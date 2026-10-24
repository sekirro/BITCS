package cn.local.bitcourse.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.local.bitcourse.PortalView
import cn.local.bitcourse.portal.PortalMode
import cn.local.bitcourse.portal.SyncPhase
import kotlinx.coroutines.delay
import java.net.URI

@Composable
fun LoginScreen(status: String, phase: SyncPhase, mode: PortalMode, webView: WebView, onBack: () -> Unit,
                onRetry: () -> Unit, onSwitchMode: () -> Unit) {
    BackHandler(onBack = onBack)
    var host by remember { mutableStateOf("") }
    LaunchedEffect(webView) {
        while (true) {
            host = runCatching { URI(webView.url.orEmpty()).host.orEmpty() }.getOrDefault("")
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("返回课表") }
            TextButton(onClick = onRetry) { Text("重新连接") }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("学校网页登录", style = MaterialTheme.typography.titleLarge)
            if (host.isNotBlank()) Text(host, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            Text("登录信息由学校页面处理。请确认页面属于 bit.edu.cn。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = onSwitchMode, contentPadding = PaddingValues(0.dp)) {
                Text(if (mode == PortalMode.DIRECT) "当前：校园直连 · 切换校外访问" else "当前：校外访问 · 切换校园直连")
            }
        }
        HorizontalDivider()
        if (phase == SyncPhase.CONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
        PortalView(webView, Modifier.fillMaxWidth().weight(1f))
    }
}
