package cn.local.bitcourse.portal

/** Structured progress for the current sync; independent of status wording. */
enum class SyncPhase {
    CONNECTING,
    AWAITING_LOGIN,
    READING_TERM,
    READING_COURSES,
    READING_CALENDAR,
    /** Set by the activity while persisting a completed sync, never by SchoolPortal. */
    SAVING,
}
