package cn.local.bitcourse.ui

import cn.local.bitcourse.model.Course

/** A connected group of inclusive period intervals, rendered in one day column. */
internal data class TimetableBlock(
    val startPeriod: Int,
    val endPeriod: Int,
    val courses: List<Course>,
)

internal object TimetableLayout {
    fun blocksForDay(courses: List<Course>, week: Int, day: Int): List<TimetableBlock> {
        val visible = courses.filter { it.day == day && week in it.weeks }
            .sortedWith(compareBy<Course> { it.startPeriod }.thenBy { it.endPeriod }
                .thenBy { it.name }.thenBy { it.id })
        val blocks = mutableListOf<TimetableBlock>()
        var members = mutableListOf<Course>()
        var firstPeriod = 0
        var lastPeriod = 0

        fun finishBlock() {
            if (members.isNotEmpty()) {
                blocks += TimetableBlock(firstPeriod, lastPeriod, members.toList())
            }
        }

        for (course in visible) {
            // Periods are inclusive: 1–2 and 3–4 are separate, while 1–2 and 2–3 overlap.
            if (members.isEmpty() || course.startPeriod > lastPeriod) {
                finishBlock()
                members = mutableListOf(course)
                firstPeriod = course.startPeriod
                lastPeriod = course.endPeriod
            } else {
                members += course
                lastPeriod = maxOf(lastPeriod, course.endPeriod)
            }
        }
        finishBlock()
        return blocks
    }
}
