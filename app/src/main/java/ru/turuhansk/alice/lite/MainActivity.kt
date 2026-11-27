package ru.turuhansk.alice.lite

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.View
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
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class MainActivity : Activity() {

    companion object {
        // Тёмная палитра интерфейса приложения
        private val BG_DARK = Color.parseColor("#141419")
        private val CARD_BG = Color.parseColor("#1E1E26")
        private val BAR_BG = Color.parseColor("#191921")
        private val TEXT_MAIN = Color.parseColor("#E8E8EC")
        private val TEXT_MUTED = Color.parseColor("#9A9AA6")
        private val BTN_BG = Color.parseColor("#2E2E3A")
        private val ACCENT = Color.parseColor("#7B61FF")

        // Зелёная подсветка включённых переключателей
        private val GREEN_BRIGHT = Color.parseColor("#FF4CAF50")
        private val DOT_OFF = Color.parseColor("#4A4A55")

        private const val PREFS_NAME = "alice_lite_prefs"
        private const val KEY_FORCE_DARK = "force_dark"
        private const val KEY_LOGGING = "logging_enabled"
        private const val KEY_LOCK_ORIENTATION = "lock_orientation"
        // Новый ключ: фон выключен по умолчанию, старое значение не наследуется
        private const val KEY_BACKGROUND = "background_enabled_v2"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_LOG_DIR = "log_dir_uri"
        private const val KEY_LOG_DIR_LABEL = "log_dir_label"
        private const val KEY_LOG_FILE = "log_file_uri"

        private const val DEFAULT_URL = "https://alice.yandex.ru"

        // Показывать страницу можно уже с этой отметки прогресса — как в браузере
        private const val SHOW_AT_PROGRESS = 85

        private const val REQUEST_FILE = 1001
        private const val REQUEST_STORAGE = 1002
        private const val REQUEST_NOTIFICATIONS = 1003
        private const val REQUEST_LOG_DIR = 1004

        // Автоповтор при разовых сетевых сбоях
        private const val MAX_HTTP_RETRIES = 2
        private const val RETRY_DELAY_MS = 1500L

        // Жест «назад»: краевые зоны и пороги срабатывания
        private const val EDGE_WIDTH_DP = 24
        private const val SWIPE_THRESHOLD_DP = 90
        private const val SWIPE_MAX_VERTICAL_DP = 60
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

    // Нижний бар: ячейки переключателей и пузырь настроек лога
    private lateinit var bottomBar: LinearLayout
    private lateinit var cellTheme: LinearLayout
    private lateinit var cellOrient: LinearLayout
    private lateinit var cellBackground: LinearLayout
    private lateinit var cellLog: LinearLayout
    private lateinit var gearIcon: TextView
    private lateinit var dirBubble: LinearLayout
    private lateinit var dirBubbleSubtitle: TextView

    private var forceDarkOn = true
    private var loggingEnabled = true
    private var lockOrientationOn = false
    private var backgroundEnabled = false
    private var lastLoggedProgress = -20
    private var httpRetries = 0
    private var errorSincePageStart = false
    private var logDirUri: Uri? = null
    private var logDirLabel = "Загрузки"

    // Пересоздание активити ради смены темы: гасит лишний запуск фоновой службы
    private var recreatingForTheme = false

    // Для выбора файлов
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileParams: WebChromeClient.FileChooserParams? = null

    private val logLines = ArrayList<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var domDumped = false

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

    private fun barHeightPx(): Int = dp(56).toInt()

    // Ночной режим конфигурации следует за тумблером темы: сайт Алисы определяет
    // тему по prefers-color-scheme, поэтому «выкл» должен быть днём, иначе
    // сайт сам отдаст тёмную тему. Применяется строго до super.onCreate,
    // поэтому переключение выполняется через recreate()
    private fun applyNightConfiguration() {
        val dark = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_FORCE_DARK, true)
        try {
            val config = Configuration(resources.configuration)
            config.uiMode =
                (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            applyOverrideConfiguration(config)
            log("app", "ночной конфиг: ${if (dark) "тёмный" else "светлый"}")
        } catch (e: Exception) { /* тихо: тогда сработает только reflection-путь */ }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyNightConfiguration()
        super.onCreate(savedInstanceState)

        // Перехватчик падений: лог выгружается в тот же файл через единый канал,
        // чтобы не плодить копии в разных папках
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            logLines.add("[crash] " + sw.toString())
            if (loggingEnabled) saveLogToDownloads()
            defaultHandler?.uncaughtException(Thread.currentThread(), e)
        }

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        forceDarkOn = prefs.getBoolean(KEY_FORCE_DARK, true)
        loggingEnabled = prefs.getBoolean(KEY_LOGGING, true)
        lockOrientationOn = prefs.getBoolean(KEY_LOCK_ORIENTATION, false)
        backgroundEnabled = prefs.getBoolean(KEY_BACKGROUND, false)
        logDirUri = prefs.getString(KEY_LOG_DIR, null)?.let { Uri.parse(it) }
        logDirLabel = prefs.getString(KEY_LOG_DIR_LABEL, null) ?: "Загрузки"

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

        // Блокировка ориентации применяется сразу при старте
        applyOrientationLock()

        // Шторка — в цвет экрана
        window.statusBarColor = BG_DARK
        window.navigationBarColor = BG_DARK

        val barH = barHeightPx()

        val root = FrameLayout(this).apply { setBackgroundColor(BG_DARK) }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = barH } // приподняли чат над нижним баром
            setBackgroundColor(BG_DARK)
            setupWebView(this)
        }
        root.addView(webView)

        // Краевые зоны жеста «назад»: свайп от левого края вправо
        // или от правого края влево возвращает на предыдущую страницу
        root.addView(makeEdgeOverlay(isLeft = true, bottomMargin = barH))
        root.addView(makeEdgeOverlay(isLeft = false, bottomMargin = barH))

        loadingView = makeLoadingView(barH)
        root.addView(loadingView)

        errorView = makeErrorView(barH)
        errorView.visibility = android.view.View.GONE
        root.addView(errorView)

        // Пузырь настроек лога: над баром, прижат к шестерёнке, скрыт до вызова
        dirBubble = makeDirBubble(barH)
        dirBubble.visibility = android.view.View.GONE
        root.addView(dirBubble)

        // Тонкая полоска поверх страницы: видна, пока загрузка не завершилась
        // окончательно (прячется только в onPageFinished, не по процентам)
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

        bottomBar = makeBottomBar()
        root.addView(bottomBar) // бар последним — поверх всего

        loadingView.visibility = android.view.View.VISIBLE

        setContentView(root)

        // Тема применяется сразу при старте — из сохранённых настроек
        applyForceDark()

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

    @SuppressLint("SetJavaScriptEnabled")
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
            // User-Agent НЕ подменяем: WebView представляется сам,
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
            // Полоску сверху прячет только onPageFinished — WebView присылает 100%
            // и при провале загрузки, поэтому процентам верить нельзя
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                loadingProgress.progress = newProgress
                topProgress.progress = newProgress
                statusText.text = "Загрузка… $newProgress%"
                if (newProgress - lastLoggedProgress >= 20 || newProgress >= 100) {
                    lastLoggedProgress = newProgress
                    log("net", "прогресс: $newProgress%")
                }
                if (newProgress >= SHOW_AT_PROGRESS) {
                    revealPage()
                }
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                log("js", "${message.message()} (${message.lineNumber()})")
                return true
            }
        }
    }

    // --- Тёмная тема ---
    // Основной путь на Android 13+: isAlgorithmicDarkeningAllowed. Ночной режим
    // конфигурации включается вместе с тумблером (applyNightConfiguration до
    // onCreate), поэтому тумблер работает в обе стороны. Резервный путь для
    // старых версий: setForceDark через reflection (1 = выкл, 2 = вкл)
    private fun applyForceDark() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webView.settings.isAlgorithmicDarkeningAllowed = forceDarkOn
            log("app", "тёмная тема: ${if (forceDarkOn) "вкл" else "выкл"}")
        } else {
            try {
                val m = webView.settings.javaClass.getMethod(
                    "setForceDark", Int::class.javaPrimitiveType
                )
                m.invoke(webView.settings, if (forceDarkOn) 2 else 1)
                log("app", "форс-дарк: ${if (forceDarkOn) "вкл" else "выкл"}")
            } catch (e: Exception) {
                log("app", "затемнение недоступно на этой версии: $e")
            }
        }
    }

    // --- Блокировка ориентации ---

    private fun applyOrientationLock() {
        requestedOrientation = if (lockOrientationOn) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        log("app", "ориентация: ${if (lockOrientationOn) "фиксирована" else "свободна"}")
    }

    // --- Жест «назад»: краевые зоны ---

    // Прозрачная зона вдоль края экрана. Квалифицированный горизонтальный свайп
    // от края запускает «назад»; случайный тап в узкой зоне передаётся в WebView,
    // чтобы нажатия у края не терялись
    @SuppressLint("ClickableViewAccessibility")
    private fun makeEdgeOverlay(isLeft: Boolean, bottomMargin: Int): View {
        val zone = View(this)
        zone.layoutParams = FrameLayout.LayoutParams(
            dp(EDGE_WIDTH_DP).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (isLeft) Gravity.START else Gravity.END
        ).apply { this.bottomMargin = bottomMargin }

        var downX = 0f
        var downY = 0f
        var tracking = false

        zone.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; tracking = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (tracking) {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        val horiz = abs(dx)
                        val vert = abs(dy)
                        val directionOk = if (isLeft) dx > 0 else dx < 0
                        if (horiz >= dp(SWIPE_THRESHOLD_DP) &&
                            vert <= dp(SWIPE_MAX_VERTICAL_DP) &&
                            directionOk
                        ) {
                            performBack()
                        } else if (horiz < dp(10) && vert < dp(10)) {
                            // Тап без движения: передаём в WebView как нажатие
                            webView.dispatchTouchEvent(
                                MotionEvent.obtain(
                                    e.downTime, e.eventTime,
                                    MotionEvent.ACTION_DOWN, e.x, e.y, 0
                                )
                            )
                            webView.dispatchTouchEvent(
                                MotionEvent.obtain(
                                    e.downTime, e.eventTime,
                                    MotionEvent.ACTION_UP, e.x, e.y, 0
                                )
                            )
                        }
                    }
                    tracking = false
                    true
                }
                else -> true
            }
        }
        return zone
    }

    // Единая точка «назад»: сначала закрываем пузырь, потом идём по истории
    private fun performBack() {
        if (this::dirBubble.isInitialized && dirBubble.visibility == View.VISIBLE) {
            hideDirBubble()
            return
        }
        if (this::webView.isInitialized && webView.canGoBack()) {
            log("app", "жест: назад")
            webView.goBack()
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
        } else if (requestCode == REQUEST_LOG_DIR) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    logDirUri = uri
                    logDirLabel = dirDisplayName(uri) ?: "выбранная папка"
                    prefs.edit()
                        .putString(KEY_LOG_DIR, uri.toString())
                        .putString(KEY_LOG_DIR_LABEL, logDirLabel)
                        // старый файл в предыдущей папке больше не актуален
                        .putString(KEY_LOG_FILE, null)
                        .apply()
                    dirBubbleSubtitle.text = "Сейчас: $logDirLabel"
                    log("app", "папка лога: $logDirLabel")
                } catch (e: Exception) {
                    log("err", "не удалось закрепить доступ к папке: $e")
                }
            } else {
                log("app", "выбор папки лога отменён")
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // Имя выбранной папки для подписи в пузыре
    private fun dirDisplayName(uri: Uri): String? {
        return try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                uri, DocumentsContract.getTreeDocumentId(uri)
            )
            contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
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

    private fun dp(v: Int): Float = v * resources.displayMetrics.density

    private fun makeLoadingView(bottomMargin: Int): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG_DARK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { this.bottomMargin = bottomMargin }
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

    private fun makeErrorView(bottomMargin: Int): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BG_DARK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { this.bottomMargin = bottomMargin }
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

    // --- Нижний бар: иконки-переключатели с точками состояния, шестерёнка справа ---

    // Ячейка переключателя: эмодзи сверху, точка-индикатор снизу.
    // Эмодзи не перекрашиваются средствами системы, поэтому состояние показывают
    // точка (● зелёная / ○ серая) и зелёный ореол вокруг ячейки
    private fun makeBarToggle(emoji: String): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                dp(52).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { setMargins(dp(2).toInt(), 0, dp(2).toInt(), 0) }
        }

        val emojiView = TextView(this).apply {
            text = emoji
            textSize = 20f
            gravity = Gravity.CENTER
        }

        val dot = TextView(this).apply {
            textSize = 9f
            gravity = Gravity.CENTER
            text = "○"
        }

        // Вьюхи обязаны быть прикреплены к ячейке — без addView они невидимы
        cell.addView(emojiView)
        cell.addView(dot)
        cell.tag = emojiView to dot
        return cell
    }

    // Обновление вида ячейки: точка и ореол отражают состояние
    private fun refreshToggle(cell: LinearLayout, on: Boolean) {
        val pair = cell.tag as Pair<TextView, TextView>
        val emojiView = pair.first
        val dot = pair.second

        emojiView.background = if (on) GlowPillDrawable(true) else null
        emojiView.setPadding(dp(8).toInt(), dp(4).toInt(), dp(8).toInt(), dp(2).toInt())

        dot.text = if (on) "●" else "○"
        dot.setTextColor(if (on) GREEN_BRIGHT else DOT_OFF)
    }

    private fun announce(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun makeBottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR_BG)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeightPx(),
                Gravity.BOTTOM
            )
        }

        // Левая группа: четыре переключателя по центру оставшегося места
        val leftGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
        }

        val themeCell = makeBarToggle("🌙")
        themeCell.setOnClickListener {
            forceDarkOn = !forceDarkOn
            prefs.edit().putBoolean(KEY_FORCE_DARK, forceDarkOn).apply()
            announce(if (forceDarkOn) "Тёмная тема: вкл" else "Тёмная тема: выкл")
            // Ночной конфиг применяется только до super.onCreate,
            // поэтому пересоздаём активити: состояние чата восстановится само
            recreatingForTheme = true
            recreate()
        }

        val orientCell = makeBarToggle("🔒")
        orientCell.setOnClickListener {
            lockOrientationOn = !lockOrientationOn
            prefs.edit().putBoolean(KEY_LOCK_ORIENTATION, lockOrientationOn).apply()
            applyOrientationLock()
            refreshBarIcons()
            announce(if (lockOrientationOn) "Экран зафиксирован" else "Экран свободен")
        }

        val bgCell = makeBarToggle("🔄")
        bgCell.setOnClickListener {
            backgroundEnabled = !backgroundEnabled
            prefs.edit().putBoolean(KEY_BACKGROUND, backgroundEnabled).apply()
            if (!backgroundEnabled) {
                // Служба могла остаться от прошлого сворачивания — глушим сразу
                stopService(Intent(this, KeepAliveService::class.java))
            }
            refreshBarIcons()
            announce(if (backgroundEnabled) "Фон: вкл" else "Фон: выкл")
            log("app", "работа в фоне: ${if (backgroundEnabled) "вкл" else "выкл"}")
        }

        val logCell = makeBarToggle("📝")
        logCell.setOnClickListener {
            loggingEnabled = !loggingEnabled
            prefs.edit().putBoolean(KEY_LOGGING, loggingEnabled).apply()
            if (!loggingEnabled) hideDirBubble()
            refreshBarIcons()
            announce(if (loggingEnabled) "Лог: вкл" else "Лог: выкл")
            log("app", "запись лога: ${if (loggingEnabled) "вкл" else "выкл"}")
        }

        // Сохраняем ссылки на ячейки для обновления вида при старте и возврате
        cellTheme = themeCell
        cellOrient = orientCell
        cellBackground = bgCell
        cellLog = logCell

        leftGroup.addView(themeCell)
        leftGroup.addView(orientCell)
        leftGroup.addView(bgCell)
        leftGroup.addView(logCell)
        bar.addView(leftGroup)

        gearIcon = TextView(this).apply {
            text = "⚙️"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(18).toInt(), 0, dp(18).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener {
                if (!loggingEnabled) {
                    announce("Шестерёнка: включите запись лога (📝)")
                    log("app", "шестерёнка неактивна: запись лога выключена")
                    return@setOnClickListener
                }
                if (dirBubble.visibility == View.VISIBLE) {
                    hideDirBubble()
                } else {
                    showDirBubble()
                }
            }
        }
        bar.addView(gearIcon)

        refreshBarIcons()
        return bar
    }

    // Обновление всех индикаторов: вызывается при старте и при возврате в активити
    private fun refreshBarIcons() {
        if (!this::cellTheme.isInitialized) return
        refreshToggle(cellTheme, forceDarkOn)
        refreshToggle(cellOrient, lockOrientationOn)
        refreshToggle(cellBackground, backgroundEnabled)
        refreshToggle(cellLog, loggingEnabled)

        gearIcon.alpha = if (loggingEnabled) 1f else 0.45f
    }

    // --- Пузырь настроек лога: над баром, прижат к шестерёнке ---

    private fun makeDirBubble(barH: Int): LinearLayout {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD_BG)
                cornerRadius = dp(18)
            }
            setPadding(dp(18).toInt(), dp(14).toInt(), dp(18).toInt(), dp(14).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                bottomMargin = barH + dp(10).toInt()
                marginEnd = dp(12).toInt()
            }
        }

        val title = TextView(this).apply {
            text = "Место сохранения лога"
            textSize = 15f
            setTextColor(TEXT_MAIN)
            setTypeface(typeface, Typeface.BOLD)
        }
        bubble.addView(title)

        dirBubbleSubtitle = TextView(this).apply {
            text = "Сейчас: $logDirLabel"
            textSize = 12f
            setTextColor(TEXT_MUTED)
            setPadding(0, dp(4).toInt(), 0, 0)
        }
        bubble.addView(dirBubbleSubtitle)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12).toInt() }
        }

        val pick = TextView(this).apply {
            text = "Выбрать"
            textSize = 13f
            setTextColor(TEXT_MAIN)
            setPadding(30, 14, 30, 14)
            background = pillDrawable(BTN_BG, 22f)
            setOnClickListener { openLogDirPicker() }
        }
        actions.addView(pick)

        val close = TextView(this).apply {
            text = "✕"
            textSize = 15f
            setTextColor(TEXT_MUTED)
            setPadding(dp(16).toInt(), 14, dp(16).toInt(), 14)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8).toInt() }
            setOnClickListener { hideDirBubble() }
        }
        actions.addView(close)

        bubble.addView(actions)
        return bubble
    }

    private fun showDirBubble() {
        dirBubble.visibility = View.VISIBLE
        log("app", "пузырь лога открыт")
    }

    private fun hideDirBubble() {
        dirBubble.visibility = View.GONE
    }

    // Выбор папки для лога: системный SAF-пикер, доступ закрепляется навсегда
    private fun openLogDirPicker() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            startActivityForResult(i, REQUEST_LOG_DIR)
        } catch (e: Exception) {
            log("err", "пикер папки недоступен: $e")
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
        performBack() // единая логика с жестом: пузырь, потом история
        if (!this::webView.isInitialized || !webView.canGoBack()) {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        // Вернулись в приложение — служба больше не нужна, чат продолжается на экране
        stopService(Intent(this, KeepAliveService::class.java))
        // Блокировка ориентации переприменяется при каждом выходе на передний план,
        // чтобы настройка не «отклеивалась» после системных событий
        applyOrientationLock()
        // Индикаторы могли устареть, пока активити пересоздавалась
        refreshBarIcons()
    }

    override fun onPause() {
        super.onPause()
        // Служба держит процесс живым, пока приложение свернуто — только если
        // фон разрешён. Иначе глушим. При пересоздании ради темы службу не
        // запускаем: старая копия активити сейчас умирает
        if (backgroundEnabled && !recreatingForTheme) {
            startKeepAliveService()
        } else {
            stopService(Intent(this, KeepAliveService::class.java))
        }
        // Запоминаем последнюю открытую страницу
        val url = webView.url
        if (!url.isNullOrEmpty()) {
            prefs.edit().putString(KEY_LAST_URL, url).apply()
        }
        // Лог выгружается на диск один раз — не во время загрузки страницы
        if (loggingEnabled) saveLogToDownloads()
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
        val line = "${timeFmt.format(Date())} [$tag] $message"
        // Склейка одинаковых соседних строк: SPA-страница стреляет одними и теми
        // же событиями по несколько раз подряд, лог не должен в них тонуть
        if (logLines.isNotEmpty() && logLines.last() == line) return
        logLines.add(line)
        if (logLines.size > 500) logLines.removeAt(0)
    }

    // Запись лога: в выбранную SAF-папку, при недоступности — откат в «Загрузки».
    // Файл один и тот же (alice_log.txt), он перезаписывается, а не плодит копии
    private fun saveLogToDownloads() {
        try {
            val content = logLines.joinToString("\n")
            if (content.isEmpty()) return
            if (logDirUri != null && writeLogToSaf(content)) return
            // Откат: стандартная папка «Загрузки»
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

    // Запись в папку, выбранную через SAF. Документ создаётся один раз и
    // запоминается — дальше открываем его на перезапись
    private fun writeLogToSaf(content: String): Boolean {
        val tree = logDirUri ?: return false
        return try {
            val fileUriStr = prefs.getString(KEY_LOG_FILE, null)
            var opened = false
            if (fileUriStr != null) {
                try {
                    contentResolver.openOutputStream(Uri.parse(fileUriStr), "wt")?.use {
                        it.write(content.toByteArray())
                        opened = true
                    }
                } catch (e: Exception) {
                    // файл недоступен — создадим заново
                }
            }
            if (!opened) {
                val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
                val newUri = DocumentsContract.createDocument(
                    contentResolver, parentDoc, "text/plain", "alice_log.txt"
                ) ?: return false
                prefs.edit().putString(KEY_LOG_FILE, newUri.toString()).apply()
                contentResolver.openOutputStream(newUri, "wt")?.use { it.write(content.toByteArray()) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // --- Клиент WebView ---

    // Разовые сетевые сбои: лечатся одним тихим повтором без экрана ошибки
    private fun isTransientNetError(desc: String): Boolean {
        return listOf(
            "ERR_HTTP2",
            "ERR_CONNECTION_ABORTED",
            "ERR_CONNECTION_RESET",
            "ERR_TIMED_OUT",
            "ERR_NAME_NOT_RESOLVED"
        ).any { desc.contains(it, ignoreCase = true) }
    }

    private inner class AliceWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            log("net", "начали грузить: $url")
            loadingProgress.progress = 0
            topProgress.progress = 0
            topProgress.visibility = android.view.View.VISIBLE
            statusText.text = "Загрузка… 0%"
            lastLoggedProgress = -20 // чтобы прогресс новых загрузок снова писался в лог
            errorSincePageStart = false
            showLoading(true)
            showError(false)
        }

        // Первый видимый кадр — прячем оверлей сразу, не дожидаясь 100%
        override fun onPageCommitVisible(view: WebView?, url: String?) {
            super.onPageCommitVisible(view, url)
            log("net", "первый кадр: $url")
            revealPage()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            log("net", "страница загружена: $url, заголовок: «${view?.title ?: ""}»")
            // Полоска сверху убирается только здесь — по факту полной загрузки
            topProgress.progress = 100
            topProgress.visibility = android.view.View.GONE
            // Загрузка прошла чисто — копим лимит повторов заново
            if (!errorSincePageStart) httpRetries = 0
            revealPage()
            view?.let {
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
                errorSincePageStart = true
                if (isTransientNetError(desc) && httpRetries < MAX_HTTP_RETRIES) {
                    httpRetries++
                    // DNS-сбою нужно больше времени на восстановление, чем HTTP/2-сбою
                    val delay = if (desc.contains("ERR_NAME_NOT_RESOLVED", ignoreCase = true)) {
                        RETRY_DELAY_MS * 2
                    } else {
                        RETRY_DELAY_MS
                    }
                    log("net", "автоповтор загрузки ($httpRetries из $MAX_HTTP_RETRIES), пауза $delay мс")
                    view?.postDelayed({ view.reload() }, delay)
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

    // --- Зелёный ореол вокруг активных переключателей: концентрические
    // полупрозрачные кольца с затухающей яркостью — мягкое свечение без blur ---
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
            val pad = dp(5)
            val left = b.left + pad
            val top = b.top + pad
            val right = b.right - pad
            val bottom = b.bottom - pad
            val radius = (bottom - top) / 2f

            if (active) {
                val rings = 4
                for (i in rings downTo 1) {
                    val grow = (pad / rings) * i
                    val alpha = 50 / i
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

            fillPaint.color = Color.parseColor("#66303A46")
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, fillPaint)
        }

        override fun setAlpha(alpha: Int) { /* прозрачность управляется цветами */ }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { /* не нужно */ }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
