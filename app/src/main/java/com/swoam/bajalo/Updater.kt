package com.swoam.bajalo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualizaciones de la app desde los Releases de GitHub de Swoam
 * (repositorio configurado en BuildConfig.UPDATE_REPO). Descarga el APK
 * y abre el instalador de Android sin salir de la app.
 */
object Updater {
    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    private const val CHECK_EVERY_MS = 12L * 3600 * 1000

    val releasesPage get() = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest"

    private fun sp(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /** true si toca comprobar automáticamente (como mucho cada 12 h). */
    fun shouldAutoCheck(c: Context) =
        System.currentTimeMillis() - sp(c).getLong("last", 0) > CHECK_EVERY_MS

    /** Versión que el usuario decidió saltarse. */
    fun isSkipped(c: Context, version: String) = sp(c).getString("skip", null) == version
    fun skip(c: Context, version: String) = sp(c).edit().putString("skip", version).apply()

    /** Devuelve la release si es más nueva que la instalada; null si ya estás al día. */
    suspend fun check(c: Context): Release? = withContext(Dispatchers.IO) {
        sp(c).edit().putLong("last", System.currentTimeMillis()).apply()
        val json = get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val o = JSONObject(json)
        val version = o.getString("tag_name").removePrefix("v").removePrefix("V")
        val assets = o.optJSONArray("assets")
        var apk: JSONObject? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                    apk = a
                    break
                }
            }
        }
        apk ?: throw IOException("La versión $version no tiene APK publicado")
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return@withContext null
        Release(
            version = version,
            notes = o.optString("body").trim(),
            apkUrl = apk.getString("browser_download_url"),
            size = apk.optLong("size"),
        )
    }

    /** Compara "1.10.2" con "1.9" número a número. Las builds "-dev" cuentan como más viejas. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return local.contains('-') && !remote.contains('-')
    }

    suspend fun download(c: Context, r: Release, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(c.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
            val out = File(dir, "Bajalo-${r.version}.apk")
            val conn = open(r.apkUrl, json = false)
            try {
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) {
                                    last = p
                                    withContext(Dispatchers.Main) { onProgress(p) }
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            out
        }

    /**
     * Abre el instalador. Si Android aún no permite instalar desde esta app,
     * devuelve false y abre el ajuste para permitirlo.
     */
    fun install(c: Context, apk: File): Boolean {
        if (!c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", apk)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    private fun open(url: String, json: Boolean = true): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (json) conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Bajalo-Android")
        val code = conn.responseCode
        if (code == 404) throw IOException("Todavía no hay versiones publicadas")
        if (code !in 200..299) throw IOException("GitHub respondió $code")
        return conn
    }

    private fun get(url: String): String {
        val conn = open(url)
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
