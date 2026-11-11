package com.swoam.bajalo

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.swoam.bajalo.databinding.ActivityMainBinding
import com.swoam.bajalo.databinding.ItemAccountBinding
import com.swoam.bajalo.databinding.ItemQueueBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val adapter = QueueAdapter(
        onAction = { item ->
            if (item.active) Engine.cancel(item.id) else Engine.start(item.id)
        },
        onPreset = { item, anchor ->
            choosePreset(anchor) { p ->
                Engine.setPreset(item.id, p)
                Prefs.setPreset(this, p)
            }
        },
        onRemove = { Engine.remove(it.id) },
    )

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Prefs.setTreeUri(this, uri)
            refreshFolder()
        }
    }

    private val pickCookies = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { Cookies.import(this@MainActivity, uri) }.getOrDefault(0) }
            snack(if (n > 0) "Importadas $n cookies" else "Ese archivo no parece un cookies.txt válido")
            if (n > 0) AppLog.log(this@MainActivity, "Importado cookies.txt ($n cookies)")
            refreshAccounts()
        }
    }

    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshAccounts()
    }

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.content.padForSystemBars()

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        (b.list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        b.btnPaste.setOnClickListener { paste() }
        b.btnAdd.setOnClickListener { addFromInput() }
        b.btnStartAll.setOnClickListener { Engine.startAll() }
        b.btnQualityAll.setOnClickListener { v ->
            choosePreset(v) { p ->
                Engine.setAllPresets(p)
                Prefs.setPreset(this, p)
                snack("Calidad para toda la lista: ${p.label}")
            }
        }
        b.btnLibrary.setOnClickListener { startActivity(Intent(this, DownloadsActivity::class.java)) }
        b.btnTheme.setOnClickListener { cycleTheme() }
        b.btnImportCookies.setOnClickListener { pickCookies.launch(arrayOf("text/plain", "*/*")) }
        b.btnUpdate.setOnClickListener { updateEngine() }
        b.btnLog.setOnClickListener { showLog() }
        b.btnLog.setOnLongClickListener {
            AppLog.clear(this)
            snack("Registro borrado")
            true
        }
        b.btnFolder.setOnClickListener { pickFolder.launch(null) }
        b.btnFolder.setOnLongClickListener {
            Prefs.treeUri(this)?.let { old ->
                runCatching { contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            Prefs.setTreeUri(this, null)
            refreshFolder()
            snack("Se vuelve a guardar en Descargas › ${Saver.BASE}")
            true
        }
        b.versionText.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Engine.items.collect(::render) }
                launch { Engine.events.collect { snack(it) } }
            }
        }

        askPermissions()
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshAccounts()
        refreshFolder()
        refreshVersion()
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val n = Engine.add(text, Prefs.preset(this), autoStart = true)
        snack(
            when (n) {
                0 -> if (Links.extract(text).isEmpty()) "No encontré ningún enlace en lo compartido" else "Ese enlace ya está en la lista"
                1 -> "Descargando con calidad: ${Prefs.preset(this).label}"
                else -> "Descargando $n enlaces"
            }
        )
    }

    private fun addFromInput() {
        val text = b.inputLinks.text?.toString().orEmpty()
        val n = Engine.add(text, Prefs.preset(this))
        if (n == 0) {
            snack(if (Links.extract(text).isEmpty()) "No encontré enlaces. Pega uno por línea." else "Esos enlaces ya están en la lista")
        } else {
            b.inputLinks.setText("")
        }
    }

    private fun paste() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (clip.isNullOrBlank()) {
            snack("El portapapeles está vacío")
            return
        }
        val current = b.inputLinks.text?.toString().orEmpty().trimEnd()
        b.inputLinks.setText(if (current.isEmpty()) clip.trim() else "$current\n${clip.trim()}")
        b.inputLinks.setSelection(b.inputLinks.text?.length ?: 0)
    }

    private fun render(list: List<Engine.Item>) {
        adapter.submitList(list)
        val has = list.isNotEmpty()
        b.queueHeader.visibility = if (has) View.VISIBLE else View.GONE
        b.emptyHint.visibility = if (has) View.GONE else View.VISIBLE
        val active = list.count { it.active }
        b.queueTitle.text = if (active > 0) "En la lista · $active descargando" else "En la lista (${list.size})"
    }

    private fun choosePreset(anchor: View, onPick: (Preset) -> Unit) {
        val menu = PopupMenu(this, anchor)
        Preset.entries.forEachIndexed { i, p -> menu.menu.add(0, i, i, p.label) }
        menu.setOnMenuItemClickListener { mi ->
            onPick(Preset.entries[mi.itemId])
            true
        }
        menu.show()
    }

    private fun refreshAccounts() {
        b.accounts.removeAllViews()
        for (p in Platform.entries) {
            val row = ItemAccountBinding.inflate(layoutInflater, b.accounts, true)
            val connected = Cookies.isConnected(this, p)
            row.name.text = p.title
            row.status.text = if (connected) "Conectada" else p.hint
            row.action.text = if (connected) "Salir" else "Conectar"
            row.action.setOnClickListener {
                if (connected) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("¿Salir de ${p.title}?")
                        .setMessage("Se borrará la sesión guardada en la app.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Salir") { _, _ ->
                            Cookies.logout(this, p)
                            AppLog.log(this, "Sesión cerrada: ${p.title}")
                            refreshAccounts()
                        }
                        .show()
                } else {
                    login.launch(LoginActivity.intent(this, p))
                }
            }
        }
    }

    private fun refreshFolder() {
        val tree = Prefs.treeUri(this)
        val name = tree?.let { runCatching { DocumentFile.fromTreeUri(this, it)?.name }.getOrNull() }
        b.folderHint.text = if (tree != null) {
            "Se guarda en: ${name ?: "carpeta elegida"} › Video / Audio / Fotos\nMantén presionado para volver a Descargas."
        } else {
            "Se guarda en: Descargas › ${Saver.BASE} › Video / Audio / Fotos"
        }
    }

    private fun refreshVersion() {
        b.versionText.text = "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} · motor yt-dlp ${Engine.engineVersion ?: "cargando…"}"
    }

    private fun cycleTheme() {
        val next = when (Prefs.nightMode(this)) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_NO
            AppCompatDelegate.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        Prefs.setNightMode(this, next)
        val label = when (next) {
            AppCompatDelegate.MODE_NIGHT_NO -> "Tema claro"
            AppCompatDelegate.MODE_NIGHT_YES -> "Tema oscuro"
            else -> "Tema del sistema"
        }
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        AppCompatDelegate.setDefaultNightMode(next)
    }

    private fun updateEngine() {
        b.btnUpdate.isEnabled = false
        b.btnUpdate.text = "Actualizando…"
        lifecycleScope.launch {
            val msg = Engine.updateEngine()
            b.btnUpdate.isEnabled = true
            b.btnUpdate.text = "Actualizar motor"
            refreshVersion()
            snack(msg)
        }
    }

    private fun showLog() {
        val text = AppLog.read(this).ifBlank { "El registro está vacío." }
        MaterialAlertDialogBuilder(this)
            .setTitle("Registro")
            .setMessage(text.lines().takeLast(150).joinToString("\n"))
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Copiar") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("registro", text))
                snack("Registro copiado")
            }
            .setNegativeButton("Borrar") { _, _ -> AppLog.clear(this) }
            .show()
    }

    private fun askPermissions() {
        val perm = when {
            Build.VERSION.SDK_INT >= 33 -> Manifest.permission.POST_NOTIFICATIONS
            Build.VERSION.SDK_INT <= 28 -> Manifest.permission.WRITE_EXTERNAL_STORAGE
            else -> null
        } ?: return
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            askPermission.launch(perm)
        }
    }

    private fun snack(msg: String) = Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()

    companion object {
        const val RELEASES_URL = "https://github.com/swoam/Test/releases/latest"
    }
}

