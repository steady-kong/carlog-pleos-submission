package kr.carlog.pleos.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechCommandTest {
    @Test fun acceptsOnlyExplicitFinalCommands() {
        assertEquals(SpeechCommand.MARK_EVENT, parseSpeechCommand(" 카로그   이 순간 기록 ", true))
        assertEquals(SpeechCommand.MARK_EVENT, parseSpeechCommand("이 순간 기록", true))
        assertNull(parseSpeechCommand("이 순간 기록", false))
        assertNull(parseSpeechCommand("이 순간 기록하지 마", true))
        assertNull(parseSpeechCommand("이 순간 기록해줘", true))
        assertNull(parseSpeechCommand("", true))
    }
}
