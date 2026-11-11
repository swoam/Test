package com.swoam.bajalo

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Un archivo ya guardado, para la pantalla "Mis descargas". */
data class Record(
    val name: String,
    val uri: String,
    val kind: Saver.Kind,
    val mime: String,
    val time: Long,
    /** Ruta real (solo Android 8/9, donde se escribe directo en Descargas). */
    val path: String? = null,
)

/** Mueve lo que descarga yt-dlp a la carpeta pública (Descargas/Bajalo o la carpeta elegida). */
object Saver {
    enum class Kind(val folder: String, val label: String) {
        VIDEO("Video", "Video"), AUDIO("Audio", "Audio"), PHOTO("Fotos", "Foto")
    }

    const val BASE = "Bajalo"

    private val audioExt = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "wav", "flac", "weba")
    private val photoExt = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "avif")

    fun kindOf(name: String): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in audioExt -> Kind.AUDIO
            in photoExt -> Kind.PHOTO
            else -> Kind.VIDEO
        }
    }

    fun mimeOf(name: String, kind: Kind): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val special = when (ext) {
            "m4a" -> "audio/mp4"
            "opus" -> "audio/ogg"
            "webm" -> if (kind == Kind.AUDIO) "audio/webm" else "video/webm"
            else -> null
        }
        return special ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (kind) {
            Kind.VIDEO -> "video/mp4"
            Kind.AUDIO -> "audio/mpeg"
            Kind.PHOTO -> "image/jpeg"
        }
    }

    fun save(c: Context, src: File): Record {
        val kind = kindOf(src.name)
        val mime = mimeOf(src.name, kind)
        val tree = Prefs.treeUri(c)
        var path: String? = null
        val uri: Uri = when {
            tree != null -> try {
                saveToTree(c, tree, src, kind)
            } catch (e: Exception) {
                AppLog.log(c, "No se pudo usar la carpeta elegida (${e.message}); se usa Descargas")
                defaultSave(c, src, kind, mime).also { path = it.second }.first
            }
            else -> defaultSave(c, src, kind, mime).also { path = it.second }.first
        }
        val rec = Record(src.name, uri.toString(), kind, mime, System.currentTimeMillis(), path)
        Records.add(c, rec)
        return rec
    }

    private fun defaultSave(c: Context, src: File, kind: Kind, mime: String): Pair<Uri, String?> =
        if (Build.VERSION.SDK_INT >= 29) saveToMediaStore(c, src, kind, mime) to null
        else saveLegacy(c, src, kind, mime)

    private fun saveToTree(c: Context, tree: Uri, src: File, kind: Kind): Uri {
        val root = DocumentFile.fromTreeUri(c, tree) ?: throw IOException("carpeta no disponible")
        if (!root.canWrite()) throw IOException("sin permiso de escritura")
        val sub = root.findFile(kind.folder)?.takeIf { it.isDirectory }
            ?: root.createDirectory(kind.folder)
            ?: throw IOException("no se pudo crear ${kind.folder}")
        // octet-stream: el proveedor respeta el nombre con su extensión tal cual
        val doc = sub.createFile("application/octet-stream", src.name)
            ?: throw IOException("no se pudo crear el archivo")
        c.contentResolver.openOutputStream(doc.uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("no se pudo escribir")
        return doc.uri
    }

    private fun saveToMediaStore(c: Context, src: File, kind: Kind, mime: String): Uri {
        val cr = c.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$BASE/${kind.folder}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore rechazó el archivo")
        try {
            cr.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("no se pudo escribir")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(c: Context, src: File, kind: Kind, mime: String): Pair<Uri, String> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$BASE/${kind.folder}"
        ).apply { mkdirs() }
        var dest = File(dir, src.name)
        var n = 1
        while (dest.exists()) {
            dest = File(dir, "${src.nameWithoutExtension} (${n++}).${src.extension}")
        }
        src.copyTo(dest)
        MediaScannerConnection.scanFile(c, arrayOf(dest.path), arrayOf(mime), null)
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", dest)
        return uri to dest.path
    }

    fun delete(c: Context, r: Record): Boolean = try {
        val uri = Uri.parse(r.uri)
        when {
            r.path != null -> File(r.path).let { !it.exists() || it.delete() }
            DocumentsContract.isDocumentUri(c, uri) &&
                uri.authority != "com.android.providers.media.documents" ->
                DocumentsContract.deleteDocument(c.contentResolver, uri)
            else -> c.contentResolver.delete(uri, null, null) > 0
        }
    } catch (e: Exception) {
        AppLog.log(c, "No se pudo borrar ${r.name}: ${e.message}")
        false
    }
}

/** Historial de archivos guardados (JSON en el almacenamiento privado). */
object Records {
    private fun file(c: Context) = File(c.filesDir, "descargas.json")

    @Synchronized
    fun load(c: Context): List<Record> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Record(
                    name = o.getString("name"),
                    uri = o.getString("uri"),
                    kind = runCatching { Saver.Kind.valueOf(o.getString("kind")) }.getOrDefault(Saver.Kind.VIDEO),
                    mime = o.optString("mime", "video/mp4"),
                    time = o.optLong("time"),
                    path = o.optString("path").ifEmpty { null },
                )
            }.sortedByDescending { it.time }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun store(c: Context, list: List<Record>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("name", r.name)
                put("uri", r.uri)
                put("kind", r.kind.name)
                put("mime", r.mime)
                put("time", r.time)
                r.path?.let { put("path", it) }
            })
        }
        file(c).writeText(arr.toString())
    }

    @Synchronized
    fun add(c: Context, r: Record) = store(c, listOf(r) + load(c))

    @Synchronized
    fun remove(c: Context, r: Record) = store(c, load(c).filterNot { it.uri == r.uri })
}
