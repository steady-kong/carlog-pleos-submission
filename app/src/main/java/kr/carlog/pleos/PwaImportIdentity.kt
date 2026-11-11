package kr.carlog.pleos

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Tables that share the automotive RecordEntity key space. */
enum class PwaRecordTable(val wireName: String) {
    FUEL("fuelLogs"), MAINTENANCE("maintenanceLogs"), EXPENSE("expenseLogs"), ODOMETER("odometerReadings")
}

/** Immutable namespace for the pwa-v1 import identity contract. */
val PWA_RECORD_NAMESPACE: UUID = UUID.fromString("c7e2b663-29fa-48ab-aac0-0c62a2a1074a")

/**
 * Stable UUID v5 for a source dataset, PWA table and original ID.
 * This assigns an identity only; it does not import, merge, or validate a record.
 */
fun pwaRecordId(datasetId: UUID, table: PwaRecordTable, originalId: String): UUID {
    require(originalId.isNotBlank() && hasValidUnicodeScalars(originalId)) { "Invalid source ID" }
    val namespaceBytes = ByteBuffer.allocate(16).putLong(PWA_RECORD_NAMESPACE.mostSignificantBits)
        .putLong(PWA_RECORD_NAMESPACE.leastSignificantBits).array()
    val name = "pwa-v1/$datasetId/${table.wireName}/$originalId".toByteArray(Charsets.UTF_8)
    val hash = MessageDigest.getInstance("SHA-1").apply { update(namespaceBytes); update(name) }.digest()
    hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
    hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
    return ByteBuffer.wrap(hash).let { UUID(it.long, it.long) }
}
