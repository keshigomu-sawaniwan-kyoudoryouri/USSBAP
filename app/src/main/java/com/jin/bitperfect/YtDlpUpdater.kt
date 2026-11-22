package com.jin.bitperfect

import android.content.Context
import com.chaquo.python.Python
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Installs newer yt-dlp wheels into app-private storage without touching the
 * bundled Chaquopy requirements. yt-dlp is pure Python, so a wheel can simply
 * be expanded and prepended to sys.path; the bundled copy remains the
 * fallback when an update is rejected or deleted.
 */
object YtDlpUpdater {
    private const val PYPI_JSON = "https://pypi.org/pypi/yt-dlp/json"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "YtDlpUpdater") }

    fun update(context: Context, callback: (Boolean, String, Throwable?) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            try {
                val wheel = fetchLatestWheel()
                val current = currentVersion(app)
                if (current != null && compareVersions(wheel.version, current) <= 0) {
                    callback(true, "latest:$current", null)
                    return@execute
                }
                downloadAndInstall(app, wheel)
                AppLogger.i("YTDLP_UPDATE", "updated to ${wheel.version} (was $current)")
                callback(true, "updated:${wheel.version}", null)
            } catch (t: Throwable) {
                AppLogger.w("YTDLP_UPDATE", "update failed", t)
                callback(false, t.message ?: t.javaClass.simpleName, t)
            }
        }
    }

    fun reset(context: Context) {
        val base = baseDir(context.applicationContext)
        base.deleteRecursively()
        clearPythonModules()
        AppLogger.i("YTDLP_UPDATE", "reverted to bundled yt-dlp")
    }

    private fun clearPythonModules() {
        try {
            val globals = Python.getInstance().getModule("builtins")?.callAttr("dict")
            val code = """
import sys
for _m in [m for m in list(sys.modules) if m == 'yt_dlp' or m.startswith('yt_dlp.')]:
    del sys.modules[_m]
result = ''
""".trimIndent()
            Python.getInstance().getModule("builtins")?.callAttr("exec", code, globals)
        } catch (_: Throwable) {}
    }

    fun activePath(context: Context): String? {
        val app = context.applicationContext
        val version = try {
            File(baseDir(app), "current.txt").readText().trim()
        } catch (_: Throwable) { "" }
        if (version.isEmpty()) return null
        val dir = File(baseDir(app), version)
        return if (File(dir, "yt_dlp/__init__.py").isFile) dir.absolutePath else null
    }

    fun currentVersion(context: Context): String? {
        val app = context.applicationContext
        activePath(app)?.let { path -> versionFromFile(File(path, "yt_dlp/version.py"))?.let { return it } }
        val bundled = File(app.filesDir, "chaquopy/AssetFinder/requirements/yt_dlp/version.py")
        versionFromFile(bundled)?.let { return it }
        return try {
            Python.getInstance().getModule("yt_dlp.version")
                ?.get("__version__")
                ?.toString()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) { null }
    }

    fun isUsingUpdate(context: Context): Boolean = activePath(context) != null

    private fun baseDir(context: Context): File = File(context.filesDir, "ytdlp")

    private data class WheelInfo(val version: String, val url: String, val sha256: String)

    private fun fetchLatestWheel(): WheelInfo {
        val request = Request.Builder().url(PYPI_JSON).build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("PyPI HTTP ${response.code}")
            response.body?.string() ?: error("PyPI empty response")
        }
        val root = JSONObject(body)
        val version = root.optJSONObject("info")?.optString("version").orEmpty()
        if (version.isEmpty()) error("PyPI version missing")
        val urls = root.optJSONArray("urls") ?: error("PyPI files missing")
        for (i in 0 until urls.length()) {
            val item = urls.optJSONObject(i) ?: continue
            val filename = item.optString("filename")
            if (item.optString("packagetype") != "bdist_wheel") continue
            if (!filename.endsWith(".whl") || !filename.contains("py3-none-any")) continue
            val url = item.optString("url")
            val sha = item.optJSONObject("digests")?.optString("sha256").orEmpty()
            if (url.isNotEmpty() && sha.isNotEmpty()) return WheelInfo(version, url, sha)
        }
        error("yt-dlp wheel not found on PyPI")
    }

    private fun downloadAndInstall(context: Context, wheel: WheelInfo) {
        val temp = File(context.cacheDir, "yt_dlp_update_${wheel.version}.whl")
        try {
            val request = Request.Builder().url(wheel.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download HTTP ${response.code}")
                val body = response.body ?: error("Download empty response")
                body.byteStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(temp.readBytes())
            val actual = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actual.equals(wheel.sha256, ignoreCase = true)) {
                error("SHA256 mismatch")
            }
            installWheel(context, temp, wheel.version)
        } finally {
            temp.delete()
        }
    }

    private fun installWheel(context: Context, wheel: File, version: String) {
        val base = baseDir(context).apply { mkdirs() }
        val staging = File(base, ".staging-$version")
        staging.deleteRecursively()
        if (!staging.mkdirs()) error("Cannot create update directory")

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(wheel))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && (name.startsWith("yt_dlp/") || name.startsWith("yt_dlp-"))) {
                        val output = File(staging, name)
                        val canonical = output.canonicalPath
                        if (!canonical.startsWith(staging.canonicalPath + File.separator)) {
                            error("Unsafe wheel entry: $name")
                        }
                        output.parentFile?.mkdirs()
                        output.outputStream().use { out -> zip.copyTo(out, 128 * 1024) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (!File(staging, "yt_dlp/__init__.py").isFile) error("Invalid yt-dlp wheel")

            val target = File(base, version)
            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }
            if (!File(target, "yt_dlp/__init__.py").isFile) error("Update install failed")
            File(base, "current.txt").writeText(version)

            try {
                verifyInstalled(target, version)
            } catch (t: Throwable) {
                File(base, "current.txt").delete()
                target.deleteRecursively()
                clearPythonModules()
                throw t
            }

            // Keep only the active version so a failed future update can still
            // fall back to the bundled package.
            base.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name != version) child.deleteRecursively()
            }
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun verifyInstalled(dir: File, expectedVersion: String) {
        val escaped = dir.absolutePath.replace("\\", "/").replace("'", "\\'")
        val code = """
import sys
sys.path.insert(0, '$escaped')
for _module in [m for m in list(sys.modules) if m == 'yt_dlp' or m.startswith('yt_dlp.')]:
    del sys.modules[_module]
import yt_dlp.version as ytv
result = ytv.__version__
""".trimIndent()
        val globals = Python.getInstance().getModule("builtins")?.callAttr("dict")
        Python.getInstance().getModule("builtins")?.callAttr("exec", code, globals)
        val actual = globals?.callAttr("get", "result", "")?.toString().orEmpty()
        if (compareVersions(actual, expectedVersion) != 0) error("yt-dlp verification failed: $actual (expected $expectedVersion)")
    }

    private fun versionFromFile(file: File): String? = try {
        if (!file.isFile) null
        else Regex("__version__\\s*=\\s*['\"]([^'\"]+)").find(file.readText())?.groupValues?.get(1)
    } catch (_: Throwable) { null }

    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(i)?.toLongOrNull() ?: 0L
            val y = right.getOrNull(i)?.toLongOrNull() ?: 0L
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
