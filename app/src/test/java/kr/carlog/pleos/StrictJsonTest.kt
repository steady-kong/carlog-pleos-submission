package kr.carlog.pleos

import org.junit.Assert.*
import org.junit.Test

class StrictJsonTest {
    @Test fun schemaVersionUsesExactDecimalValueWithoutBreakingEquivalentNumbers() {
        fun example(name: String) = requireNotNull(javaClass.getResourceAsStream("/$name")).bufferedReader().use { it.readText() }
        fun version(text: String, token: String) = text.replaceFirst(Regex("\\\"schemaVersion\\\"\\s*:\\s*[12]"), "\"schemaVersion\": $token")
        val event = example("event-v1.json")
        val vehicle = example("vehicle-records-v1.json")
        val backup = "{\"app\":\"carlog\",\"schemaVersion\":1,\"data\":{}}"
        parseEventJson(version(event, "1.0"))
        parseVehicleManagementJson(version(vehicle, "1e0"))
        previewPwaBackup(version(backup, "1.000"))
        try { previewPwaBackup(version(backup, "1." + "0".repeat(256))); fail("oversized version token") }
        catch (_: IllegalArgumentException) { }
        for (action in listOf<() -> Unit>(
            { parseEventJson(version(event, "1.0000000000000000001")); Unit },
            { parseVehicleManagementJson(version(vehicle, "1.0000000000000000001")); Unit },
            { previewPwaBackup(version(backup, "1.0000000000000000001")); Unit },
        )) {
            try { action(); fail("distinct version must be rejected") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun duplicateNamesAreRejectedEvenWhenEscapedOrNested() {
        val invalid = listOf("{\"id\":1,\"id\":2}", "{\"samples\":[{\"value\":1,\"value\":2}]}",
            "{\"a\":1,\"\\u0061\":2}")
        invalid.forEach { json ->
            try { requireUnambiguousJson(json); fail("duplicate field should fail") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun validRepeatedNamesInSeparateObjectsRemainAllowed() {
        requireUnambiguousJson("{\"memo\":\"\\\"id\\\": x\",\"samples\":[{\"value\":1},{\"value\":2}]}")
    }

    @Test fun unpairedSurrogatesAreRejectedInFieldNamesAndStringValues() {
        requireUnambiguousJson("{\"🚗\":\"\\uD83D\\uDE97\"}")
        val invalid = listOf("{\"memo\":\"\\uD800\"}", "{\"memo\":\"\\uDC00\"}",
            "{\"\\uD800\":1}", "{\"memo\":\"\\uD800x\"}")
        invalid.forEach { json ->
            try { requireUnambiguousJson(json); fail("unpaired surrogate should fail") }
            catch (_: IllegalArgumentException) { }
        }
        assertFalse(hasValidUnicodeScalars("\uD800"))
        assertFalse(hasValidUnicodeScalars("\uDC00"))
        assertTrue(hasValidUnicodeScalars("🚗"))
    }

    @Test fun malformedTrailingOrDeepJsonIsRejected() {
        val invalid = listOf("{\"a\":1,}", "{\"a\":1} {}", "{\"a\":/*comment*/1}", "[".repeat(66) + "0" + "]".repeat(66))
        invalid.forEach { json ->
            try { requireUnambiguousJson(json); fail("invalid JSON should fail") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
