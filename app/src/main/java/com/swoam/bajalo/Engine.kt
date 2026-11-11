package com.swoam.bajalo

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cola de descargas. Vive mientras viva el proceso; el servicio en primer plano
 * (DownloadService) mantiene el proceso vivo mientras haya descargas activas.
 */
object Engine {
    enum class Status { FETCHING, READY, QUEUED, DOWNLOADING, ERROR }

    data class Item(
        val id: Long,
        val url: String,
        val title: String,
        val info: String = "",
        val status: Status = Status.FETCHING,
        val progress: Int = -1,
        val preset: Preset,
        val error: String? = null,
    ) {
        val active get() = status == Status.QUEUED || status == Status.DOWNLOADING
    }

    private const val PARALLEL = 2

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Boolean>()
    private val slots = Semaphore(PARALLEL)
    private val nextId = AtomicLong(System.currentTimeMillis())
    private val jobs = ConcurrentHashMap<Long, Job>()

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    @Volatile
    var engineVersion: String? = null
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        scope.launch {
            val ok = try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                engineVersion = YoutubeDL.getInstance().version(app)
                true
            } catch (e: Throwable) {
                AppLog.log(app, "Error al iniciar el motor: ${e.message}")
                false
            }
            ready.complete(ok)
        }
    }

    private fun get(id: Long) = _items.value.firstOrNull { it.id == id }

    private fun update(id: Long, f: (Item) -> Item) =
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }

    /** Añade los enlaces que haya en [text]. Devuelve cuántos son nuevos. */
    fun add(text: String, preset: Preset, autoStart: Boolean = false): Int {
        val existing = _items.value.map { it.url }.toSet()
        val fresh = Links.extract(text).filter { it !in existing }
        for (url in fresh) {
            val id = nextId.incrementAndGet()
            _items.update { it + Item(id, url, title = url, preset = preset) }
            scope.launch { fetchInfo(id, url) }
            if (autoStart) start(id)
        }
        return fresh.size
    }

    private suspend fun fetchInfo(id: Long, url: String) {
        if (!ready.await()) {
            update(id) { it.copy(status = Status.ERROR, error = "El motor no se pudo iniciar. Mira el registro.") }
            return
        }
        val cookies = Cookies.copyFor(app, -id)
        try {
            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--playlist-items", "1")
                cookies?.let { addOption("--cookies", it.absolutePath) }
            }
            val info = YoutubeDL.getInstance().getInfo(req)
            val title = info.title?.takeIf { it.isNotBlank() } ?: info.fulltitle ?: url
            val details = listOfNotNull(
                info.extractorKey,
                info.uploader?.takeIf { it.isNotBlank() },
                info.duration.takeIf { it > 0 }?.let(::formatDuration),
            ).joinToString(" · ")
            update(id) {
                it.copy(
                    title = title,
                    info = details,
                    status = if (it.status == Status.FETCHING) Status.READY else it.status,
                )
            }
        } catch (e: Exception) {
            AppLog.log(app, "No se pudo leer la información de $url: ${shortError(e)}")
            update(id) {
                it.copy(
                    info = "No se pudo leer la información; puedes intentar descargar igual.",
                    status = if (it.status == Status.FETCHING) Status.READY else it.status,
                )
            }
        } finally {
            cookies?.delete()
        }
    }

    fun setPreset(id: Long, preset: Preset) = update(id) { if (it.active) it else it.copy(preset = preset) }

    fun setAllPresets(preset: Preset) =
        _items.update { list -> list.map { if (it.active) it else it.copy(preset = preset) } }

    fun start(id: Long) {
        val item = get(id) ?: return
        if (item.active) return
        update(id) { it.copy(status = Status.QUEUED, progress = -1, error = null) }
        DownloadService.ensureRunning(app)
        jobs[id] = scope.launch { download(id) }
    }

    fun startAll() = _items.value.filterNot { it.active }.forEach { start(it.id) }

    fun cancel(id: Long) {
        YoutubeDL.getInstance().destroyProcessById(id.toString())
        jobs.remove(id)?.cancel()
        update(id) { it.copy(status = Status.READY, progress = -1) }
    }

    fun remove(id: Long) {
        if (get(id)?.active == true) cancel(id)
        _items.update { list -> list.filterNot { it.id == id } }
    }

    private suspend fun download(id: Long) = slots.withPermit {
        if (!ready.await()) {
            update(id) { it.copy(status = Status.ERROR, error = "El motor no se pudo iniciar. Mira el registro.") }
            return@withPermit
        }
        val item = get(id) ?: return@withPermit
        if (item.status != Status.QUEUED) return@withPermit
        update(id) { it.copy(status = Status.DOWNLOADING, progress = -1) }

        val dir = File(app.cacheDir, "dl/$id").apply { deleteRecursively(); mkdirs() }
        val cookies = Cookies.copyFor(app, id)
        try {
            val req = YoutubeDLRequest(item.url).apply {
                addOption("-o", File(dir, "%(title).90B [%(id)s].%(ext)s").absolutePath)
                addOption("--no-playlist") // un enlace de video dentro de una lista baja solo ese video
                addOption("--no-mtime")
                addOption("--windows-filenames") // nombres válidos en cualquier almacenamiento
                addOption("--concurrent-fragments", 4)
                cookies?.let { addOption("--cookies", it.absolutePath) }
            }
            item.preset.applyTo(req)
            AppLog.log(app, "Descargando (${item.preset.label}): ${item.url}")

            var last = -2
            YoutubeDL.getInstance().execute(req, id.toString()) { progress, _, _ ->
                val p = progress.toInt()
                if (p != last) {
                    last = p
                    update(id) { it.copy(progress = p) }
                }
            }

            val files = dir.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                .sortedBy { it.name }
                .toList()
            if (files.isEmpty()) throw IOException("yt-dlp terminó sin generar archivos")

            val saved = withContext(Dispatchers.IO) { files.map { Saver.save(app, it) } }
            val title = get(id)?.title ?: item.url
            AppLog.log(app, "Guardado: ${saved.joinToString { "${it.kind.folder}/${it.name}" }}")
            _events.tryEmit(if (saved.size == 1) "Guardado: $title" else "Guardados ${saved.size} archivos de: $title")
            Notifs.done(app, title, saved.size)
            // Lista limpia: lo terminado desaparece de la cola
            _items.update { list -> list.filterNot { it.id == id } }
        } catch (e: YoutubeDL.CanceledException) {
            AppLog.log(app, "Cancelado: ${item.url}")
        } catch (e: CancellationException) {
            AppLog.log(app, "Cancelado: ${item.url}")
            throw e
        } catch (e: Exception) {
            val msg = shortError(e)
            AppLog.log(app, "Error con ${item.url}: ${e.message}")
            update(id) { it.copy(status = Status.ERROR, error = msg, progress = -1) }
        } finally {
            dir.deleteRecursively()
            cookies?.delete()
            jobs.remove(id)
        }
    }

    /** Actualiza yt-dlp (el "motor" que entiende cada sitio). */
    suspend fun updateEngine(): String = withContext(Dispatchers.IO) {
        if (!ready.await()) return@withContext "El motor no está disponible"
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE)
            engineVersion = YoutubeDL.getInstance().version(app)
            val msg = if (status == YoutubeDL.UpdateStatus.DONE) "Motor actualizado a $engineVersion"
            else "El motor ya está al día ($engineVersion)"
            AppLog.log(app, msg)
            msg
        } catch (e: Exception) {
            AppLog.log(app, "Error al actualizar el motor: ${e.message}")
            "No se pudo actualizar: ${shortError(e)}"
        }
    }

    /** Convierte la salida de yt-dlp en un mensaje corto y entendible. */
    private fun shortError(e: Throwable): String {
        val text = e.message.orEmpty()
        val line = text.lines().lastOrNull { it.contains("ERROR:") }
            ?.substringAfter("ERROR:")?.trim()
            ?: text.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?: e.javaClass.simpleName
        val hint = when {
            line.contains("login", true) || line.contains("sign in", true) ||
                line.contains("cookies", true) || line.contains("private", true) ->
                " Prueba conectando tu cuenta en Cuentas."
            line.contains("Unsupported URL", true) -> " Este sitio no está soportado."
            line.contains("No video formats", true) ->
                " Puede ser una publicación solo de fotos o necesitar sesión."
            else -> ""
        }
        return line.take(220) + hint
    }
}
