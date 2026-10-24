package cn.local.bitcourse.data

import cn.local.bitcourse.model.Course
import java.security.MessageDigest

/** Derived display records only. The imported schedule and its cache remain unchanged. */
object CourseDisplay {
    private data class Details(
        val name: String,
        val teacher: String,
        val room: String,
        val campus: String,
        val day: Int,
    )

    /**
     * Join adjacent fragments for each actual teaching week, then collect equal ranges' weeks.
     * Different source ids can denote different course codes, which Course does not expose.
     * If such records overlap, preserve their entire connected group for the conflict chooser.
     */
    fun mergePeriods(courses: List<Course>): List<Course> {
        val weeksByDisplayRecord = linkedMapOf<Course, MutableSet<Int>>()
        val groups = courses.distinct().groupBy {
            Details(it.name, it.teacher, it.room, it.campus, it.day)
        }
        for ((details, fragments) in groups) {
            val displayIds = mutableMapOf<Pair<Int, Int>, String>()
            val byWeek = sortedMapOf<Int, MutableList<Course>>()
            for (course in fragments) {
                for (week in course.weeks.distinct()) {
                    byWeek.getOrPut(week) { mutableListOf() }.add(course)
                }
            }
            for ((week, records) in byWeek) {
                val sorted = records.sortedWith(compareBy<Course> { it.startPeriod }
                    .thenBy { it.endPeriod }.thenBy { it.id })
                var connected = mutableListOf<Course>()
                var end = 0

                fun emitConnectedGroup() {
                    if (connected.isEmpty()) return
                    val endBySource = mutableMapOf<String, Int>()
                    val ambiguous = connected.any { course ->
                        val overlapsOtherSource = endBySource.any { (id, previousEnd) ->
                            id != course.id && previousEnd >= course.startPeriod
                        }
                        endBySource[course.id] = maxOf(endBySource[course.id] ?: 0, course.endPeriod)
                        overlapsOtherSource
                    }
                    val displayRecords = if (ambiguous) connected else {
                        val start = connected.first().startPeriod
                        listOf(connected.first().copy(
                            id = displayIds.getOrPut(start to end) { displayId(details, start, end) },
                            startPeriod = start,
                            endPeriod = end,
                        ))
                    }
                    for (record in displayRecords) {
                        // The key keeps source ids in ambiguous groups; equal visible text alone
                        // must not deduplicate two distinct, overlapping courses.
                        weeksByDisplayRecord.getOrPut(record.copy(weeks = emptyList())) {
                            sortedSetOf()
                        }.add(week)
                    }
                }

                for (record in sorted) {
                    if (connected.isNotEmpty() && record.startPeriod > end + 1) {
                        emitConnectedGroup()
                        connected = mutableListOf()
                    }
                    if (connected.isEmpty()) end = record.endPeriod else end = maxOf(end, record.endPeriod)
                    connected.add(record)
                }
                emitConnectedGroup()
            }
        }
        return weeksByDisplayRecord.map { (course, weeks) -> course.copy(weeks = weeks.sorted()) }
            .sortedWith(compareBy<Course> { it.day }.thenBy { it.startPeriod }.thenBy { it.endPeriod }
                .thenBy { it.name }.thenBy { it.teacher }.thenBy { it.room }.thenBy { it.campus }
                .thenBy { it.id })
    }

    private fun displayId(details: Details, start: Int, end: Int): String {
        val fields = listOf(details.name, details.teacher, details.room, details.campus,
            details.day.toString(), start.toString(), end.toString())
        val canonical = fields.joinToString("") { "${it.length}:$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "display-" + digest.joinToString("") { "%02x".format(it) }
    }
}
