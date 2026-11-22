package com.jin.bitperfect

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal WebView login screen used to obtain YouTube/Google cookies.
 *
 * Google sometimes refuses embedded-WebView sign-in ("This browser or app may
 * not be secure"). The screen therefore also offers manual cookie paste and
 * always exposes a "save cookies" button. Nothing is saved until we actually
 * see an authenticated cookie or the user presses save.
 */
class YouTubeLoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private val cookieJar = LinkedHashMap<String, String>()
    private var autoSaved = false

    private val loginUrl =
        "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_bg))
        }
        statusView = TextView(this).apply {
            text = getString(R.string.youtube_login_hint)
            setTextColor(getColor(R.color.accent))
            textSize = 12f
            setPadding(28, 20, 28, 12)
        }
        root.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // A desktop Chrome UA gives the embedded login flow a better chance
            // of being accepted; Google still blocks some WebViews, in which
            // case the manual cookie paste path remains available.
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val host = request?.url?.host.orEmpty()
                if (host.endsWith("youtube.com") || host.endsWith("google.com") || host.endsWith("googlevideo.com")) {
                    val header = request?.requestHeaders?.entries
                        ?.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
                        ?.value
                    captureCookies(header)
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                captureCookies(CookieManager.getInstance().getCookie(url ?: ""))
                captureCookies(CookieManager.getInstance().getCookie("https://www.youtube.com"))
                captureCookies(CookieManager.getInstance().getCookie("https://accounts.google.com"))
                if (hasAuthCookie()) {
                    if (!autoSaved) {
                        autoSaved = true
                        saveCookies(auto = true)
                    }
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 14, 20, 28)
        }
        val save = Button(this).apply {
            text = getString(R.string.youtube_login_save)
            setOnClickListener { saveCookies(auto = false) }
        }
        val manual = Button(this).apply {
            text = getString(R.string.youtube_login_manual)
            setOnClickListener { showManualCookieDialog() }
        }
        val close = Button(this).apply {
            text = getString(R.string.youtube_login_close)
            setOnClickListener { finish() }
        }
        bar.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(manual, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        webView.loadUrl(loginUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun captureCookies(raw: String?) {
        if (raw.isNullOrBlank()) return
        var changed = false
        synchronized(cookieJar) {
            for (pair in raw.split(';')) {
                val index = pair.indexOf('=')
                if (index <= 0) continue
                val name = pair.substring(0, index).trim()
                val value = pair.substring(index + 1).trim()
                if (name.isEmpty() || value.isEmpty()) continue
                if (cookieJar[name] != value) {
                    cookieJar[name] = value
                    changed = true
                }
            }
        }
        if (changed) runOnUiThread { updateStatus() }
    }

    private fun hasAuthCookie(): Boolean = synchronized(cookieJar) {
        listOf("SID", "SAPISID", "__Secure-3PSID", "__Secure-1PSID", "HSID", "SSID")
            .any { cookieJar.containsKey(it) }
    }

    private fun updateStatus() {
        val count = synchronized(cookieJar) { cookieJar.size }
        val authenticated = hasAuthCookie()
        statusView.text = if (authenticated) {
            getString(R.string.youtube_login_status_logged_in) + " ($count)"
        } else {
            getString(R.string.youtube_login_hint) + " ($count)"
        }
    }

    private fun saveCookies(auto: Boolean) {
        val value = synchronized(cookieJar) {
            cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        if (value.isBlank()) {
            Toast.makeText(this, getString(R.string.youtube_cookie_empty), Toast.LENGTH_LONG).show()
            return
        }
        try {
            YtStreamHelper.setCookies(this, value)
            val message = getString(R.string.youtube_cookie_saved) + " (" + cookieJar.size + ")"
            statusView.text = message
            if (!auto) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (t: Throwable) {
            Toast.makeText(this, t.message ?: getString(R.string.youtube_cookie_empty), Toast.LENGTH_LONG).show()
        }
    }

    private fun showManualCookieDialog() {
        val input = EditText(this).apply {
            hint = "SID=...; HSID=...; SSID=...; APISID=...; SAPISID=..."
            setText(YtStreamHelper.cookies)
            setSingleLine(false)
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.youtube_login_manual))
            .setView(input)
            .setPositiveButton(getString(R.string.youtube_login_save)) { _: DialogInterface, _: Int ->
                val value = input.text?.toString()?.trim().orEmpty()
                if (value.isEmpty()) {
                    Toast.makeText(this, getString(R.string.youtube_cookie_empty), Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        YtStreamHelper.setCookies(this, value)
                        Toast.makeText(this, getString(R.string.youtube_cookie_saved), Toast.LENGTH_SHORT).show()
                        finish()
                    } catch (t: Throwable) {
                        Toast.makeText(this, t.message ?: getString(R.string.youtube_cookie_empty), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (_: Throwable) {}
        super.onDestroy()
    }
}
