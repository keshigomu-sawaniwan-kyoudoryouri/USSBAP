package com.jin.bitperfect

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Shared async loader for local embedded covers and remote artwork. */
object AlbumArtLoader {
    private const val MAX_DIMENSION = 384
    private const val DISK_LIMIT_BYTES = 32L * 1024L * 1024L
    private const val MAX_DOWNLOAD_BYTES = 24L * 1024L * 1024L

    private val memoryCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pendingLock = Any()
    private val pending = HashMap<String, MutableList<WeakReference<ImageView>>>()
    private val executor = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "AlbumArtLoader").apply { isDaemon = true }
    }

    /** The caller supplies its placeholder; callbacks are discarded after a row is rebound. */
    fun load(context: Context, uri: Uri, target: ImageView) {
        val key = uri.toString()
        if (target.tag != key) target.setImageResource(R.drawable.ic_default_art)
        target.tag = key
        memoryCache.get(key)?.let { target.setImageBitmap(it); return }

        var startLoad = false
        synchronized(pendingLock) {
            val listeners = pending[key]
            if (listeners == null) {
                pending[key] = mutableListOf(WeakReference(target))
                startLoad = true
            } else {
                listeners.add(WeakReference(target))
            }
        }
        if (!startLoad) return

        val appContext = context.applicationContext
        executor.execute {
            val bitmap = try {
                loadCached(appContext, key) ?: loadArtwork(appContext, uri)?.also {
                    if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) saveCached(appContext, key, it)
                }
            } catch (_: Throwable) {
                null
            }
            if (bitmap != null) memoryCache.put(key, bitmap)
            val listeners = synchronized(pendingLock) { pending.remove(key).orEmpty() }
            listeners.forEach { reference ->
                reference.get()?.let { image ->
                    image.post {
                        if (image.tag == key && bitmap != null) image.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun loadCached(context: Context, key: String): Bitmap? {
        val file = cacheFile(context, key)
        if (!file.isFile) return null
        return decodeFile(file).also { if (it == null) file.delete() }
    }

    private fun loadArtwork(context: Context, uri: Uri): Bitmap? {
        return if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            val file = cacheFile(context, uri.toString())
            downloadAndDecode(uri, file)
        } else {
            val bytes = embeddedPicture(context.contentResolver, uri) ?: return null
            decodeBytes(bytes)
        }
    }

    private fun embeddedPicture(resolver: ContentResolver, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                retriever.embeddedPicture
            }
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    private fun downloadAndDecode(uri: Uri, cacheFile: File): Bitmap? {
        val directory = cacheFile.parentFile ?: return null
        if (!directory.exists()) directory.mkdirs()
        val temp = File(directory, "${cacheFile.name}.download")
        val connection = URL(uri.toString()).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7_000
            connection.readTimeout = 7_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            var total = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_DOWNLOAD_BYTES) return null
                        output.write(buffer, 0, count)
                    }
                }
            }
            val bitmap = decodeFile(temp) ?: return null
            if (temp.renameTo(cacheFile)) trimDiskCache(directory)
            return bitmap
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.scaleToLimit()
    }

    private fun decodeFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)?.scaleToLimit()
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_DIMENSION && height / (sample * 2) >= MAX_DIMENSION) sample *= 2
        return sample
    }

    private fun Bitmap.scaleToLimit(): Bitmap {
        val largest = maxOf(width, height)
        if (largest <= MAX_DIMENSION) return this
        val scale = MAX_DIMENSION.toFloat() / largest
        val resized = Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
        if (resized !== this) recycle()
        return resized
    }

    private fun saveCached(context: Context, key: String, bitmap: Bitmap) {
        val directory = File(context.cacheDir, "album_art")
        if (!directory.exists() && !directory.mkdirs()) return
        val file = cacheFile(context, key)
        val temp = File(directory, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            trimDiskCache(directory)
        } catch (_: Throwable) {
            temp.delete()
        }
    }

    private fun cacheFile(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(File(context.cacheDir, "album_art"), "$name.jpg")
    }

    private fun trimDiskCache(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "jpg" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= DISK_LIMIT_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }
}
