package cn.local.bitcourse.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.local.bitcourse.data.CourseDisplay
import cn.local.bitcourse.data.SavedSchedule
import cn.local.bitcourse.data.SchedulePolicy
import cn.local.bitcourse.model.Course
import cn.local.bitcourse.model.SchoolParser
import cn.local.bitcourse.portal.PortalMode
import java.time.LocalDate

private val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val courseColors = listOf(0xFFE1F1EA, 0xFFE4EDFA, 0xFFF7EACF, 0xFFF3E5EF, 0xFFE2EFF1, 0xFFEBE8F6)

@Composable
fun CourseScreen(term: String, schedule: SavedSchedule?, firstMonday: LocalDate?, busy: Boolean,
                 status: String, automaticSync: Boolean, mode: PortalMode, followSchoolTerm: Boolean, onSync: () -> Unit,
                 onCancel: () -> Unit, onSettingsSave: (String, PortalMode, Boolean, Boolean) -> Unit,
                 onPickDate: () -> Unit, onLogout: () -> Unit) {
    val today = LocalDate.now()
    val sourceCourses = schedule?.courses.orEmpty()
    val courses = remember(sourceCourses) { CourseDisplay.mergePeriods(sourceCourses) }
    val currentWeek = SchedulePolicy.weekAt(firstMonday, today)
    val maxWeek = maxOf(20, courses.flatMap { it.weeks }.maxOrNull() ?: 20).coerceAtMost(60)
    var week by remember(term, firstMonday, maxWeek) { mutableIntStateOf(currentWeek.coerceIn(1, maxWeek)) }
    var settings by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    var listView by remember { mutableStateOf(false) }
    var selectedCourse by remember { mutableStateOf<Course?>(null) }
    var statusDetails by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (schedule == null) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
                verticalArrangement = Arrangement.Center) {
                Text("把课表带在身边", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("在学校官网完成一次登录，课程会自动导入并保存在手机上。之后可以随时按周查看。",
                    Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onSync, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("登录学校，导入课表") }
                TextButton(onClick = { settings = true }) { Text("选择学期与访问方式") }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    TextButton(onClick = onCancel) { Text("取消同步") }
                }
                Text(status, Modifier.padding(top = 12.dp).clickable { statusDetails = true },
                    style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { week-- }, enabled = week > 1, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "上一周")
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("第 $week 周", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1)
                }
                IconButton(onClick = { week++ }, enabled = week < maxWeek, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "下一周")
                }
                TextButton(onClick = { week = currentWeek.coerceIn(1, maxWeek) }, enabled = firstMonday != null,
                    modifier = Modifier.width(48.dp), contentPadding = PaddingValues(0.dp)) { Text("本周", fontSize = 12.sp) }
                IconButton(onClick = if (busy) onCancel else onSync,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = if (busy) "取消同步" else "同步课表" }) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = null)
                }
                IconButton(onClick = { settings = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Settings, "设置") }
            }
            if (listView && courses.none { week in it.weeks }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("第 $week 周暂无已排课程") }
            } else if (listView) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    (1..7).forEach { day ->
                        val dayCourses = SchedulePolicy.coursesOn(courses, week, day)
                        if (dayCourses.isNotEmpty()) {
                            Text(dayNames[day - 1], Modifier.padding(vertical = 8.dp), fontWeight = FontWeight.Bold)
                            dayCourses.forEach { course ->
                                Card(onClick = { selectedCourse = course }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = courseColor(course))) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(course.name, fontWeight = FontWeight.Bold)
                                        Text("${course.startPeriod}–${course.endPeriod} 节 · ${course.room.ifBlank { "地点待定" }}",
                                            style = MaterialTheme.typography.bodyMedium)
                                        Text(SchedulePolicy.weeksLabel(course.weeks), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                TimetableGrid(courses, week, firstMonday, onCourse = { selectedCourse = it })
            }
        }
    }
    if (settings) SettingsDialog(term, mode, automaticSync, followSchoolTerm,
        onDismiss = { settings = false },
        onSave = { t, m, a, f -> onSettingsSave(t, m, a, f); settings = false },
        onDate = { settings = false; onPickDate() },
        onLogout = { settings = false; logout = true },
        viewLabel = if (schedule == null) null else if (listView) "查看周课表" else "查看课程列表",
        onViewChange = { settings = false; listView = !listView },
        onStatus = { settings = false; statusDetails = true })
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("退出学校登录？") },
        text = { Text("本地课表会保留。下次同步时需要重新登录学校。") },
        confirmButton = { TextButton(onClick = { logout = false; onLogout() }) { Text("退出登录") } },
        dismissButton = { TextButton(onClick = { logout = false }) { Text("取消") } })
    if (statusDetails) AlertDialog(onDismissRequest = { statusDetails = false }, title = { Text("同步状态") },
        text = { Text(status, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { statusDetails = false }) { Text("知道了") } })
    selectedCourse?.let { course ->
        AlertDialog(onDismissRequest = { selectedCourse = null }, title = { Text(course.name) },
            text = {
                Text(listOf(dayNames[course.day - 1] + " ${course.startPeriod}–${course.endPeriod} 节",
                    SchedulePolicy.weeksLabel(course.weeks), "教室：${course.room.ifBlank { "待定" }}",
                    "教师：${course.teacher.ifBlank { "未提供" }}", "校区：${course.campus.ifBlank { "未提供" }}").joinToString("\n\n"),
                    Modifier.verticalScroll(rememberScrollState()))
            }, confirmButton = { TextButton(onClick = { selectedCourse = null }) { Text("知道了") } })
    }
}

