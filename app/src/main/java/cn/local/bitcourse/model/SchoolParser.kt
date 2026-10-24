package cn.local.bitcourse.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object SchoolParser {
    private val termPattern = Regex("^(\\d{4})-(\\d{4})-([1-3])$")
    private val weekSegment = Regex("^(\\d+)(?:-(\\d+))?(?:\\(([单双])\\)|([单双]))?$")
    private val trailingParity = Regex("(?:\\(([单双])\\)|([单双]))$")
    private const val MAX_WEEK = 60
    private const val MAX_PERIOD = 30

    fun isValidTerm(term: String): Boolean {
        val match = termPattern.matchEntire(term.trim()) ?: return false
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].toInt()
        return start >= 1 && end == start + 1
    }

    /** Returns the trimmed term, or throws a readable validation error. */
    fun validateTerm(term: String): String {
        if (!isValidTerm(term)) invalid("学期格式应为连续年份和学期编号，例如 2026-2027-1（编号为 1 至 3）")
        return term.trim()
    }

    fun currentTerm(json: String): String {
        val root = responseObject(json)
        val rows = root.requiredObject("datas", "当前学期")
            .requiredObject("dqxnxq", "当前学期")
            .requiredArray("rows", "当前学期")
        if (rows.size() == 0) invalid("教学中心未返回当前学期，请手动选择学期")
        val row = rows[0]
        if (!row.isJsonObject) invalid("当前学期数据格式异常")
        val term = row.asJsonObject.text("DM", "当前学期")
            ?: invalid("当前学期编号缺失，请手动选择学期")
        return validateTerm(term)
    }

    fun parseCourses(json: String, term: String): ParsedSchedule {
        val selectedTerm = validateTerm(term)
        val courseData = responseObject(json).requiredObject("datas", "课表")
            .requiredObject("cxxszhxqkb", "课表")
        val rows = courseData.requiredArray("rows", "课表")
        courseData.get("extParams")?.let { parameters ->
            if (!parameters.isJsonObject) invalid("课表查询状态格式异常")
            val status = parameters.asJsonObject
            // This inner API uses 1 for success; the outer response uses 0.
            if (status.has("code") && status.text("code", "课表查询状态") != "1") {
                invalid("教学中心课表查询未成功，已保留原有课表，请稍后重试")
            }
        }
        if (courseData.has("totalSize")) {
            val total = courseData.text("totalSize", "课表")?.toLongOrNull()
                ?: invalid("课表记录总数格式异常")
            // The school's unpaged response uses -1 for an unspecified total.
            if (total >= 0 && total > rows.size()) {
                invalid("教学中心仅返回了部分课表记录，未覆盖原有课表，请重新获取完整数据")
            }
        }
        val groups = linkedMapOf<CourseKey, MutableSet<Int>>()
        val warnings = linkedSetOf<String>()
        val offsets = mutableListOf<Int?>()

        rows.forEachIndexed { index, element ->
            val label = "第 ${index + 1} 条课程"
            if (!element.isJsonObject) invalid("$label 数据格式异常")
            val row = element.asJsonObject
            val returnedTerm = row.text("XNXQDM", label)
            if (!returnedTerm.isNullOrBlank() && returnedTerm != selectedTerm) {
                invalid("课程数据所属学期与选择的学期不一致，请重新获取")
            }
            val name = row.text("KCM", label)?.takeIf { it.isNotBlank() }
                ?: invalid("$label 缺少课程名称")
            val day = row.integer("SKXQ", label)
            val start = row.integer("KSJC", label)
            val end = row.integer("JSJC", label)
            if (day !in 1..7) invalid("$label 的星期应为 1 至 7")
            if (start !in 1..MAX_PERIOD || end !in start..MAX_PERIOD) invalid("$label 的起止节次应在 1 至 $MAX_PERIOD 节之间")

            val chinese = row.text("ZCMC", label).orEmpty()
            val bitmap = row.text("SKZC", label).orEmpty()
            val chineseWeeks = try {
                parseWeeks(chinese).takeIf { it.isNotEmpty() }
            } catch (_: IllegalArgumentException) {
                warnings += "$label 的中文周次无法解析，已尝试使用位图周次"
                null
            }
            val bitmapWeeks = try {
                parseBitmap(bitmap).takeIf { it.isNotEmpty() }
            } catch (_: IllegalArgumentException) {
                warnings += "$label 的位图周次格式异常，不能用于校历偏移核对"
                null
            }
            val displayWeeks: List<Int>
            if (chineseWeeks != null) {
                displayWeeks = chineseWeeks
                offsets += inferOffset(chineseWeeks, bitmapWeeks)
            } else {
                displayWeeks = bitmapWeeks ?: invalid("$label 缺少可用的上课周次")
                offsets += 0
                warnings += "$label 缺少可用的中文周次，已使用位图周次"
            }

            val key = CourseKey(
                code = row.text("KCH", label).orEmpty(),
                name = name,
                teacher = row.text("SKJS", label).orEmpty(),
                room = row.text("JASMC", label).orEmpty(),
                campus = row.text("XXXQMC", label).orEmpty(),
                day = day,
                start = start,
                end = end,
            )
            groups.getOrPut(key) { sortedSetOf() }.addAll(displayWeeks)
        }

        val knownOffsets = offsets.filterNotNull().toSet()
        val weekOffset = when {
            offsets.isEmpty() -> {
                warnings += "课表为空，无法判断中文周次与校历的偏移"
                null
            }
            offsets.any { it == null } -> {
                warnings += "部分课程的中文周次与位图周次无法对应，未自动推断校历偏移"
                null
            }
            knownOffsets.size != 1 -> {
                warnings += "各条课程的周次偏移不一致，未自动推断校历偏移"
                null
            }
            else -> knownOffsets.single()
        }
        val courses = groups.map { (key, weeks) ->
            Course(
                id = key.stableId(selectedTerm),
                name = key.name,
                teacher = key.teacher,
                room = key.room,
                campus = key.campus,
                day = key.day,
                startPeriod = key.start,
                endPeriod = key.end,
                weeks = weeks.sorted(),
            )
        }.sortedWith(compareBy<Course>({ it.day }, { it.startPeriod }, { it.endPeriod }, { it.name }, { it.room }, { it.id }))
        return ParsedSchedule(selectedTerm, courses, weekOffset, warnings.toList())
    }

    /** Reads the school calendar's week-one Monday, before applying a week offset. */
    fun firstMonday(json: String): LocalDate? {
        val root = responseObject(json)
        val data = root.get("data") ?: return null
        if (data.isJsonNull) return null
        if (!data.isJsonArray) invalid("校历 data 应为日期列表")
        for (element in data.asJsonArray) {
            if (!element.isJsonObject) invalid("校历日期记录格式异常")
            val row = element.asJsonObject
            if (row.text("XQ", "校历") != "1") continue
            val dateText = row.text("RQ", "校历")?.takeIf { it.isNotBlank() } ?: continue
            val date = try {
                if (dateText.length == 10) {
                    LocalDate.parse(dateText, DateTimeFormatter.ISO_LOCAL_DATE)
                } else {
                    val isoText = if (dateText.getOrNull(10) == ' ') {
                        dateText.replaceRange(10, 11, "T")
                    } else dateText
                    LocalDate.from(DateTimeFormatter.ISO_DATE_TIME.parse(isoText))
                }
            } catch (_: DateTimeParseException) {
                invalid("校历日期格式无效，应为 yyyy-MM-dd 或 ISO 日期时间")
            }
            if (date.dayOfWeek != DayOfWeek.MONDAY) {
                invalid("校历将非星期一日期标记为星期一，请手动确认学期起始日期")
            }
            return date
        }
        return null
    }

    /** Supports lists, inclusive ranges, and odd/even week qualifiers. */
    fun parseWeeks(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        var normalized = buildString {
            for (character in text.trim()) append(
                when (character) {
                    in '０'..'９' -> '0' + (character - '０')
                    '（', '[', '【' -> '('
                    '）', ']', '】' -> ')'
                    '，', '、', ';', '；' -> ','
                    '—', '–', '－', '~', '～', '至', '到' -> '-'
                    else -> character
                }
            )
        }.replace(Regex("\\s+"), "").replace("周次", "").replace("周", "").replace("第", "")
        var globalParity: String? = null
        val paritySuffix = trailingParity.find(normalized)
        if (paritySuffix != null && normalized.count { it == '单' || it == '双' } == 1) {
            globalParity = paritySuffix.groupValues[1].ifEmpty { paritySuffix.groupValues[2] }
            normalized = normalized.substring(0, paritySuffix.range.first).trimEnd(',')
        }
        if (normalized.isEmpty()) invalid("单双周标记缺少具体周次范围")
        val result = sortedSetOf<Int>()
        for (part in normalized.split(',')) {
            val match = weekSegment.matchEntire(part) ?: invalid("上课周次格式无法识别")
            val start = match.groupValues[1].toIntOrNull() ?: invalid("上课周次超出有效范围")
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?: if (match.groupValues[2].isEmpty()) start else invalid("上课周次超出有效范围")
            if (start < 1 || end < start || end > MAX_WEEK) invalid("上课周次范围无效")
            val parity = match.groupValues[3].ifEmpty { match.groupValues[4] }.ifEmpty { globalParity.orEmpty() }
            for (week in start..end) {
                if (parity.isEmpty() || (parity == "单" && week % 2 == 1) || (parity == "双" && week % 2 == 0)) {
                    result += week
                }
            }
        }
        return result.toList()
    }

    private fun parseBitmap(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        if (text.length > MAX_WEEK || text.any { it != '0' && it != '1' }) invalid("位图周次格式无效")
        return text.mapIndexedNotNull { index, bit -> if (bit == '1') index + 1 else null }
    }

    private fun inferOffset(display: List<Int>, bitmap: List<Int>?): Int? {
        if (bitmap == null || bitmap.size != display.size) return null
        val differences = display.zip(bitmap) { shown, raw -> raw - shown }.toSet()
        return differences.singleOrNull()
    }

    private fun responseObject(json: String): JsonObject {
        val text = json.trim().removePrefix("\uFEFF").trimStart()
        if (text.startsWith("<")) invalid("教学中心返回了网页而非数据，请确认网页登录仍然有效")
        val root = try {
            JsonReader(StringReader(text)).use { reader ->
                reader.strictness = Strictness.STRICT
                val element = JsonParser.parseReader(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT || !element.isJsonObject) {
                    invalid("教学中心响应应为 JSON 对象")
                }
                element.asJsonObject
            }
        } catch (_: Exception) {
            invalid("教学中心响应不是有效的 JSON 对象，请重新登录后获取")
        }
        if (root.has("code") && !root.get("code").isJsonNull) {
            val code = root.text("code", "接口状态")
            if (code != "0" && code != "200") invalid("教学中心接口返回失败状态，请重新登录或稍后重试")
        }
        root.get("success")?.let { success ->
            if (!success.isJsonNull && (!success.isJsonPrimitive || success.asString.lowercase() != "true")) {
                invalid("教学中心接口报告请求失败，请重新登录或稍后重试")
            }
        }
        root.get("error")?.let { error ->
            if (!error.isJsonNull && !(error.isJsonPrimitive && error.asString in setOf("", "0", "false"))) {
                invalid("教学中心接口返回错误，请重新登录或稍后重试")
            }
        }
        return root
    }

    private fun JsonObject.requiredObject(key: String, label: String): JsonObject {
        val element = get(key)
        if (element == null || !element.isJsonObject) invalid("$label 缺少 $key 对象或格式异常")
        return element.asJsonObject
    }

    private fun JsonObject.requiredArray(key: String, label: String): JsonArray {
        val element = get(key)
        if (element == null || !element.isJsonArray) invalid("$label 缺少 $key 列表或格式异常")
        return element.asJsonArray
    }

    private fun JsonObject.text(key: String, label: String): String? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || element.asJsonPrimitive.isBoolean) invalid("$label 的 $key 字段格式异常")
        return element.asString.trim()
    }

    private fun JsonObject.integer(key: String, label: String): Int =
        text(key, label)?.toIntOrNull() ?: invalid("$label 的 $key 字段缺失或不是整数")

    private data class CourseKey(
        val code: String,
        val name: String,
        val teacher: String,
        val room: String,
        val campus: String,
        val day: Int,
        val start: Int,
        val end: Int,
    ) {
        fun stableId(term: String): String {
            val fields = listOf(term, code, name, teacher, room, campus, day.toString(), start.toString(), end.toString())
            val encoded = fields.joinToString("") { "${it.length}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}
