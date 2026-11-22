package ru.turuhansk.alice.lite

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class AliceWebViewClient(
    private val activity: MainActivity,
    private val onError: () -> Unit
) : WebViewClient() {

    companion object {
        private const val CHAT_URL = "https://alice.yandex.ru"
        private val ALLOWED_HOSTS = listOf("alice.yandex.ru", "yandex.ru")
        private val AUTH_HOSTS = listOf(
            "oauth.yandex.ru", "passport.yandex.ru", "id.yandex.ru"
        )
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        activity.showLoading(true)
        activity.hideError()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        activity.showLoading(false)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        val host = url.host ?: return false

        // Страницы входа Яндекса — внутри WebView
        if (AUTH_HOSTS.any { host.endsWith(it) || host == it }) return false

        // Наши домены — внутри WebView
        if (ALLOWED_HOSTS.any { host.endsWith(it) }) return false

        // Всё остальное — в системный браузер
        return try {
            val intent = Intent(Intent.ACTION_VIEW, url)
            activity.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("AliceLite", "Нет браузера для $url", e)
            false
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        // Основной документ не загрузился — показываем шутку
        if (request?.isForMainFrame == true) onError()
    }
}
