package com.swoam.bajalo

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.swoam.bajalo.databinding.ActivityLoginBinding

/**
 * Abre la página oficial de inicio de sesión en un navegador interno.
 * Cuando el usuario termina, se guardan las cookies de esa sesión para yt-dlp.
 * La contraseña la escribe el usuario directamente en la web de la plataforma: la app nunca la ve.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var b: ActivityLoginBinding
    private lateinit var platform: Platform

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars()

        platform = runCatching { Platform.valueOf(intent.getStringExtra(EXTRA)!!) }.getOrElse {
            finish()
            return
        }

        b.btnSave.text = "Ya entré a ${platform.title} → Guardar"
        b.btnClose.setOnClickListener { finish() }
        b.btnSave.setOnClickListener { save() }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(b.web, true)

        b.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Sin la marca "wv" algunas webs (Google) no bloquean el inicio de sesión
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("""Version/\S+\s"""), "")
        }
        b.web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                b.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                b.progress.visibility = View.GONE
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.web.canGoBack()) b.web.goBack() else finish()
            }
        })

        if (savedInstanceState == null) b.web.loadUrl(platform.loginUrl)
        else b.web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.web.saveState(outState)
    }

    private fun save() {
        CookieManager.getInstance().flush()
        if (Cookies.saveFromWebView(this, platform)) {
            AppLog.log(this, "Sesión guardada: ${platform.title}")
            Toast.makeText(this, "${platform.title} conectada", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } else {
            Toast.makeText(
                this,
                "Todavía no detecto la sesión. Inicia sesión y vuelve a tocar Guardar.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        b.web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA = "platform"
        fun intent(c: Context, p: Platform) = Intent(c, LoginActivity::class.java).putExtra(EXTRA, p.name)
    }
}
