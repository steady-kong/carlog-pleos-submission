package kr.carlog.pleos

/** Only the primary recognition result is considered. */
fun isEventVoiceCommand(candidates: List<String>): Boolean = candidates.firstOrNull()
    ?.trim()?.replace(Regex("\\s+"), "") in setOf("이순간기록", "이벤트기록")

/** Null means no usable result; an explicit record command creates an empty memo. */
fun eventVoiceMemo(candidates: List<String>): String? {
    val spoken = candidates.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (isEventVoiceCommand(listOf(spoken))) "" else spoken
}
