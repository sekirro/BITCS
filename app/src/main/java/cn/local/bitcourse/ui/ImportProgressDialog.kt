package cn.local.bitcourse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.local.bitcourse.portal.SyncPhase

val SyncPhase.isImporting: Boolean
    get() = this == SyncPhase.READING_TERM || this == SyncPhase.READING_COURSES ||
        this == SyncPhase.READING_CALENDAR || this == SyncPhase.SAVING

/** Shows real stages, never a time-based or estimated download percentage. */
@Composable
fun ImportProgressDialog(phase: SyncPhase, hasSavedSchedule: Boolean, onCancel: () -> Unit) {
    val steps = listOf("确定学期", "提取课程安排", "读取校历", "保存到手机")
    val current = when (phase) {
        SyncPhase.READING_TERM -> 0
        SyncPhase.READING_COURSES -> 1
        SyncPhase.READING_CALENDAR -> 2
        SyncPhase.SAVING -> 3
        else -> return
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(dismissOnClickOutside = false)) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(42.dp), strokeWidth = 4.dp)
                Text("正在导入课表", Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("正在从学校读取并保存课程，请稍候。", Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${current + 1} / ${steps.size} · ${steps[current]}",
                    Modifier.padding(top = 16.dp, bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold)
                steps.forEachIndexed { index, label ->
                    val active = index == current
                    val complete = index < current
                    val color = if (active || complete) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).background(
                            if (active || complete) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                            if (complete) Icon(Icons.Default.Check, contentDescription = "已完成", modifier = Modifier.size(18.dp), tint = color)
                            else Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = color)
                        }
                        Text(label, Modifier.padding(start = 12.dp).weight(1f), color = color,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodyMedium)
                        if (active) Text("进行中", color = color, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (hasSavedSchedule) Text("导入完成后会更新课表，取消会保留现有课表。",
                    Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onCancel, modifier = Modifier.padding(top = 12.dp)) { Text("取消导入") }
            }
        }
    }
}
