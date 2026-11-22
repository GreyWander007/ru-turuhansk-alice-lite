package ru.turuhansk.alice.lite

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btnRefresh: Button
    private lateinit var progressBar: View
    private lateinit var errorText: TextView
    private var webviewReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)

            webView = findViewById(R.id.webView)
            btnRefresh = findViewById(R.id.btnRefresh)
            progressBar = findViewById(R.id.progressBar)
            errorText = findViewById(R.id.errorText)

            btnRefresh.setOnClickListener {
                errorText.visibility = View.GONE
                webView.reload()
            }

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webviewReady && webView.canGoBack()) webView.goBack() else finish()
                }
            })

            setupWebView()

            if (savedInstanceState != null) {
                webView.restoreState(savedInstanceState)
                webviewReady = true
            } else {
                webView.loadUrl("https://alice.yandex.ru")
            }
        } catch (e: Throwable) {
            showCrash(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true

        s.loadsImagesAutomatically = false
        s.blockNetworkImage = true

        s.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

        s.userAgentString =
            "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = AliceWebViewClient(this) { showError() }
    }

    fun showLoading(show: Boolean) {
        runOnUiThread {
            try {
                progressBar.visibility = if (show) View.VISIBLE else View.GONE
                if (show) btnRefresh.visibility = View.GONE
            } catch (_: Throwable) { }
        }
    }

    fun hideError() {
        runOnUiThread {
            try { btnRefresh.visibility = View.GONE } catch (_: Throwable) { }
        }
    }

    private fun showError() {
        val jokes = listOf(
            "Интернет сегодня решил помедитировать. Страница пока в режиме тишины. Нажмите «Обновить», чтобы вернуть её в чат.",
            "Похоже, страница едет к вам на велосипеде, а не на спорткаре. Обновите — пусть ускорится!",
            "Сервер немного задумался. Такое бывает, когда слишком много умных вопросов. Обновите страницу — продолжим думать вместе.",
            "Страница хотела прийти вовремя, но застряла в пробке из пакетов. Давайте попробуем ещё раз?",
            "Сеть сейчас такая тихая, что даже эхо не слышно. Обновите: если повезёт, покажем версию из кэша — она уже здесь и ждёт.",
            "Кажется, Алиса сегодня прячется за плохим соединением. Нажимаем «Обновить» — выманиваем обратно!",
            "Это не ошибка, это режим «минималистичного ожидания». Обновите, чтобы увидеть всё остальное.",
            "Мы хотели показать страницу, но интернет сказал «не сейчас». Давайте попробуем ещё разок?",
            "Страница немного стесняется показываться при такой связи. Обновите — она выйдет из-за угла!",
            "Иногда страницы любят, чтобы их подождали. Нажмите «Обновить» — проявим немного терпения.",
            "Всё под контролем: просто сеть решила устроить тихий час. Обновим, когда будет готова.",
            "Связь сегодня не в настроении. Обновите — посмотрим, стало ли лучше."
            "Сегодня Котэ на смене, изнивите, сейчас выпнем и оправим нового мастера, попробуйте обновить."
        )
        runOnUiThread {
            try {
                progressBar.visibility = View.GONE
                btnRefresh.visibility = View.VISIBLE
                Toast.makeText(this, jokes.random(), Toast.LENGTH_LONG).show()
            } catch (_: Throwable) { }
        }
    }

    private fun showCrash(e: Throwable) {
        val text = buildString {
            append("Ошибка запуска:\n")
            append(e.javaClass.simpleName).append(": ").append(e.message ?: "(нет сообщения)")
            e.stackTrace.take(6).forEach { append("\n").append(it.toString()) }
            e.cause?.let {
                append("\n\nПричина: ").append(it.javaClass.simpleName)
                append(": ").append(it.message ?: "(нет сообщения)")
            }
        }
        try {
            errorText.text = text
            errorText.visibility = View.VISIBLE
            progressBar.visibility = View.GONE
            btnRefresh.visibility = View.GONE
            webView.visibility = View.GONE
        } catch (_: Throwable) {
            try { Toast.makeText(this, text.take(400), Toast.LENGTH_LONG).show() } catch (_: Throwable) { }
        }
    }
}
