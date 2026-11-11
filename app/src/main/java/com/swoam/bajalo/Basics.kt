package com.swoam.bajalo

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Calidades que el usuario puede elegir para cada enlace. */
enum class Preset(val label: String) {
    BEST("Mejor calidad"),
    P1080("Video hasta 1080p"),
    P720("Video hasta 720p"),
    P480("Video hasta 480p"),
    MP3("Audio MP3"),
    M4A("Audio M4A (original)");

    fun applyTo(r: YoutubeDLRequest) {
        when (this) {
            BEST -> video(r, null)
            P1080 -> video(r, 1080)
            P720 -> video(r, 720)
            P480 -> video(r, 480)
            MP3 -> {
                r.addOption("-f", "ba/b")
                r.addOption("-x")
                r.addOption("--audio-format", "mp3")
                r.addOption("--audio-quality", "0")
            }
            M4A -> {
                // M4A conserva el audio original de la plataforma cuando existe
                r.addOption("-f", "ba[ext=m4a]/ba/b")
                r.addOption("-x")
                r.addOption("--audio-format", "m4a")
            }
        }
    }

    private fun video(r: YoutubeDLRequest, maxHeight: Int?) {
        r.addOption("-f", "bv*+ba/b")
        // Prioriza resolución y, a igual resolución, H.264/AAC (se reproducen en cualquier teléfono)
        val res = if (maxHeight == null) "res" else "res:$maxHeight"
        r.addOption("-S", "$res,vcodec:h264,acodec:m4a")
        r.addOption("--merge-output-format", "mp4")
    }
}

/** Ajustes persistentes. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun preset(c: Context): Preset =
        runCatching { Preset.valueOf(sp(c).getString("preset", null)!!) }.getOrDefault(Preset.BEST)

    fun setPreset(c: Context, p: Preset) = sp(c).edit().putString("preset", p.name).apply()

    fun treeUri(c: Context): Uri? = sp(c).getString("tree", null)?.let(Uri::parse)

    fun setTreeUri(c: Context, uri: Uri?) = sp(c).edit().putString("tree", uri?.toString()).apply()

    fun nightMode(c: Context): Int =
        sp(c).getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setNightMode(c: Context, mode: Int) = sp(c).edit().putInt("night", mode).apply()
}

/** Registro persistente que se ve con "Ver registro". */
object AppLog {
    private const val MAX_LINES = 500
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    private fun file(c: Context) = File(c.filesDir, "registro.txt")

    @Synchronized
    fun log(c: Context, msg: String) {
        val f = file(c)
        val old = if (f.exists()) f.readLines() else emptyList()
        val line = "${fmt.format(Date())}  ${msg.trim()}"
        f.writeText((old + line).takeLast(MAX_LINES).joinToString("\n"))
    }

    @Synchronized
    fun read(c: Context): String = file(c).takeIf { it.exists() }?.readText().orEmpty()

    @Synchronized
    fun clear(c: Context) {
        file(c).delete()
    }
}

/** Extrae enlaces de un texto (pegado o compartido desde otra app). */
object Links {
    private val urlRegex = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")

    fun extract(text: String): List<String> =
        urlRegex.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'') }
            .map { if (it.startsWith("www.", ignoreCase = true)) "https://$it" else it }
            .distinct()
            .toList()
}

fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Añade a la vista el espacio de las barras del sistema (Android 15 dibuja de borde a borde). */
fun View.padForSystemBars() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val s = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        v.setPadding(l + s.left, t + s.top, r + s.right, b + s.bottom)
        insets
    }
}
