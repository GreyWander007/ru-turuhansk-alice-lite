package ru.turuhansk.alice.lite

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.ValueCallback
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

        private const val REQUEST_FILE = 1001
        private const val REQUEST_STORAGE = 1002
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout
    private lateinit var errorView: LinearLayout
    private lateinit var funnyText: TextView
    private lateinit var realErrorText: TextView
    private lateinit var toggleBtn: TextView

    private var hidingEnabled = true

    // Для выбора файлов
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileParams: WebChromeClient.FileChooserParams? = null

    private val logLines = ArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())
    private var isLoading = false
    private var domDumped = false

    // Скрываем только мусор: промо, баннеры, QR, призывы скачать приложение, плашки куки.
    // Шапку, навигацию и сайдбар НЕ трогаем — там кнопка входа и история чатов.
    private val hideSelectors = listOf(
        "[class*='Promo']", "[class*='promo']",
        "[class*='Banner']", "[class*='banner']",
        "[class*='Qr']", "[class*='qr']",
        "[class*='Download']", "[class*='download']",
        "[class*='Install']", "[class*='install']",
        "[class*='Cookie']", "[class*='cookie']",
        "[class*='Marketing']", "[class*='marketing']",
        "[class*='Adv']", "[class*='adv']"
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

        toggleBtn = makeToggleButton()
        root.addView(toggleBtn)

        loadingView.visibility = android.view.View.VISIBLE

        setContentView(root)

        webView.loadUrl("https://alice.yandex.ru")
    }

    private fun logWebViewVersion() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pkg = WebView.getCurrentWebViewPackage()
                log("app", "WebView: ${pkg?.packageName ?: "?"} ${pkg?.versionName ?: "?"}")
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
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.webViewClient = AliceWebViewClient()
        wv.webChromeClient = object : WebChromeClient() {

            // Всплывающие окна (шаги авторизации, формы) грузим в тот же WebView
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (view == null || resultMsg == null) return false
                log("js", "создание окна: isDialog=$isDialog")
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }

            // Диалог выбора файлов: без него крестик «прикрепить файл» мёртв
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                log("app", "запрошен выбор файла, режим=${params.mode}")
                openFilePicker(params)
                return true
            }

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

    // --- Выбор файлов ---

    private fun storagePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasStoragePermission(): Boolean {
        // На Android 10+ системный выбор файлов не требует разрешения — доступ выдаётся
        // самим пикером только к выбранному файлу
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun openFilePicker(params: WebChromeClient.FileChooserParams) {
        if (hasStoragePermission()) {
            launchPicker(params)
        } else {
            pendingFileParams = params
            log("app", "запрашиваем разрешение на хранилище")
            requestPermissions(storagePermissions(), REQUEST_STORAGE)
        }
    }

    private fun launchPicker(params: WebChromeClient.FileChooserParams) {
        try {
            startActivityForResult(params.createIntent(), REQUEST_FILE)
        } catch (e: Exception) {
            log("err", "не удалось открыть выбор файла: $e")
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILE) {
            val callback = filePathCallback
            filePathCallback = null
            val result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            if (result != null && result.isNotEmpty()) {
                log("app", "файл выбран: ${result.joinToString { it.lastPathSegment ?: "?" }}")
            } else {
                log("app", "выбор файла отменён")
            }
            callback?.onReceiveValue(result ?: arrayOf())
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                pendingFileParams?.let { launchPicker(it) }
            } else {
                log("err", "разрешение на хранилище не выдано")
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
            pendingFileParams = null
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    // --- Инъекция: стиль прячется/показывается классом на body, поэтому переключаемо ---

    private fun injectChatOnly(wv: WebView) {
        val selJson = hideSelectors.joinToString(",") { "\"$it\"" }
        val enabled = if (hidingEnabled) "true" else "false"
        val script = """
(function(){
window.__hideUi = $enabled;
if(!window.__aliceCssOn){
  window.__aliceCssOn = true;
  var st=document.createElement('style');
  st.id='__aliceCss';
  st.textContent='body.__aliceHideUi ' + [[$selJson].join(',')].join(',') + ' {display:none!important}';
  (document.head||document.documentElement).appendChild(st);
  var mo=new MutationObserver(function(){
    if(!document.getElementById('__aliceCss')){
      var s2=document.createElement('style');
      s2.id='__aliceCss';
      s2.textContent='body.__aliceHideUi ' + [[$selJson].join(',')].join(',') + ' {display:none!important}';
      (document.head||document.documentElement).appendChild(s2);
    }
    if(document.body && window.__hideUi===true && !document.body.classList.contains('__aliceHideUi')){
      document.body.classList.add('__aliceHideUi');
    }
  });
  mo.observe(document.documentElement,{childList:true,subtree:true});
}
if(document.body){
  if(window.__hideUi===true){ document.body.classList.add('__aliceHideUi'); }
  else { document.body.classList.remove('__aliceHideUi'); }
}
})();
""".trimIndent()

        wv.evaluateJavascript(script) { _ -> log("inject", "инъекция применена (hide=$enabled)") }
    }

    // Разовый дамп верхнего уровня страницы — для подбора точных селекторов
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

    private fun makeToggleButton(): TextView {
        return TextView(this).apply {
            text = "режим: только чат"
            textSize = 12f
            setTextColor(TEXT_MUTED)
            setBackgroundColor(Color.parseColor("#802E2E3A"))
            setPadding(24, 12, 24, 12)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, 16, 16, 0) }
            setOnClickListener {
                hidingEnabled = !hidingEnabled
                text = if (hidingEnabled) "режим: только чат" else "режим: показать всё"
                log("app", "переключатель: hide=$hidingEnabled")
                injectChatOnly(webView)
            }
        }
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
