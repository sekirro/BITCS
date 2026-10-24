package cn.local.bitcourse.data

import android.content.Context
import android.util.AtomicFile
import cn.local.bitcourse.model.SchoolParser
import com.google.gson.Gson
import java.io.File
import java.time.LocalDate

/** Only timetable fields are stored. School credentials remain in the system WebView cookie store. */
class ScheduleStore(context: Context) {
    private val directory = File(context.filesDir, "schedules").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("course_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    var currentTerm: String
        get() = preferences.getString("current_term", "").orEmpty().takeIf(SchoolParser::isValidTerm).orEmpty()
        set(value) {
            val term = SchoolParser.validateTerm(value)
            check(preferences.edit().putString("current_term", term).commit()) { "无法保存学期，请检查手机存储空间" }
        }

    var portalMode: String
        get() = preferences.getString("portal_mode", "DIRECT").orEmpty()
        set(value) { preferences.edit().putString("portal_mode", value).apply() }

    var automaticSync: Boolean
        get() = preferences.getBoolean("automatic_sync", true)
        set(value) { preferences.edit().putBoolean("automatic_sync", value).apply() }

    var followSchoolTerm: Boolean
        get() = preferences.getBoolean("follow_school_term", true)
        set(value) { preferences.edit().putBoolean("follow_school_term", value).apply() }

    var hasSynced: Boolean
        get() = preferences.getBoolean("has_synced", false)
        set(value) { preferences.edit().putBoolean("has_synced", value).apply() }

    fun manualMonday(term: String): LocalDate? = runCatching {
        preferences.getString("monday_${SchoolParser.validateTerm(term)}", null)?.let(LocalDate::parse)
            ?.takeIf { it.dayOfWeek == java.time.DayOfWeek.MONDAY }
    }.getOrNull()

    fun setManualMonday(term: String, date: LocalDate) {
        SchedulePolicy.resolveMonday(null, null, date)
        check(preferences.edit().putString("monday_${SchoolParser.validateTerm(term)}", date.toString()).commit()) {
            "无法保存日期，请检查手机存储空间"
        }
    }

    fun load(term: String): SavedSchedule? {
        if (!SchoolParser.isValidTerm(term)) return null
        val atomic = AtomicFile(File(directory, "$term.json"))
        return runCatching {
            atomic.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                gson.fromJson(reader, SavedSchedule::class.java)?.takeIf { saved ->
                    saved.term == term && saved.syncedAtMillis > 0 && saved.warnings.all { it.length <= 4000 } &&
                        saved.courses.all { course ->
                            course.id.isNotBlank() && course.name.isNotBlank() && course.teacher.length <= 4000 &&
                                course.room.length <= 4000 && course.campus.length <= 4000 && course.day in 1..7 &&
                                course.startPeriod in 1..30 && course.endPeriod in course.startPeriod..30 &&
                                course.weeks.isNotEmpty() && course.weeks.all { it in 1..60 }
                        }
                }
            }
        }.getOrNull()
    }

    fun save(schedule: SavedSchedule) {
        val term = SchoolParser.validateTerm(schedule.term)
        val atomic = AtomicFile(File(directory, "$term.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(gson.toJson(schedule).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
