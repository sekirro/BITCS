package cn.local.bitcourse.data

import cn.local.bitcourse.model.Course
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class SavedSchedule(
    val term: String,
    val courses: List<Course>,
    val syncedAtMillis: Long,
    val firstMonday: String?,
    val dateSource: String,
    val warnings: List<String>,
)

object SchedulePolicy {
    fun resolveMonday(calendarMonday: LocalDate?, weekOffset: Int?, manualMonday: LocalDate?): LocalDate? {
        if (manualMonday != null) {
            require(manualMonday.dayOfWeek == DayOfWeek.MONDAY) { "请选择第 1 周的星期一" }
            return manualMonday
        }
        if (calendarMonday == null || weekOffset == null) return null
        require(calendarMonday.dayOfWeek == DayOfWeek.MONDAY) { "校历日期不是星期一" }
        require(weekOffset in -52..52) { "校历周次差值超出范围" }
        return calendarMonday.plusWeeks(weekOffset.toLong())
    }

    fun weekAt(firstMonday: LocalDate?, today: LocalDate = LocalDate.now()): Int =
        if (firstMonday == null) 1 else Math.floorDiv(ChronoUnit.DAYS.between(firstMonday, today), 7).toInt() + 1

    fun coursesOn(courses: List<Course>, week: Int, day: Int): List<Course> = courses
        .filter { it.day == day && week in it.weeks }
        .sortedWith(compareBy<Course> { it.startPeriod }.thenBy { it.endPeriod }.thenBy { it.name })

    fun weeksLabel(weeks: List<Int>): String {
        val sorted = weeks.distinct().sorted()
        if (sorted.isEmpty()) return "周次待确认"
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var end = start
        for (week in sorted.drop(1)) {
            if (week == end + 1) end = week else {
                parts += if (start == end) "$start" else "$start–$end"
                start = week
                end = week
            }
        }
        parts += if (start == end) "$start" else "$start–$end"
        return parts.joinToString("、") + " 周"
    }
}
