package ru.turuhansk.alice.lite

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
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
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class MainActivity : Activity() {

    companion object {
        // Тёмная палитра
        private val BG_DARK = Color.parseColor("#141419")
        private val TEXT_MAIN = Color.parseColor("#E8E8EC")
        private val TEXT_MUTED = Color.parseColor("#9A9AA6")
        private val BTN_BG = Color.parseColor("#2E2E3A")
        private val ACCENT = Color.parseColor("#7B61FF")

        // Зелёная подсветка активного режима
        private val GREEN_BRIGHT = Color.parseColor("#FF4CAF50")

        private const val PREFS_NAME = "alice_lite_prefs"
        private const val KEY_PILL_X = "pill_x"
        private const val KEY_PILL_Y = "pill_y"
        private const val KEY_HIDING = "hiding_enabled"
        private const val KEY_LAST_URL = "last_url"

        private const val DEFAULT_URL = "https://alice.yandex.ru"

        // Показывать страницу можно уже с этой отметки прогресса — как в браузере
        private const val SHOW_AT_PROGRESS = 85

        private const val REQUEST_FILE = 1001
        private const val REQUEST_STORAGE = 1002
        private const val REQUEST_NOTIFICATIONS = 1003

        // Автоповтор при разовых сетевых сбоях (ERR_HTTP2_PROTOCOL_ERROR)
        private const val MAX_HTTP_RETRIES = 2
        private const val RETRY_DELAY_MS = 1500L
    }

    private lateinit var prefs: SharedPreferences

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout
    private lateinit var errorView: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var topProgress: ProgressBar
    private lateinit var funnyText: TextView
    private lateinit var realErrorText: TextView
    private lateinit var toggleBtn: TextView

    private var hidingEnabled = true
    private var lastLoggedProgress = -20
    private var httpRetries = 0

    // Для выбора файлов
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileParams: WebChromeClient.FileChooserParams? = null

    private val logLines = ArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
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

        // Перехватчик падений: выгружает лог на диск даже при креше.
        // Без него буфер в памяти умирает вместе с процессом и лог теряется
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val content = logLines.joinToString("\n") + "\n[crash] " + sw
            try {
                getExternalFilesDir(null)?.let { dir ->
                    File(dir, "alice_log.txt").writeText(content)
                }
            } catch (_: Exception) { /* уже всё плохо, не усугубляем */ }
            defaultHandler?.uncaughtException(Thread.currentThread(), e)
        }

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        hidingEnabled = prefs.getBoolean(KEY_HIDING, true)

        log("app", "onCreate: запуск")
        logWebViewVersion()

        // Разрешение на уведомления: без него на Android 13+ шторка молчит,
        // хотя служба-то работает
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }

        // Шторка и нижняя панель — в цвет экрана, чтобы не выделялись
        window.statusBarColor = BG_DARK
        window.navigationBarColor = BG_DARK

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

        // Тонкая полоска поверх страницы: видна, пока загрузка не дошла до 100%
        topProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 3).toInt(),
                Gravity.TOP
            )
        }
        root.addView(topProgress)

        loadingView.visibility = android.view.View.VISIBLE

        setContentView(root)

        // Восстановление сессии: если система пересоздала активити — возвращаем
        // состояние WebView (включая историю переходов и открытый чат)
        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            log("app", "состояние WebView восстановлено")
        } else {
            val lastUrl = prefs.getString(KEY_LAST_URL, null) ?: DEFAULT_URL
            log("app", "открываем сохранённую страницу: $lastUrl")
            webView.loadUrl(lastUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Сохраняем состояние WebView: историю, позиции прокрутки — чат продолжится
        webView.saveState(outState)
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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true) // без этого onCreateWindow не срабатывает вовсе
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // User-Agent НЕ подменяем: WebView представляется сам (Chrome 157),
            // а сайт раздаёт бандлы под настоящую версию движка
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
                wv2: WebView?,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                log("app", "запрошен выбор файла, режим=${params.mode}")
                openFilePicker(params)
                return true
            }

            // Живой прогресс: проценты в тексте и полоска обновляются в реальном времени.
            // Страницу показываем рано (с SHOW_AT_PROGRESS%), как в браузере
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                loadingProgress.progress = newProgress
                topProgress.progress = newProgress
                topProgress.visibility =
                    if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
                statusText.text = "Загрузка… $newProgress%"
                if (newProgress - lastLoggedProgress >= 20 || newProgress >= 100) {
                    lastLoggedProgress = newProgress
                    log("net", "прогресс: $newProgress%")
                }
                if (newProgress >= SHOW_AT_PROGRESS) {
                    revealPage()
                    if (newProgress >= 100) view?.let { injectChatOnly(it) }
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
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                log("app", "разрешение на уведомления выдано")
            } else {
                log("app", "разрешение на уведомления не выдано — шторка будет молчать")
            }
        } else if (requestCode == REQUEST_STORAGE) {
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

    // Проба живости: загрузился ли бандл React и смонтировалось ли приложение
    private fun jsProbe(wv: WebView, reason: String) {
        val script = """
(function(){
  var page=document.querySelector('div[id^="AlicePage"]');
  return JSON.stringify({
    pageFound: !!page,
    pageChildren: page?page.childElementCount:0,
    readyState: document.readyState,
    bodyLen: document.body?document.body.innerHTML.length:0
  });
})();
""".trimIndent()

        wv.evaluateJavascript(script) { r -> log("probe", "$reason: $r") }
    }

    // --- Оформление ---

    private fun pillDrawable(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    // Обновление вида пилюли в зависимости от режима.
    // btn передаётся явно: при первом создании toggleBtn ещё не присвоен
    private fun refreshPillLook(btn: TextView) {
        btn.background = GlowPillDrawable(hidingEnabled)
        btn.setTextColor(if (hidingEnabled) GREEN_BRIGHT else TEXT_MUTED)
    }

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

        statusText = TextView(this).apply {
            text = "Загрузка… 0%"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(TEXT_MAIN)
            setPadding(48, 0, 48, 24)
        }

        loadingProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(BTN_BG)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(64, 0, 64, 36) }
        }

        val reload = Button(this).apply {
            text = "Обновить"
            setTextColor(TEXT_MAIN)
            background = pillDrawable(BTN_BG, 28f)
            setPadding(64, 28, 64, 28)
            setOnClickListener { reloadPage() }
        }

        box.addView(statusText)
        box.addView(loadingProgress)
        box.addView(reload)
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
            setTextColor(TEXT_MAIN)
            background = pillDrawable(BTN_BG, 28f)
            setPadding(64, 28, 64, 28)
            setOnClickListener { reloadPage() }
        }

        val browserBtn = Button(this).apply {
            text = "Открыть в браузере"
            setTextColor(TEXT_MUTED)
            background = pillDrawable(BTN_BG, 28f)
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(DEFAULT_URL))
                    )
                } catch (e: Exception) {
                    log("err", "браузер не найден: $e")
                }
            }
        }

        box.addView(funnyText)
        box.addView(realErrorText)
        box.addView(btn)
        box.addView(browserBtn)
        return box
    }

    // Переключатель: пилюля с перетаскиванием, позиция запоминается между запусками,
    // разлитый зелёный ореол в активном режиме, без него — в пассивном
    @SuppressLint("ClickableViewAccessibility")
    private fun makeToggleButton(): TextView {
        return TextView(this).apply {
            text = if (hidingEnabled) "только чат" else "показать всё"
            textSize = 12f
            setPadding(34, 16, 34, 16)
            refreshPillLook(this) // фон и цвет текста по текущему состоянию
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { setMargins(0, 24, 0, 0) }

            // Возвращаем сохранённую позицию
            translationX = prefs.getFloat(KEY_PILL_X, 0f)
            translationY = prefs.getFloat(KEY_PILL_Y, 0f)

            var downX = 0f
            var downY = 0f
            var moved = false

            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; moved = false; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (abs(dx) > 8 || abs(dy) > 8) {
                            moved = true
                            v.translationX += dx
                            v.translationY += dy
                            downX = e.rawX; downY = e.rawY
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            // Запоминаем позицию
                            prefs.edit()
                                .putFloat(KEY_PILL_X, v.translationX)
                                .putFloat(KEY_PILL_Y, v.translationY)
                                .apply()
                        } else {
                            hidingEnabled = !hidingEnabled
                            prefs.edit().putBoolean(KEY_HIDING, hidingEnabled).apply()
                            text = if (hidingEnabled) "только чат" else "показать всё"
                            refreshPillLook(toggleBtn)
                            log("app", "переключатель: hide=$hidingEnabled")
                            injectChatOnly(webView)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    // --- Действия ---

    private fun reloadPage() {
        showError(false)
        loadingProgress.progress = 0
        statusText.text = "Загрузка… 0%"
        showLoading(true)
        webView.reload()
    }

    private fun randomFunny(): String = funnyErrors[Random.nextInt(funnyErrors.size)]

    private fun showLoading(visible: Boolean) {
        loadingView.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun revealPage() {
        showLoading(false)
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

    override fun onResume() {
        super.onResume()
        // Вернулись в приложение — служба больше не нужна, чат продолжается на экране
        stopService(Intent(this, KeepAliveService::class.java))
    }

    override fun onPause() {
        super.onPause()
        // Пока приложение свернуто, служба держит процесс живым — чат не умирает
        startKeepAliveService()
        // Запоминаем последнюю открытую страницу
        val url = webView.url
        if (!url.isNullOrEmpty()) {
            prefs.edit().putString(KEY_LAST_URL, url).apply()
        }
        // Лог выгружается на диск один раз — не во время загрузки страницы
        saveLogToDownloads()
    }

    private fun startKeepAliveService() {
        try {
            val i = Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        } catch (e: Exception) {
            log("app", "не удалось запустить фоновую службу: $e")
        }
    }

    // --- Логирование ---

    private fun log(tag: String, message: String) {
        // Только в память: файловые записи во время загрузки тормозили рендеринг
        val line = "${timeFmt.format(Date())} [$tag] $message"
        logLines.add(line)
        if (logLines.size > 500) logLines.removeAt(0)
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
            loadingProgress.progress = 0
            topProgress.progress = 0
            topProgress.visibility = android.view.View.VISIBLE
            statusText.text = "Загрузка… 0%"
            lastLoggedProgress = -20 // чтобы прогресс новых загрузок снова писался в лог
            showLoading(true)
            showError(false)
        }

        // Первый видимый кадр — прячем оверлей сразу, не дожидаясь 100%
        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            log("net", "первый кадр: $url")
            revealPage()
            view?.let { injectChatOnly(it) }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            log("net", "страница загружена: $url, заголовок: «${view?.title ?: ""}»")
            revealPage()
            view?.let {
                injectChatOnly(it)
                dumpDom(it)
                jsProbe(it, "по завершении")
                it.postDelayed({ if (!isFinishing) jsProbe(it, "через 10с") }, 10000)
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
                // HTTP2-сбои часто разовые: тихий автоповтор вместо ошибки на экране
                val isHttp2 = desc.contains("ERR_HTTP2", ignoreCase = true)
                if (isHttp2 && httpRetries < MAX_HTTP_RETRIES) {
                    httpRetries++
                    log("net", "автоповтор загрузки ($httpRetries из $MAX_HTTP_RETRIES)")
                    view?.postDelayed({ view.reload() }, RETRY_DELAY_MS)
                } else {
                    showLoading(false)
                    showError(true, desc)
                }
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

    // --- Разлитый свет вокруг пилюли: концентрические полупрозрачные кольца
    // с затухающей яркостью — эффект мягкого ореола без капризного blur ---
    private inner class GlowPillDrawable(private val active: Boolean) : Drawable() {

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private fun dp(v: Int): Float = v * resources.displayMetrics.density

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            val pad = dp(6) // зона под свечение внутри границ view
            val left = b.left + pad
            val top = b.top + pad
            val right = b.right - pad
            val bottom = b.bottom - pad
            val radius = (bottom - top) / 2f

            if (active) {
                val rings = 5
                for (i in rings downTo 1) {
                    val grow = (pad / rings) * i
                    val alpha = 56 / i // 56, 28, 18, 14, 11 — чем дальше от пилюли, тем тусклее
                    glowPaint.color = Color.argb(
                        alpha,
                        Color.red(GREEN_BRIGHT),
                        Color.green(GREEN_BRIGHT),
                        Color.blue(GREEN_BRIGHT)
                    )
                    glowPaint.strokeWidth = pad / rings
                    canvas.drawRoundRect(
                        left - grow, top - grow, right + grow, bottom + grow,
                        radius + grow, radius + grow, glowPaint
                    )
                }
            }

            // Сама пилюля — чуть меньше прежней, чтобы освободить место под ореол
            fillPaint.color = Color.parseColor("#AA2E2E3A")
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)
        }

        override fun setAlpha(alpha: Int) { /* прозрачность управляется цветами */ }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { /* не нужно */ }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
