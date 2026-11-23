package ru.turuhansk.alice.lite

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

    companion object {
        // Тёмная палитра
        private val BG_DARK = Color.parseColor("#141419")
        private val TEXT_MAIN = Color.parseColor("#E8E8EC")
        private val TEXT_MUTED = Color.parseColor("#9A9AA6")
        private val BTN_BG = Color.parseColor("#2E2E3A")

        private const val WATCHDOG_SECONDS = 60L
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout
    private lateinit var errorView: LinearLayout
    private lateinit var funnyText: TextView
    private lateinit var realErrorText: TextView

    private val logLines = ArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private var isLoading = false
    private var domDumped = false

    // Что прячем, чтобы остался только чат. Список правится — после дампа DOM уточним.
    private val hideSelectors = listOf(
        "header", "nav", "footer",
        "[class*='Header']", "[class*='header']",
        "[class*='Nav']", "[class*='nav']",
        "[class*='Sidebar']", "[class*='sidebar']",
        "[class*='Footer']", "[class*='footer']",
        "[class*='Promo']", "[class*='promo']",
        "[class*='Banner']", "[class*='banner']",
        "[class*='Download']", "[class*='download']",
        "[class*='Install']", "[class*='install']",
        "[class*='Qr']", "[class*='qr']",
        "[class*='Cookie']", "[class*='cookie']"
    )

    // Сторожевой таймер: если за 60 секунд страница не отрисовалась — показываем ошибку
    private val watchdog = Runnable {
        if (isLoading) {
            log("err", "таймаут: страница не отрисовалась за $WATCHDOG_SECONDS секунд")
            showLoading(false)
            showError(true, "страница не ответила за $WATCHDOG_SECONDS секунд — скрипты сайта не стартовали (возможно, старый WebView)")
        }
    }

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
        logWebViewVersion()

        val root = FrameLayout(this).apply { setBackgroundColor(BG_DARK) }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(BG_DARK)
            setupWebView(this)
        }
        root.addView(webView)

        loadingView = makeLoadingView()
        root.addView(loadingView)

        errorView = makeErrorView()
        errorView.visibility = android.view.View.GONE
        root.addView(errorView)

        loadingView.visibility = android.view.View.VISIBLE

        setContentView(root)

        webView.loadUrl("https://alice.yandex.ru")
    }

    private fun logWebViewVersion() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pkg = WebView.getCurrentWebViewPackage()
                log("app", "WebView: ${pkg?.name ?: "?"} ${pkg?.versionName ?: "?"}")
            }
        } catch (e: Exception) {
            log("app", "WebView: версию узнать не удалось")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = AliceWebViewClient()
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                log("net", "прогресс: $newProgress%")
                if (newProgress >= 100) {
                    isLoading = false
                    handler.removeCallbacks(watchdog)
                    showLoading(false)
                    view?.let { injectChatOnly(it) }
                }
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                log("js", "${message.message()} (${message.lineNumber()})")
                return true
            }
        }
    }

    // --- Инъекция: прячем всё, кроме чата ---

    private fun injectChatOnly(wv: WebView) {
        val selJson = hideSelectors.joinToString(",") { "\"$it\"" }
        val script = """
(function(){
var sels=[$selJson];
function hideAll(){
  for(var i=0;i<sels.length;i++){
    try{
      var list=document.querySelectorAll(sels[i]);
      for(var j=0;j<list.length;j++){
        list[j].style.setProperty('display','none','important');
      }
    }catch(e){}
  }
}
hideAll();
if(window.__aliceHideOn){return;}
window.__aliceHideOn=true;
var t=null;
var mo=new MutationObserver(function(){
  if(t){clearTimeout(t);}
  t=setTimeout(hideAll,50);
});
mo.observe(document.documentElement,{childList:true,subtree:true});
})();
""".trimIndent()

        wv.evaluateJavascript(script) { r -> log("inject", "скрытие применено: ${r ?: "ok"}") }
    }

    // Разовый дамп верхнего уровня страницы — чтобы подобрать точные селекторы
    private fun dumpDom(wv: WebView) {
        if (domDumped) return
        domDumped = true
        val script = """
(function(){
var out=[];
var all=document.querySelectorAll('body > *');
for(var i=0;i<all.length&&i<60;i++){
  var e=all[i];
  var cls=(typeof e.className==='string'&&e.className.trim())?
    ('.'+e.className.trim().split(/\s+/).join('.')):'';
  out.push(e.tagName.toLowerCase()+(e.id?('#'+e.id):'')+cls);
}
return out.join(' | ');
})();
""".trimIndent()

        wv.evaluateJavascript(script) { r ->
            val s = (r ?: "пусто").trim()
            val short = if (s.length > 1500) s.substring(0, 1500) + "…" else s
            log("dom", "структура: $short")
        }
    }

    // --- Экраны ---

    private fun makeLoadingView(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG_DARK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val tv = TextView(this).apply {
            text = "Загрузка…\nсоединяемся с сервером"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(TEXT_MAIN)
            setPadding(48, 0, 48, 0)
        }
        box.addView(tv)
        return box
    }

    private fun makeErrorView(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG_DARK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        funnyText = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 8)
            setTextColor(TEXT_MAIN)
            setTypeface(typeface, Typeface.BOLD)
        }

        realErrorText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 24)
            setTextColor(TEXT_MUTED)
        }

        val btn = Button(this).apply {
            text = "Повторить"
            setOnClickListener { retry() }
            setTextColor(TEXT_MAIN)
            setBackgroundColor(BTN_BG)
        }

        val browserBtn = Button(this).apply {
            text = "Открыть в браузере"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://alice.yandex.ru"))
                    )
                } catch (e: Exception) {
                    log("err", "браузер не найден: $e")
                }
            }
            setTextColor(TEXT_MUTED)
            setBackgroundColor(BTN_BG)
        }

        box.addView(funnyText)
        box.addView(realErrorText)
        box.addView(btn)
        box.addView(browserBtn)
        return box
    }

    private fun randomFunny(): String = funnyErrors[Random.nextInt(funnyErrors.size)]

    private fun retry() {
        showError(false)
        showLoading(true)
        isLoading = true
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

    // --- Жизненный цикл ---

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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- Логирование ---

    private fun log(tag: String, message: String) {
        val line = "${timeFmt.format(Date())} [$tag] $message"
        logLines.add(line)
        if (logLines.size > 500) logLines.removeAt(0)
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
                val existingId = contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf("alice_log.txt"),
                    null
                )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

                val uri = if (existingId != null) {
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existingId)
                } else {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "alice_log.txt")
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    }
                    contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                }
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
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
            isLoading = true
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, WATCHDOG_SECONDS * 1000)
            showLoading(true)
            showError(false)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            log("net", "страница загружена: $url, заголовок: «${view?.title ?: ""}»")
            isLoading = false
            handler.removeCallbacks(watchdog)
            showLoading(false)
            view?.let {
                injectChatOnly(it)
                dumpDom(it)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            log("net", "редирект/переход: ${request?.url}")
            return false
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
            errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            log("err", "HTTP ${errorResponse?.statusCode} ${request?.url}")
        }
    }
}