private fun courseColor(course: Course) = Color(courseColors[Math.floorMod(course.name.hashCode(), courseColors.size)])

@Composable
private fun TimetableGrid(courses: List<Course>, week: Int, firstMonday: LocalDate?, onCourse: (Course) -> Unit) {
    val periods = maxOf(13, courses.maxOfOrNull { it.endPeriod } ?: 13)
    val periodWidth = 28.dp
    val monday = firstMonday?.plusWeeks((week - 1).toLong())
    val density = LocalDensity.current
    val nameLineHeight = with(density) { 13.sp.toDp() }
    val detailLineHeight = with(density) { 12.sp.toDp() }
    val blocksByDay = remember(courses, week) {
        (1..7).map { TimetableLayout.blocksForDay(courses, week, it) }
    }
    var selectedBlock by remember(courses, week) { mutableStateOf<TimetableBlock?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val headerHeight = if (monday == null) maxOf(28.dp, with(density) { 14.sp.toDp() } + 4.dp)
            else maxOf(36.dp, with(density) { 26.sp.toDp() } + 4.dp)
        val availableHeight = (maxHeight - headerHeight).coerceAtLeast(0.dp)
        // Use the whole remaining screen for the full day; only unusually short windows or
        // schedules with many periods need scrolling to preserve readable, tappable cells.
        val needsScroll = availableHeight < 24.dp * periods
        val gridHeight = if (needsScroll) 24.dp * periods else availableHeight
        val rowHeight = gridHeight / periods
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(headerHeight)) {
                Box(Modifier.width(periodWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("节", style = MaterialTheme.typography.labelSmall)
                }
                (1..7).forEach { day ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text(dayNames[day - 1], fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        monday?.plusDays((day - 1).toLong())?.let {
                            Text("${it.monthValue}/${it.dayOfMonth}", fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState(), enabled = needsScroll)) {
                Box(Modifier.width(periodWidth).height(gridHeight)) {
                    (1..periods).forEach { period ->
                        Box(Modifier.offset(y = rowHeight * (period - 1)).height(rowHeight).fillMaxWidth(),
                            contentAlignment = Alignment.Center) {
                            Text(period.toString(), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                blocksByDay.forEach { blocks ->
                    Box(Modifier.weight(1f).height(gridHeight)) {
                        Canvas(Modifier.matchParentSize()) {
                            val lineColor = Color(0xFFE1E7E1)
                            for (period in 0..periods) {
                                val y = (size.height * period / periods).coerceAtMost((size.height - 0.5f).coerceAtLeast(0f))
                                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                            }
                            drawLine(lineColor, Offset.Zero, Offset(0f, size.height), strokeWidth = 1f)
                            drawLine(lineColor, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), strokeWidth = 1f)
                        }
                        blocks.forEach { block ->
                            val course = block.courses.first()
                            val span = block.endPeriod - block.startPeriod + 1
                            val grouped = block.courses.size > 1
                            val textHeight = (rowHeight * span - 6.dp).coerceAtLeast(0.dp)
                            val countHeight = if (grouped) detailLineHeight else 0.dp
                            val showRoom = !grouped && textHeight >= nameLineHeight * 2 + detailLineHeight + 2.dp
                            val roomLines = if (span >= 2 && textHeight >= nameLineHeight * 2 + detailLineHeight * 2 + 2.dp) 2 else 1
                            val roomHeight = if (showRoom) detailLineHeight * roomLines + 2.dp else 0.dp
                            val showName = textHeight >= countHeight + nameLineHeight
                            val nameLines = ((textHeight - countHeight - roomHeight) / nameLineHeight).toInt().coerceIn(1, 8)
                            Box(Modifier.offset(y = rowHeight * (block.startPeriod - 1))
                                .fillMaxWidth().height(rowHeight * span).padding(1.dp)
                                .clip(RoundedCornerShape(6.dp)).background(courseColor(course)).clickable {
                                    if (grouped) selectedBlock = block else onCourse(course)
                                }.padding(2.dp)) {
                                Column {
                                    if (grouped) Text("共${block.courses.size}门", fontSize = 10.sp, lineHeight = 12.sp,
                                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (showName) Text(if (grouped) block.courses.joinToString(" / ") { it.name } else course.name,
                                        fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
                                        maxLines = nameLines, overflow = TextOverflow.Ellipsis)
                                    if (showRoom) {
                                        Text(course.room.ifBlank { "地点待定" }, Modifier.padding(top = 2.dp),
                                            fontSize = 10.sp, lineHeight = 12.sp,
                                            maxLines = roomLines, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedBlock?.let { block ->
        AlertDialog(onDismissRequest = { selectedBlock = null },
            title = { Text("${dayNames[block.courses.first().day - 1]} · ${block.courses.size} 门课程") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text("这些课程的部分节次重叠，点击查看完整信息。", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp))
                    block.courses.forEach { course ->
                        Card(onClick = { selectedBlock = null; onCourse(course) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = courseColor(course))) {
                            Column(Modifier.padding(12.dp)) {
                                Text(course.name, fontWeight = FontWeight.Bold)
                                Text("${course.startPeriod}–${course.endPeriod} 节 · ${course.room.ifBlank { "地点待定" }}",
                                    style = MaterialTheme.typography.bodySmall)
                                if (course.teacher.isNotBlank()) Text(course.teacher, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { selectedBlock = null }) { Text("关闭") } })
    }
}

@Composable
private fun SettingsDialog(term: String, mode: PortalMode, automaticSync: Boolean, followSchoolTerm: Boolean, onDismiss: () -> Unit,
                           onSave: (String, PortalMode, Boolean, Boolean) -> Unit, onDate: () -> Unit, onLogout: () -> Unit,
                           viewLabel: String?, onViewChange: () -> Unit, onStatus: () -> Unit) {
    var editedTerm by remember { mutableStateOf(term) }
    var editedMode by remember { mutableStateOf(mode) }
    var editedAuto by remember { mutableStateOf(automaticSync) }
    var editedFollow by remember { mutableStateOf(followSchoolTerm) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("课表设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("跟随学校当前学期", Modifier.weight(1f)); Switch(checked = editedFollow, onCheckedChange = { editedFollow = it; error = "" })
                }
                OutlinedTextField(value = editedTerm, onValueChange = { editedTerm = it; error = "" }, label = { Text("学期") },
                    placeholder = { Text("例如 2026-2027-1") }, enabled = !editedFollow, singleLine = true, isError = error.isNotBlank(), modifier = Modifier.fillMaxWidth())
                Text(if (editedFollow) "每次同步都读取学校当前学期；往期课表仍保留在手机中" else "修改学期后保存，再点击同步；已保存的学期可直接离线查看",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("访问方式", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                listOf(PortalMode.DIRECT to "校园直连", PortalMode.WEBVPN to "校外访问（WebVPN）").forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { editedMode = value }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = editedMode == value, onClick = { editedMode = value }); Text(label)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("打开 App 时自动同步", Modifier.weight(1f)); Switch(checked = editedAuto, onCheckedChange = { editedAuto = it })
                }
                Text("登录失效时提示重新登录，离线课表继续保留。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onDate) { Text("设置第 1 周的星期一") }
                if (viewLabel != null) TextButton(onClick = onViewChange) { Text(viewLabel) }
                TextButton(onClick = onStatus) { Text("查看同步状态") }
                TextButton(onClick = onLogout) { Text("退出学校登录") }
            }
        },
        confirmButton = { TextButton(onClick = {
            val normalized = editedTerm.trim()
            if (normalized.isBlank() && editedFollow || SchoolParser.isValidTerm(normalized)) {
                runCatching { onSave(normalized, editedMode, editedAuto, editedFollow) }.onFailure { error = "设置保存失败，请重试" }
            } else error = "请输入有效学期，例如 2026-2027-1"
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
