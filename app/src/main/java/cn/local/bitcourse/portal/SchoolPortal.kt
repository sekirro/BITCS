package cn.local.bitcourse.portal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.local.bitcourse.model.ParsedSchedule
import cn.local.bitcourse.model.SchoolParser
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.UUID

data class PortalSync(val schedule: ParsedSchedule, val calendarMonday: LocalDate?, val warnings: List<String>)

/** A school-owned login page keeps credentials inside WebView's school session. */
@SuppressLint("SetJavaScriptEnabled")
class SchoolPortal(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onLoginRequired: () -> Unit,
    private val onComplete: (PortalSync) -> Unit,
    private val onError: (String) -> Unit,
    private val onPhaseChanged: (SyncPhase) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var generation = 0L
    private var active = false
    private var destroyed = false
    private var clearingSession = false
    private val clearCallbacks = mutableListOf<() -> Unit>()
    private var loginNotified = false
    private var authenticationRetries = 0
    private var mode = PortalMode.DIRECT
    private var requestedTerm: String? = null
    private var resolvedTerm: String? = null
    private var schedule: ParsedSchedule? = null
    private var stage = Stage.TERM
    private var deadline = 0L
    private var pending: Pending? = null
    private var reportedPhase: SyncPhase? = null
    private enum class Stage { TERM, COURSES, CALENDAR }
    private data class Pending(val token: String, val page: String, val generation: Long,
                               val stage: Stage, val startedAt: Long, var started: Boolean = false,
                               var polling: Boolean = false)

    val webView: WebView = WebView(context).apply {
        // Chromium treats WRAP_CONTENT height as a zero-height CSS layout viewport,
        // even when Compose measures the native view to an exact visible size.
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        @Suppress("DEPRECATION")
        settings.saveFormData = false
        @Suppress("DEPRECATION")
        settings.savePassword = false
        if (Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = Client()
        // No JavaScript interface or cookie/header bridge is installed.
    }

    fun start(term: String?, mode: PortalMode) = onMain {
        if (destroyed) return@onMain
        if (clearingSession) {
            onError("正在清理学校登录状态，请稍后再试")
            return@onMain
        }
        cancelInternal()
        val normalized = try { term?.let(SchoolParser::validateTerm) } catch (_: IllegalArgumentException) {
            onError("学期格式不正确，请使用如 2025-2026-1 的连续学年格式")
            return@onMain
        }
        this.mode = mode
        requestedTerm = normalized
        resetStages()
        active = true
        loginNotified = false
        authenticationRetries = 0
        deadline = SystemClock.elapsedRealtime() + 180_000
        val startedGeneration = generation
        if (!reportPhase(SyncPhase.CONNECTING, startedGeneration)) return@onMain
        onStatus("正在打开学校课表页面")
        if (!active || destroyed || generation != startedGeneration) return@onMain
        webView.loadUrl(PortalUrls.page(mode))
        handler.post(ticker)
    }

    fun retry() = onMain { if (!destroyed) start(requestedTerm, mode) }

    fun cancel() = onMain {
        if (!destroyed) cancelInternal()
    }

    fun clearSession(onCleared: () -> Unit) = onMain {
        if (destroyed) return@onMain
        clearCallbacks += onCleared
        if (clearingSession) return@onMain
        clearingSession = true
        cancelInternal()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.clearCache(true)
        @Suppress("DEPRECATION")
        webView.clearFormData()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            onMain {
                CookieManager.getInstance().flush()
                clearingSession = false
                val completedCallbacks = clearCallbacks.toList()
                clearCallbacks.clear()
                if (!destroyed) completedCallbacks.forEach { callback -> if (!destroyed) callback() }
            }
        }
    }

    fun destroy() = onMain {
        if (destroyed) return@onMain
        cancelInternal()
        destroyed = true
        clearCallbacks.clear()
        webView.loadUrl("about:blank")
        webView.removeAllViews()
        webView.destroy()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post { block() }
    }

    private fun resetStages() {
        resolvedTerm = requestedTerm
        schedule = null
        stage = if (resolvedTerm == null) Stage.TERM else Stage.COURSES
    }

    private fun cancelInternal() {
        active = false
        generation++
        reportedPhase = null
        handler.removeCallbacks(ticker)
        discardPending()
        webView.stopLoading()
    }

    private fun discardPending() {
        val request = pending
        pending = null
        if (!destroyed && request != null && webView.url == request.page && PortalUrls.isCoursePage(request.page, mode)) {
            webView.evaluateJavascript(PortalScripts.clear(request.token), null)
        }
    }

    /** Callbacks run on the main thread and may synchronously cancel or restart this sync. */
    private fun reportPhase(phase: SyncPhase, expectedGeneration: Long = generation): Boolean {
        if (!active || destroyed || generation != expectedGeneration) return false
        if (reportedPhase != phase) {
            reportedPhase = phase
            onPhaseChanged(phase)
        }
        return active && !destroyed && generation == expectedGeneration
    }

    private fun notifyLogin() {
        val loginGeneration = generation
        if (!reportPhase(SyncPhase.AWAITING_LOGIN, loginGeneration) || loginNotified) return
        loginNotified = true
        onStatus("请在学校官方网页完成登录，完成后会自动读取课表")
        if (active && !destroyed && generation == loginGeneration) onLoginRequired()
    }

    private fun fail(message: String) {
        if (!active) return
        cancelInternal()
        onError(message)
    }

    private fun finish(monday: LocalDate?, warning: String? = null) {
        val result = schedule ?: return fail("课表数据未能读取，请重试")
        cancelInternal()
        onComplete(PortalSync(result, monday, listOfNotNull(warning)))
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!active || destroyed) return
            val tickGeneration = generation
            if (SystemClock.elapsedRealtime() > deadline) {
                operationFailed(stage, "等待学校页面超时，请重试；本地课表保持不变")
                return
            }
            val current = webView.url
            val request = pending
            if (request != null) {
                if (current != request.page || !PortalUrls.isCoursePage(current, mode)) {
                    discardPending()
                    resetStages()
                    if (PortalUrls.isLoginPage(current, mode)) notifyLogin()
                    else reportPhase(SyncPhase.CONNECTING)
                } else if (SystemClock.elapsedRealtime() - request.startedAt > 30_000) {
                    operationFailed(request.stage, "学校接口响应超时，请重试")
                } else if (request.started && !request.polling) {
                    poll(request)
                }
            } else if (PortalUrls.isCoursePage(current, mode)) {
                begin(current!!)
            } else if (PortalUrls.isLoginPage(current, mode)) {
                notifyLogin()
            }
            if (active && !destroyed && generation == tickGeneration) handler.postDelayed(this, 650)
        }
    }

    private fun begin(page: String) {
        if (!active || pending != null || !PortalUrls.isCoursePage(page, mode)) return
        val (url, form) = when (stage) {
            Stage.TERM -> PortalUrls.term(mode) to null
            Stage.COURSES -> PortalUrls.courses(mode) to mapOf("XNXQDM" to requireNotNull(resolvedTerm))
            Stage.CALENDAR -> PortalUrls.calendar(mode) to mapOf("requestParamStr" to gson.toJson(mapOf("XNXQDM" to requireNotNull(resolvedTerm), "ZC" to "1")))
        }
        check(PortalUrls.isApiUrl(url, mode))
        val request = Pending("__bitcourse_" + UUID.randomUUID().toString().replace("-", ""), page, generation, stage, SystemClock.elapsedRealtime())
        pending = request
        loginNotified = false
        val phase = when (stage) {
            Stage.TERM -> SyncPhase.READING_TERM
            Stage.COURSES -> SyncPhase.READING_COURSES
            Stage.CALENDAR -> SyncPhase.READING_CALENDAR
        }
        if (!reportPhase(phase, request.generation) || !isCurrent(request)) return
        onStatus(when (stage) { Stage.TERM -> "正在读取当前学期"; Stage.COURSES -> "正在读取课表"; Stage.CALENDAR -> "正在读取校历" })
        if (!isCurrent(request)) return
        webView.evaluateJavascript(PortalScripts.begin(request.token, page, url, form)) { raw ->
            if (!isCurrent(request)) return@evaluateJavascript
            val value = runCatching { JsonParser.parseString(raw).asString }.getOrNull()
            if (value == "started") request.started = true else discardPending()
        }
    }

    private fun isCurrent(request: Pending): Boolean = active && !destroyed &&
        request === pending && request.generation == generation && webView.url == request.page &&
        PortalUrls.isCoursePage(request.page, mode)

    private fun poll(request: Pending) {
        request.polling = true
        webView.evaluateJavascript(PortalScripts.poll(request.token, request.page)) { raw ->
            request.polling = false
            if (!isCurrent(request)) return@evaluateJavascript
            val value = PortalReply.decode(raw, request.token, request.page)
                ?: return@evaluateJavascript operationFailed(request.stage, "未能读取学校页面结果，请重试")
            val status = value.status
            if (status == "missing" || status == "changed") { discardPending(); return@evaluateJavascript }
            when (status) {
                "pending" -> Unit
                "auth" -> {
                    discardPending()
                    if (request.stage == Stage.CALENDAR && schedule != null) {
                        finish(null, "课表已读取；校历需要重新登录，可手动设置第一周周一")
                    } else {
                        authenticationRetries++
                        if (authenticationRetries > 2) {
                            fail("学校登录尚未完成，请清除学校登录状态后重试")
                            return@evaluateJavascript
                        }
                        notifyLogin()
                        // Re-enter the official portal, allowing its normal SSO redirect to run.
                        if (active && !destroyed && generation == request.generation) {
                            resetStages()
                            webView.loadUrl(PortalUrls.page(mode))
                        }
                    }
                }
                "complete" -> {
                    val body = value.body
                    discardPending()
                    if (body == null) operationFailed(request.stage, "学校接口没有返回数据") else accept(request.stage, body)
                }
                else -> operationFailed(request.stage, "未能读取学校接口，请检查网络或重新登录后重试")
            }
        }
    }

    private fun accept(completedStage: Stage, body: String) {
        if (!active) return
        try {
            when (completedStage) {
                Stage.TERM -> { resolvedTerm = SchoolParser.currentTerm(body); stage = Stage.COURSES }
                Stage.COURSES -> { schedule = SchoolParser.parseCourses(body, requireNotNull(resolvedTerm)); stage = Stage.CALENDAR }
                Stage.CALENDAR -> {
                    val monday = SchoolParser.firstMonday(body)
                    finish(monday, if (monday == null) "课表已读取；学校未返回校历，请手动设置第一周周一" else null)
                }
            }
        } catch (_: Exception) {
            operationFailed(completedStage, "学校返回的数据无法识别，请重新登录或稍后重试")
        }
    }

    private fun operationFailed(failedStage: Stage, message: String) {
        discardPending()
        if (failedStage == Stage.CALENDAR && schedule != null) finish(null, "课表已读取；校历读取失败，请手动设置第一周周一") else fail(message)
    }

    private inner class Client : WebViewClient() {
        private fun blocked(url: String?): Boolean = !(url == "about:blank" && !active) && !PortalUrls.isAllowedSchoolUrl(url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val reject = blocked(request.url.toString())
            if (reject && request.isForMainFrame) onMain { if (active) fail("已阻止打开非学校的安全网页地址") }
            return reject
        }

        @Deprecated("Legacy WebView callback")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = blocked(url)

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            return if (PortalUrls.isAllowedSchoolUrl(request.url.toString())) null else
                WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (!active) return
            if (!PortalUrls.isAllowedSchoolUrl(url)) { view.stopLoading(); fail("已阻止不安全的学校页面跳转"); return }
            discardPending()
            resetStages()
            if (PortalUrls.isLoginPage(url, mode)) notifyLogin()
            else if (url == view.url) reportPhase(SyncPhase.CONNECTING)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (active && PortalUrls.isLoginPage(url, mode)) notifyLogin()
            // The periodic probe also handles SPA transitions without onPageFinished.
        }

        override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: SslError) {
            sslHandler.cancel()
            val failedUrl = error.url
            onMain { if (active && !destroyed && failedUrl == webView.url) fail("学校网站证书校验失败，已停止连接") }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            val failedUrl = request.url.toString()
            if (request.isForMainFrame) onMain {
                if (active && !destroyed && failedUrl == webView.url) fail("学校页面加载失败，请检查网络后重试")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (!request.isForMainFrame) return
            val failedUrl = request.url.toString()
            onMain { if (active && !destroyed && failedUrl == webView.url) {
                if (response.statusCode == 401 || response.statusCode == 403) notifyLogin()
                else if (response.statusCode >= 400) fail("学校页面暂时不可用，请稍后重试")
            } }
        }
    }
}
