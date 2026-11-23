package ru.turuhansk.alice.lite

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout
    private lateinit var errorView: LinearLayout
    private lateinit var funnyText: TextView
    private lateinit var realErrorText: TextView

    private val logLines = ArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Смешные ошибки на русском — показываются при проблеме сети
    private val funnyErrors = listOf(
        "Сегодня на смене Котэ. Мы извиняемся, что выпустили тормоза — сейчас заменим, а вы пока обновите 🐱",
        "Ошибка: успех. Не спрашивайте.",
        "Неизвестная ошибка случилась, но всё ок.",
        "Подключаемся к серверу… шутка, не ждите.",
        "Прогресс: минус один процент.",
        "Установка прошла успешно и провалилась одновременно.",
        "Файл не найден: файл не существует. Мы посмотрели.",
        "Пинг сервера: таймаут. Действуем надеждой.",
        "DNS отказался отвечать. Он тоже удивлён.",
        "Не удалось сохранить лог ошибки об ошибке.",
        "Интернет не подключён. Просто поверьте.",
        "Это работает? 1",
        "Загрузка видео завершена. Скачалось: ничего.",
        "Часовой пояс не найден. Зима близко.",
        "Данных нет. Всё хорошо.",
        "Памяти достаточно? 1",
        "Котэ настучал на сервер лапой. Сервер не ответил.",
        "Сервер ушёл на обед. Возвращается, когда захочет.",
        "Мы посчитали. Не сошлось. Считать умеем, но не хотим.",
        "Версия новейшая, данных нет. Как всегда."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        log("app", "onCreate: запуск")

        val root = FrameLayout(this)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setupWebView(this)
        }
        root.addView(webView)

        loadingView = makeLoadingView()
        root.addView(loadingView)

        errorView = makeErrorView()
        root.addView(errorView)

        setContentView(root)

        webView.loadUrl("https://alice.yandex.ru")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        }

        wv.webViewClient = AliceWebViewClient()
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                log("js", "${message.message()} (${message.lineNumber()})")
                return true
            }
        }
    }

    private fun makeLoadingView(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val tv = TextView(this).apply {
            text = "Котэ грузит страницу… лапки в процессе 🐾"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 0)
        }
        box.addView(tv)
        return box
    }

    private fun makeErrorView(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        funnyText = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 8)
            setTypeface(typeface, Typeface.BOLD)
        }

        realErrorText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 24)
            setTextColor(Color.GRAY)
        }

        val btn = Button(this).apply {
            text = "Повторить"
            setOnClickListener { retry() }
        }

        box.addView(funnyText)
        box.addView(realErrorText)
        box.addView(btn)
        return box
    }

    private fun randomFunny(): String = funnyErrors[Random.nextInt(funnyErrors.size)]

    private fun retry() {
        showError(false)
        showLoading(true)
        webView.loadUrl("https://alice.yandex.ru")
    }

    private fun showLoading(visible: Boolean) {
        loadingView.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showError(visible: Boolean, message: String = "") {
        if (visible) {
            funnyText.text = randomFunny()
            realErrorText.text =
                if (message.isEmpty()) "(реально было: ошибка загрузки)" else "(реально было: $message)"
            log("funny", "на экране: ${funnyText.text}")
        }
        errorView.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        saveLogToDownloads()
    }

    // --- Логирование ---

    private fun log(tag: String, message: String) {
        val line = "${timeFmt.format(Date())} [$tag] $message"
        logLines.add(line)
        if (logLines.size > 500) {
            logLines.removeAt(0)
        }
        try {
            getExternalFilesDir(null)?.let { dir ->
                File(dir, "alice_log.txt").appendText("$line\n")
            }
        } catch (e: Exception) { /* тишина — лог не должен ронять приложение */ }
    }

    private fun saveLogToDownloads() {
        try {
            val content = logLines.joinToString("\n")
            if (content.isEmpty()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "alice_log.txt")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, "alice_log.txt").writeText(content)
            }
        } catch (e: Exception) { /* тишина */ }
    }

    // --- Клиент WebView ---

    private inner class AliceWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            log("net", "начали грузить: $url")
            showLoading(true)
            showError(false)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            log("net", "страница загружена: $url")
            showLoading(false)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            val desc = error?.description?.toString() ?: "неизвестная ошибка"
            log("err", "$desc (url=${request?.url})")
            if (request?.isForMainFrame == true) {
                showLoading(false)
                showError(true, desc)
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: android.webkit.WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            log("err", "HTTP ${errorResponse?.statusCode} ${request?.url}")
        }
    }
}
