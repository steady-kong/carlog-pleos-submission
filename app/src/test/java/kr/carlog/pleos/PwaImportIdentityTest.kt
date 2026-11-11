package kr.carlog.pleos

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PwaImportIdentityTest {
    private val dataset = UUID.fromString("a039a45c-c91d-4b3f-8c39-7576a6301e00")

    @Test fun fixedVectorsMatchRfc4122VersionFive() {
        assertEquals("784ef5c2-ac24-5f78-8a03-957f0d80d4fd", pwaRecordId(dataset, PwaRecordTable.FUEL, "source-id").toString())
        assertEquals("0d53d771-c869-5986-9b19-92284bb54d55", pwaRecordId(dataset, PwaRecordTable.MAINTENANCE, "source-id").toString())
        assertEquals("6f54c3e2-30f3-5f4a-a7ec-52100e637a82", pwaRecordId(dataset, PwaRecordTable.EXPENSE, "source-id").toString())
        assertEquals("dbe91b7d-af61-5e03-91d3-b13f3b79865f", pwaRecordId(dataset, PwaRecordTable.ODOMETER, "source-id").toString())
    }

    @Test fun sameSourceIsStableAndDifferentSourcesStayDistinct() {
        val id = pwaRecordId(dataset, PwaRecordTable.FUEL, "기록/1")
        assertEquals(id, pwaRecordId(dataset, PwaRecordTable.FUEL, "기록/1"))
        assertEquals(5, id.version())
        assertEquals(2, id.variant())
        assertNotEquals(id, pwaRecordId(dataset, PwaRecordTable.EXPENSE, "기록/1"))
        assertNotEquals(id, pwaRecordId(UUID.fromString("6d48f531-3fbb-4609-b424-d0d6f16d23ab"), PwaRecordTable.FUEL, "기록/1"))
        assertNotEquals(id, pwaRecordId(dataset, PwaRecordTable.FUEL, "기록/2"))
    }

    @Test fun malformedOriginalIdentifiersCannotBeSilentlyReplaced() {
        listOf("", "  ", "\uD800").forEach { invalid ->
            try { pwaRecordId(dataset, PwaRecordTable.FUEL, invalid); fail("invalid source identifier") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
