package cn.local.bitcourse

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.local.bitcourse.data.SavedSchedule
import cn.local.bitcourse.data.SchedulePolicy
import cn.local.bitcourse.data.ScheduleStore
import cn.local.bitcourse.model.SchoolParser
import cn.local.bitcourse.portal.PortalMode
import cn.local.bitcourse.portal.PortalSync
import cn.local.bitcourse.portal.SchoolPortal
import cn.local.bitcourse.portal.SyncPhase
import cn.local.bitcourse.ui.CourseScreen
import cn.local.bitcourse.ui.ImportProgressDialog
import cn.local.bitcourse.ui.isImporting
import cn.local.bitcourse.ui.LoginScreen
import java.time.DayOfWeek
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var store: ScheduleStore
    private lateinit var portal: SchoolPortal
    private var term by mutableStateOf("")
    private var schedule by mutableStateOf<SavedSchedule?>(null)
    private var manualMonday by mutableStateOf<LocalDate?>(null)
    private var mode by mutableStateOf(PortalMode.DIRECT)
    private var automaticSync by mutableStateOf(true)
    private var followSchoolTerm by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var loginVisible by mutableStateOf(false)
    private var syncPhase by mutableStateOf(SyncPhase.CONNECTING)
    private var message by mutableStateOf("登录学校后，课程会自动同步到这里")
    private var notice by mutableStateOf<String?>(null)
    private var interactive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScheduleStore(this)
        term = store.currentTerm
        schedule = store.load(term)
        manualMonday = store.manualMonday(term)
        mode = runCatching { PortalMode.valueOf(store.portalMode) }.getOrDefault(PortalMode.DIRECT)
        automaticSync = store.automaticSync
        followSchoolTerm = store.followSchoolTerm
        if (schedule != null) message = "正在显示手机中保存的课表"

        portal = SchoolPortal(
            context = this,
            onStatus = { message = it },
            onLoginRequired = {
                if (interactive) {
                    loginVisible = true
                    message = "请在学校页面完成登录，随后将自动同步"
                } else {
                    cancelSync()
                    message = "需要学校登录，已保留本地课表"
                    notice = message
                }
            },
            onComplete = ::acceptSync,
            onError = {
                busy = false
                loginVisible = false
                message = if (schedule != null) "$it。本地课表已保留" else it
                notice = message
            },
            onPhaseChanged = { phase ->
                val startedImporting = !syncPhase.isImporting && phase.isImporting
                syncPhase = phase
                if (startedImporting && interactive) {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(portal.webView.windowToken, 0)
                }
            },
        )

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF176B57),
                secondary = Color(0xFF54746A),
                background = Color(0xFFF6F8F5),
                surface = Color.White,
                onSurface = Color(0xFF182A23),
            )) {
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(notice) {
                    val text = notice ?: return@LaunchedEffect
                    snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short)
                    if (notice == text) notice = null
                }
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    if (loginVisible) {
                        LoginScreen(
                            status = message,
                            phase = syncPhase,
                            mode = mode,
                            webView = portal.webView,
                            onBack = {
                                cancelSync()
                                message = if (schedule != null) "已返回本地课表" else "同步已取消，可稍后重新登录"
                                notice = message
                            },
                            onRetry = { portal.retry(); busy = true },
                            onSwitchMode = {
                                mode = if (mode == PortalMode.DIRECT) PortalMode.WEBVPN else PortalMode.DIRECT
                                store.portalMode = mode.name
                                beginSync(true)
                            },
                        )
                    } else {
                        CourseScreen(
                            term = term, schedule = schedule,
                            firstMonday = manualMonday ?: schedule?.firstMonday?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                            busy = busy, status = message, automaticSync = automaticSync, mode = mode,
                            followSchoolTerm = followSchoolTerm,
                            onSync = { beginSync(true) },
                            onCancel = { cancelSync(); message = "已取消同步，保留现有课表"; notice = message },
                            onSettingsSave = ::saveSettings,
                            onPickDate = ::pickDate,
                            onLogout = {
                                cancelSync()
                                busy = true
                                message = "正在退出学校登录…"
                                portal.clearSession {
                                    busy = false
                                    store.hasSynced = false
                                    message = "已退出学校登录，本地课表仍可查看"
                                    notice = message
                                }
                            },
                        )
                        // A mounted WebView can refresh the school's session without interrupting offline viewing.
                        PortalView(portal.webView, Modifier.size(1.dp).alpha(0f))
                    }
                    if (interactive && busy && syncPhase.isImporting) {
                        ImportProgressDialog(
                            phase = syncPhase,
                            hasSavedSchedule = schedule != null,
                            onCancel = {
                                cancelSync()
                                message = if (schedule != null) "导入已取消，本地课表已保留" else "导入已取消，可稍后重新同步"
                                notice = message
                            },
                        )
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
                }
            }
        }

        if (store.hasSynced && automaticSync) window.decorView.post { beginSync(false) }
    }

    private fun beginSync(showLogin: Boolean) {
        interactive = showLogin
        busy = true
        loginVisible = showLogin
        syncPhase = SyncPhase.CONNECTING
        notice = null
        message = "正在连接学校教学中心…"
        portal.start(if (followSchoolTerm) null else term.takeIf { it.isNotBlank() }, mode)
    }

    private fun cancelSync() {
        portal.cancel()
        busy = false
        loginVisible = false
        syncPhase = SyncPhase.CONNECTING
        interactive = false
    }

    private fun acceptSync(result: PortalSync) {
        val resultTerm = result.schedule.term
        if (!followSchoolTerm && term.isNotBlank() && term != resultTerm) return
        syncPhase = SyncPhase.SAVING
        message = "正在保存课表到手机…"
        val selectedMonday = store.manualMonday(resultTerm)
        val monday = runCatching {
            SchedulePolicy.resolveMonday(result.calendarMonday, result.schedule.weekOffset, selectedMonday)
        }.getOrNull()
        val warnings = (result.warnings + result.schedule.warnings +
            if (monday == null) listOf("尚未确定第 1 周日期，可先按周查看，再手动设置") else emptyList()).distinct()
        val saved = SavedSchedule(
            term = resultTerm,
            courses = result.schedule.courses,
            syncedAtMillis = System.currentTimeMillis(),
            firstMonday = monday?.toString(),
            dateSource = if (selectedMonday != null) "manual" else if (monday != null) "school" else "unknown",
            warnings = warnings,
        )
        // A small normalized timetable is committed atomically in this main-thread callback.
        // A semester switch/cancel cannot interleave with its cache commit.
        try {
                store.save(saved)
                store.currentTerm = resultTerm
                term = resultTerm
                schedule = saved
                manualMonday = selectedMonday
                store.hasSynced = true
                busy = false
                loginVisible = false
                val count = saved.courses.map { it.name }.distinct().size
                message = if (count == 0) "导入完成，学校返回本学期暂无已排课程" else "导入完成，共 $count 门课程"
                if (warnings.isNotEmpty()) message += "。" + warnings.joinToString("；")
                if (interactive || warnings.isNotEmpty()) notice = message
        } catch (_: Exception) {
                busy = false
                loginVisible = false
                message = "课程已获取，但手机保存失败。请检查存储空间后重试"
                notice = message
        }
    }

    private fun saveSettings(newTerm: String, newMode: PortalMode, auto: Boolean, follow: Boolean) {
        val normalized = if (newTerm.isBlank() && follow) "" else SchoolParser.validateTerm(newTerm)
        cancelSync()
        if (normalized.isNotBlank()) store.currentTerm = normalized
        term = normalized
        mode = newMode
        automaticSync = auto
        followSchoolTerm = follow
        store.portalMode = newMode.name
        store.automaticSync = auto
        store.followSchoolTerm = follow
        schedule = store.load(term)
        manualMonday = store.manualMonday(term)
        message = "设置已保存。点击同步获取所选学期的课表"
        notice = message
    }

    private fun pickDate() {
        if (term.isBlank()) {
            Toast.makeText(this, "请先同步或在设置中选择学期", Toast.LENGTH_LONG).show()
            return
        }
        val current = manualMonday ?: schedule?.firstMonday?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now().with(DayOfWeek.MONDAY)
        DatePickerDialog(this, { _, year, month, day ->
            val selected = LocalDate.of(year, month + 1, day)
            if (selected.dayOfWeek != DayOfWeek.MONDAY) {
                Toast.makeText(this, "请选择第 1 周的星期一", Toast.LENGTH_LONG).show()
            } else {
                runCatching {
                    store.setManualMonday(term, selected)
                    manualMonday = selected
                    message = "第 1 周从 $selected 开始，后续同步将保留你的设置"
                    notice = message
                }.onFailure { Toast.makeText(this, "日期保存失败，请检查存储空间", Toast.LENGTH_LONG).show() }
            }
        }, current.year, current.monthValue - 1, current.dayOfMonth).apply {
            setTitle("选择第 1 周的星期一")
            show()
        }
    }

    override fun onDestroy() {
        if (::portal.isInitialized) portal.destroy()
        super.onDestroy()
    }
}

@Composable
fun PortalView(webView: WebView, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
    )
}
