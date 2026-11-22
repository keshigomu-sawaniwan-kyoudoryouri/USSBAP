package com.jin.bitperfect

import android.content.Context
import android.net.Uri
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureDownloadManager {
    private const val KEY_ALIAS = "signal_lab_downloads_v1"
    private const val PREFS = "BitPerfectPrefs"
    private const val INDEX = "secure_download_index_v1"
    private val LIGHT_MAGIC = byteArrayOf(0x53, 0x4c, 0x4d, 0x31) // SLM1
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val queue=Executors.newSingleThreadExecutor{r->Thread(r,"ProtectedDownloadQueue")}
    private val cancelled=AtomicBoolean(false)
    private val paused=AtomicBoolean(false)
    @Volatile private var activeTitle=""

    data class Entry(val id: String, val title: String, val artist: String, val sourceUrl: String, val thumbnail: String, val video: Boolean, val bytes: Long,val quality:Int=0,val createdAt:Long=System.currentTimeMillis(),val durationSeconds:Long=0)

    fun download(context: Context, result: YtAudioResult, sourceUrl: String, video: Boolean, quality: Int, onProgress: (Int) -> Unit, onDone: (Entry) -> Unit, onError: (String) -> Unit) {
        queue.execute {
            var plain: File? = null
            val temporary=mutableListOf<File>()
            var completed = false
            try {
                cancelled.set(false);paused.set(false);activeTitle=result.title
                val selection = selectMedia(result, video, quality)
                if (selection.primary.isEmpty()) error("保存可能なメディアURLがありません")
                val id = UUID.nameUUIDFromBytes("$sourceUrl|$video|$quality".toByteArray()).toString()
                if(selection.audioExtra!=null){val vp=File(context.cacheDir,"download_$id.video.part");val ap=File(context.cacheDir,"download_$id.audio.part");temporary.add(vp);temporary.add(ap);downloadFile(context,result.title,selection.primary,vp,0,55,onProgress);downloadFile(context,result.title,selection.audioExtra,ap,55,90,onProgress);plain=File(context.cacheDir,"download_$id.mux.mp4");temporary.add(plain);val session=FFmpegKit.execute("-y -i '${vp.absolutePath}' -i '${ap.absolutePath}' -map 0:v:0 -map 1:a:0 -c copy -movflags +faststart '${plain.absolutePath}'");if(!ReturnCode.isSuccess(session.returnCode))error("動画と音声の結合に失敗しました")}
                else{plain=File(context.cacheDir,"download_$id.part");downloadFile(context,result.title,selection.primary,plain,0,95,onProgress)}
                val dir = File(context.filesDir, "protected_media").apply { mkdirs() }
                val encrypted = File(dir, "$id.slm")
                obfuscate(plain, encrypted, id)
                val entry = Entry(id, result.title, result.uploader, sourceUrl, result.thumbnailUrl, video, encrypted.length(),quality,System.currentTimeMillis(),result.durationSeconds)
                val entries = list(context).toMutableList().apply { add(0, entry) }
                saveIndex(context, entries.distinctBy{it.id});notifyProgress(context,result.title,100,true); onDone(entry);plain.delete();completed=true
            } catch (t: Throwable) { onError(t.message ?: "ダウンロードに失敗しました") }
            finally {
                activeTitle=""
                // Preserve partial adaptive streams after transient failures so a
                // later request can continue with HTTP Range.
                if(completed || cancelled.get()) temporary.forEach{it.delete()}
            }
        }
    }

    fun cancelActive(){cancelled.set(true)}
    fun pauseActive(){paused.set(true)}
    fun resumeActive(){paused.set(false)}
    fun isPaused()=paused.get()
    fun activeTask():String=activeTitle
    private fun notifyProgress(context:Context,title:String,progress:Int,done:Boolean){val manager=context.getSystemService(NotificationManager::class.java);val channel="signal_downloads";manager.createNotificationChannel(NotificationChannel(channel,"Downloads",NotificationManager.IMPORTANCE_LOW));val notification=NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(if(done)"保存完了" else "ダウンロード中").setContentText(title).setOnlyAlertOnce(true).setOngoing(!done).setProgress(100,progress,false).build();manager.notify(title.hashCode(),notification)}

    private data class MediaSelection(val primary:String,val audioExtra:String?=null)
    private fun downloadFile(context:Context,title:String,url:String,file:File,start:Int,end:Int,onProgress:(Int)->Unit){val resumeAt=file.length();val request=Request.Builder().url(url).apply{if(YtStreamHelper.cookies.isNotEmpty())addHeader("Cookie",YtStreamHelper.cookies);if(resumeAt>0)addHeader("Range","bytes=$resumeAt-")}.build();val response=client.newCall(request).execute();if(!response.isSuccessful)error("Download HTTP ${response.code}");val body=response.body?:error("空のレスポンスです");val append=resumeAt>0&&response.code==206;if(!append&&resumeAt>0)file.delete();val base=if(append)resumeAt else 0L;val total=(body.contentLength()+base).coerceAtLeast(1L);var read=base;var last=-1;body.byteStream().use{input->FileOutputStream(file,append).use{output->val buffer=ByteArray(128*1024);while(true){if(cancelled.get())error("キャンセルしました");while(paused.get()&&!cancelled.get())Thread.sleep(100);val n=input.read(buffer);if(n<0)break;output.write(buffer,0,n);read+=n;val raw=(read*100/total).toInt();val progress=start+(raw*(end-start)/100);onProgress(progress);if(progress/5!=last/5){last=progress;notifyProgress(context,title,progress,false)}}}}}
    private fun selectMedia(result: YtAudioResult, video: Boolean, quality: Int): MediaSelection {
        if (result.fullJson.isNotEmpty()) try {
            val formats = JSONObject(result.fullJson).optJSONArray("formats")
            if (formats != null) {
                data class Candidate(val url: String, val score: Int)
                val candidates = mutableListOf<Candidate>();val audioCandidates=mutableListOf<Candidate>()
                val videoLimit = when (quality) { 1 -> 1080; 2 -> 720; else -> Int.MAX_VALUE }
                val audioLimit = when (quality) { 1 -> 256; 2 -> 160; else -> Int.MAX_VALUE }
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue; val url = f.optString("url"); if (url.isEmpty()) continue
                    val acodec = f.optString("acodec", "none"); val vcodec = f.optString("vcodec", "none")
                    if (video && vcodec != "none") { val height=f.optInt("height",0); if(height<=videoLimit)candidates.add(Candidate(url,height+(if(acodec!="none")1 else 10000))) }
                    if (!video && acodec != "none" && vcodec == "none") { val abr=f.optDouble("abr",f.optDouble("tbr",0.0)).toInt(); if(abr<=audioLimit)candidates.add(Candidate(url,abr)) }
                    if(video&&acodec!="none"&&vcodec=="none")audioCandidates.add(Candidate(url,f.optDouble("abr",f.optDouble("tbr",0.0)).toInt()))
                }
                candidates.maxByOrNull { it.score }?.let { chosen -> val adaptive=chosen.score>=10000;return MediaSelection(chosen.url,if(adaptive)audioCandidates.maxByOrNull{it.score}?.url else null) }
            }
        } catch (_: Exception) {}
        return MediaSelection(if (video && result.videoUrl.isNotEmpty()) result.videoUrl else result.audioUrl,if(video&&result.videoUrl.isNotEmpty()&&result.audioUrl.isNotEmpty())result.audioUrl else null)
    }

    fun list(context: Context): List<Entry> = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(INDEX, null) ?: return emptyList()
        val array = JSONArray(raw); (0 until array.length()).map { i -> val o = array.getJSONObject(i); Entry(o.getString("id"), o.getString("title"), o.optString("artist"), o.optString("source"), o.optString("thumbnail"), o.optBoolean("video"), o.optLong("bytes"),o.optInt("quality",0),o.optLong("createdAt",0),o.optLong("duration",0)) }
    } catch (_: Exception) { emptyList() }

    fun preparePlayback(context: Context, entry: Entry): Uri {
        val started = android.os.SystemClock.elapsedRealtime()
        val source = File(File(context.filesDir, "protected_media"), "${entry.id}.slm")
        val output = File(context.cacheDir, "secure_playback_${entry.id}.${if (entry.video) "mp4" else "m4a"}")
        if (isLight(source)) deobfuscate(source, output, entry.id) else migrateOne(source, output, entry.id)
        AppLogger.i("DL_PERF", "preparePlayback ${entry.title}: ${android.os.SystemClock.elapsedRealtime()-started}ms (${source.length()/1024}KiB)")
        return Uri.fromFile(output)
    }

    fun migrateLegacyInBackground(context: Context) {
        Thread {
            val dir = File(context.filesDir, "protected_media")
            list(context).forEach { entry ->
                val source=File(dir,"${entry.id}.slm"); if(!source.exists()||isLight(source))return@forEach
                val started=android.os.SystemClock.elapsedRealtime(); val plain=File.createTempFile("slm_migrate_",".tmp",context.cacheDir); val converted=File(dir,"${entry.id}.new")
                try { decrypt(source,plain);obfuscate(plain,converted,entry.id);if(source.delete()&&converted.renameTo(source))AppLogger.i("DL_PERF","migrated ${entry.title}: ${android.os.SystemClock.elapsedRealtime()-started}ms") else converted.delete() } catch(t:Throwable){AppLogger.w("DL_PERF","migration failed: ${entry.title}",t)} finally {plain.delete()}
            }
        }.apply{name="DownloadFormatMigration";priority=Thread.MIN_PRIORITY;start()}
    }

    fun delete(context: Context, entry: Entry) {
        File(File(context.filesDir, "protected_media"), "${entry.id}.slm").delete()
        File(context.cacheDir, "secure_playback_${entry.id}.${if (entry.video) "mp4" else "m4a"}").delete()
        saveIndex(context, list(context).filterNot { it.id == entry.id })
    }

    private fun saveIndex(context: Context, entries: List<Entry>) {
        val array = JSONArray(); entries.forEach { e -> array.put(JSONObject().apply { put("id", e.id); put("title", e.title); put("artist", e.artist); put("source", e.sourceUrl); put("thumbnail", e.thumbnail); put("video", e.video); put("bytes", e.bytes);put("quality",e.quality);put("createdAt",e.createdAt);put("duration",e.durationSeconds) }) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(INDEX, array.toString()).apply()
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()) }.generateKey()
    }
    private fun encrypt(input: File, output: File) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        FileOutputStream(output).use { raw -> raw.write(cipher.iv.size); raw.write(cipher.iv); CipherOutputStream(raw, cipher).use { encrypted -> FileInputStream(input).use { it.copyTo(encrypted, 128 * 1024) } } }
    }
    private fun decrypt(input: File, output: File) {
        FileInputStream(input).use { raw -> val size = raw.read(); require(size in 12..32); val iv = ByteArray(size); require(raw.read(iv) == size); val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }; CipherInputStream(raw, cipher).use { decrypted -> FileOutputStream(output).use { decrypted.copyTo(it, 128 * 1024) } } }
    }

    private fun isLight(file:File):Boolean=try{FileInputStream(file).use{input->val magic=ByteArray(4);input.read(magic)==4&&magic.contentEquals(LIGHT_MAGIC)}}catch(_:Exception){false}
    private fun mask(id:String)=MessageDigest.getInstance("SHA-256").digest("SIGNAL-LAB-LIGHT:$id".toByteArray())
    private fun obfuscate(input:File,output:File,id:String){val key=mask(id);FileInputStream(input).use{src->FileOutputStream(output).use{dst->dst.write(LIGHT_MAGIC);val buffer=ByteArray(256*1024);var offset=0L;while(true){val n=src.read(buffer);if(n<0)break;for(i in 0 until n){val p=offset+i;buffer[i]=(buffer[i].toInt() xor key[(p%key.size).toInt()].toInt() xor (((p*31+17) and 0xff).toInt())).toByte()};dst.write(buffer,0,n);offset+=n}}}}
    private fun deobfuscate(input:File,output:File,id:String){val key=mask(id);FileInputStream(input).use{src->repeat(4){src.read()};FileOutputStream(output).use{dst->val buffer=ByteArray(256*1024);var offset=0L;while(true){val n=src.read(buffer);if(n<0)break;for(i in 0 until n){val p=offset+i;buffer[i]=(buffer[i].toInt() xor key[(p%key.size).toInt()].toInt() xor (((p*31+17) and 0xff).toInt())).toByte()};dst.write(buffer,0,n);offset+=n}}}}
    private fun migrateOne(source:File,output:File,id:String){val plain=File(output.parentFile,"${output.name}.legacy");val converted=File(source.parentFile,"${source.name}.new");try{decrypt(source,plain);obfuscate(plain,converted,id);deobfuscate(converted,output,id);if(source.delete())converted.renameTo(source)}finally{plain.delete();converted.delete()}}
}
