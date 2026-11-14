package com.swoam.bajalo

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File

/** Plataformas en las que se puede iniciar sesión dentro de la app. */
enum class Platform(
    val title: String,
    val hint: String,
    val loginUrl: String,
    /** (URL de la que leer cookies, dominio con el que guardarlas) */
    val cookieSources: List<Pair<String, String>>,
    /** Cookies que indican que la sesión está iniciada. */
    val authCookies: Set<String>,
) {
    YOUTUBE(
        "YouTube", "Privados o con restricción de edad",
        "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F",
        listOf(
            "https://www.youtube.com" to "youtube.com",
            "https://accounts.google.com" to "google.com",
            "https://www.google.com" to "google.com",
        ),
        setOf("SAPISID", "__Secure-3PSID", "SID", "LOGIN_INFO"),
    ),
    INSTAGRAM(
        "Instagram", "Reels, historias, carruseles y cuentas privadas",
        "https://www.instagram.com/accounts/login/",
        listOf("https://www.instagram.com" to "instagram.com"),
        setOf("sessionid"),
    ),
    FACEBOOK(
        "Facebook", "Grupos y perfiles privados",
        "https://www.facebook.com/login/",
        listOf("https://www.facebook.com" to "facebook.com", "https://m.facebook.com" to "facebook.com"),
        setOf("c_user"),
    ),
    X(
        "X / Twitter", "Videos marcados como sensibles",
        "https://x.com/i/flow/login",
        listOf("https://x.com" to "x.com", "https://twitter.com" to "twitter.com"),
        setOf("auth_token"),
    ),
    TIKTOK(
        "TikTok", "Videos con restricción de edad o privados",
        "https://www.tiktok.com/login",
        listOf("https://www.tiktok.com" to "tiktok.com"),
        setOf("sessionid"),
    );

    val domains: Set<String> get() = cookieSources.map { it.second }.toSet()
}

/**
 * Guarda las sesiones en un único cookies.txt (formato Netscape) que se le pasa a yt-dlp.
 * El archivo vive en el almacenamiento privado de la app.
 */
object Cookies {
    private const val HEADER = "# Netscape HTTP Cookie File"

    private data class Line(val raw: String, val domain: String, val name: String)

    fun file(c: Context) = File(c.filesDir, "cookies.txt")

    @Synchronized
    private fun lines(c: Context): List<Line> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull(::parse)
    }

    private fun parse(raw: String): Line? {
        val s = raw.trimEnd('\r')
        if (s.isBlank()) return null
        if (s.startsWith("#") && !s.startsWith("#HttpOnly_")) return null
        val parts = s.split('\t')
        if (parts.size < 7) return null
        val domain = parts[0].removePrefix("#HttpOnly_").trimStart('.').lowercase()
        return Line(s, domain, parts[5])
    }

    @Synchronized
    private fun write(c: Context, lines: List<Line>) {
        val f = file(c)
        if (lines.isEmpty()) {
            f.delete()
            return
        }
        f.writeText(HEADER + "\n\n" + lines.joinToString("\n") { it.raw } + "\n")
    }

    private fun matches(domain: String, base: String) = domain == base || domain.endsWith(".$base")

    fun hasAny(c: Context) = lines(c).isNotEmpty()

    fun isConnected(c: Context, p: Platform) = lines(c).any { l ->
        p.domains.any { matches(l.domain, it) } && l.name in p.authCookies
    }

    /** Lee las cookies del WebView tras iniciar sesión. Devuelve false si no hay sesión. */
    @Synchronized
    fun saveFromWebView(c: Context, p: Platform): Boolean {
        val cm = CookieManager.getInstance()
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val fresh = linkedMapOf<Pair<String, String>, Line>()
        for ((url, domain) in p.cookieSources) {
            val raw = cm.getCookie(url) ?: continue
            raw.split(";").forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) return@forEach
                val name = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                if (name.isEmpty()) return@forEach
                val line = ".$domain\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"
                fresh[domain to name] = Line(line, domain, name)
            }
        }
        if (fresh.values.none { it.name in p.authCookies }) return false
        val kept = lines(c).filterNot { l -> p.domains.any { matches(l.domain, it) } }
        write(c, kept + fresh.values)
        return true
    }

    @Synchronized
    fun logout(c: Context, p: Platform) {
        write(c, lines(c).filterNot { l -> p.domains.any { matches(l.domain, it) } })
        // Cierra también la sesión del navegador interno
        val cm = CookieManager.getInstance()
        for ((url, _) in p.cookieSources) {
            cm.getCookie(url)?.split(";")?.forEach { pair ->
                val name = pair.substringBefore('=').trim()
                if (name.isNotEmpty()) {
                    cm.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
            }
        }
        cm.flush()
    }

    /** Importa un cookies.txt exportado desde el navegador. Devuelve cuántas cookies se leyeron. */
    @Synchronized
    fun import(c: Context, uri: Uri): Int {
        val text = c.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return 0
        val imported = text.lines().mapNotNull(::parse)
        if (imported.isEmpty()) return 0
        val keys = imported.map { it.domain to it.name }.toSet()
        val kept = lines(c).filterNot { (it.domain to it.name) in keys }
        write(c, kept + imported)
        return imported.size
    }

    /**
     * Copia el cookies.txt a un archivo temporal para una descarga concreta
     * (yt-dlp reescribe el archivo al terminar y así no se pisan descargas paralelas).
     */
    fun copyFor(c: Context, id: Long): File? {
        val src = file(c)
        if (!src.exists() || !hasAny(c)) return null
        val dst = File(File(c.cacheDir, "ck").apply { mkdirs() }, "$id.txt")
        synchronized(this) { src.copyTo(dst, overwrite = true) }
        return dst
    }
}
