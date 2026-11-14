package com.swoam.bajalo

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.swoam.bajalo.databinding.ActivityDownloadsBinding
import com.swoam.bajalo.databinding.ItemSavedBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Mis descargas": ver, reproducir, compartir o borrar lo que ya se bajó. */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDownloadsBinding
    private var records: List<Record> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars()
        b.toolbar.setNavigationOnClickListener { finish() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            records = withContext(Dispatchers.IO) { Records.load(this@DownloadsActivity) }
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
            b.empty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            b.toolbar.subtitle = if (records.isEmpty()) null else "${records.size} archivos"
        }
    }

    private fun open(r: Record) {
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(r.uri), r.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No hay una app para abrir este archivo", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "El archivo ya no está disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(r: Record) {
        val i = Intent(Intent.ACTION_SEND)
            .setType(r.mime)
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(r.uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Compartir"))
    }

    private fun delete(r: Record) {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Borrar archivo?")
            .setMessage(r.name)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Solo quitar de la lista") { _, _ ->
                Records.remove(this, r)
                reload()
            }
            .setPositiveButton("Borrar") { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { Saver.delete(this@DownloadsActivity, r) }
                    Records.remove(this@DownloadsActivity, r)
                    if (!ok) {
                        Toast.makeText(
                            this@DownloadsActivity,
                            "No se pudo borrar el archivo (se quitó de la lista)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    reload()
                }
            }
            .show()
    }

    private val adapter = object : RecyclerView.Adapter<SavedVH>() {
        override fun getItemCount() = records.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            SavedVH(ItemSavedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: SavedVH, position: Int) {
            val r = records[position]
            h.b.name.text = r.name
            h.b.meta.text = "${r.kind.label} · " + DateUtils.getRelativeTimeSpanString(
                r.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            h.b.icon.setImageResource(
                when (r.kind) {
                    Saver.Kind.VIDEO -> R.drawable.ic_movie
                    Saver.Kind.AUDIO -> R.drawable.ic_music
                    Saver.Kind.PHOTO -> R.drawable.ic_photo
                }
            )
            h.b.root.setOnClickListener { open(r) }
            h.b.btnShare.setOnClickListener { share(r) }
            h.b.btnDelete.setOnClickListener { delete(r) }
        }
    }

    class SavedVH(val b: ItemSavedBinding) : RecyclerView.ViewHolder(b.root)
}
