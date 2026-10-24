package cn.local.bitcourse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.local.bitcourse.portal.SyncPhase
import cn.local.bitcourse.ui.ImportProgressDialog

/** Offline visual fixture: no school requests, credentials or stored courses. */
class ImportPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phase = runCatching { SyncPhase.valueOf(intent.getStringExtra("phase").orEmpty()) }
            .getOrDefault(SyncPhase.READING_COURSES)
        setContent {
            var visible by remember { mutableStateOf(true) }
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF176B57))) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                    contentAlignment = Alignment.Center) {
                    Text(if (visible) "导入界面验证（模拟阶段）" else "已取消导入")
                    if (visible) ImportProgressDialog(phase, hasSavedSchedule = true, onCancel = { visible = false })
                }
            }
        }
    }
}
