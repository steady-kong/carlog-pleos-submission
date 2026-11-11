package kr.carlog.pleos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandTest {
    @Test fun acceptsOnlyThePrimaryExplicitCommand() {
        assertTrue(isEventVoiceCommand(listOf("이 순간 기록")))
        assertTrue(isEventVoiceCommand(listOf(" 이벤트\t기록 ")))
        assertFalse(isEventVoiceCommand(emptyList()))
        assertFalse(isEventVoiceCommand(listOf("이벤트 기록하지 마")))
        assertFalse(isEventVoiceCommand(listOf("다른 말", "이벤트 기록")))
    }

    @Test fun recognitionMapsCommandsAndSpeechToMemo() {
        assertEquals("", eventVoiceMemo(listOf("이 순간 기록")))
        assertEquals("", eventVoiceMemo(listOf(" 이벤트\t기록 ")))
        assertEquals("앞쪽에서 소리가 나요", eventVoiceMemo(listOf("  앞쪽에서 소리가 나요  ", "이벤트 기록")))
        assertNull(eventVoiceMemo(emptyList()))
        assertNull(eventVoiceMemo(listOf(" \n\t", "대체 후보")))
    }
}
