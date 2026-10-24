package cn.local.bitcourse.model

data class Course(
    val id: String,
    val name: String,
    val teacher: String = "",
    val room: String = "",
    val campus: String = "",
    val day: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: List<Int>,
)

data class ParsedSchedule(
    val term: String,
    val courses: List<Course>,
    val weekOffset: Int?,
    val warnings: List<String>,
)
