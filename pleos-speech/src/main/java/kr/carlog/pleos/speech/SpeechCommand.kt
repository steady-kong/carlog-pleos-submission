package kr.carlog.pleos.speech

/** Only final, explicit phrases may be handed to a future user-initiated capture flow. */
internal enum class SpeechCommand { MARK_EVENT }

internal fun parseSpeechCommand(text: String, isFinal: Boolean): SpeechCommand? {
    if (!isFinal) return null
    val normalized = text.trim().replace(Regex("\\s+"), " ")
    return when (normalized) {
        "이 순간 기록", "카로그 이 순간 기록" -> SpeechCommand.MARK_EVENT
        else -> null
    }
}