/** Lista de enlaces pendientes. */
class QueueAdapter(
    private val onAction: (Engine.Item) -> Unit,
    private val onPreset: (Engine.Item, View) -> Unit,
    private val onRemove: (Engine.Item) -> Unit,
) : ListAdapter<Engine.Item, QueueAdapter.VH>(DIFF) {

    class VH(val b: ItemQueueBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = getItem(position)
        val b = h.b
        b.title.text = item.title

        val (info, isError) = when (item.status) {
            Engine.Status.FETCHING -> "Leyendo información…" to false
            Engine.Status.READY -> item.info.ifEmpty { "Listo para descargar" } to false
            Engine.Status.QUEUED -> "En espera…" to false
            Engine.Status.DOWNLOADING ->
                (if (item.progress >= 0) "Descargando… ${item.progress} %" else "Descargando…") to false
            Engine.Status.ERROR -> (item.error ?: "Error") to true
        }
        b.info.text = info
        b.info.setTextColor(
            if (isError) com.google.android.material.color.MaterialColors.getColor(b.info, com.google.android.material.R.attr.colorError)
            else com.google.android.material.color.MaterialColors.getColor(b.info, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )

        val showProgress = item.status == Engine.Status.FETCHING || item.active
        val indeterminate = item.status != Engine.Status.DOWNLOADING || item.progress < 0
        val pi = b.progress
        if (!showProgress) {
            pi.visibility = View.GONE
        } else {
            if (pi.isIndeterminate != indeterminate) {
                pi.visibility = View.INVISIBLE // no se puede cambiar de modo mientras es visible
                pi.isIndeterminate = indeterminate
            }
            if (!indeterminate) pi.setProgressCompat(item.progress, true)
            pi.visibility = View.VISIBLE
        }

        b.btnPreset.text = item.preset.label
        b.btnPreset.isEnabled = !item.active
        b.btnPreset.setOnClickListener { onPreset(item, it) }

        when {
            item.active -> {
                b.btnAction.text = "Cancelar"
                b.btnAction.setIconResource(R.drawable.ic_close)
            }
            item.status == Engine.Status.ERROR -> {
                b.btnAction.text = "Reintentar"
                b.btnAction.setIconResource(R.drawable.ic_refresh)
            }
            else -> {
                b.btnAction.text = "Descargar"
                b.btnAction.setIconResource(R.drawable.ic_download)
            }
        }
        b.btnAction.setOnClickListener { onAction(item) }
        b.btnRemove.setOnClickListener { onRemove(item) }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Engine.Item>() {
            override fun areItemsTheSame(a: Engine.Item, b: Engine.Item) = a.id == b.id
            override fun areContentsTheSame(a: Engine.Item, b: Engine.Item) = a == b
        }
    }
}
