package kr.carlog.pleos.vehicle

import ai.pleos.playground.vehicle.api.model.capability.AccessType
import org.junit.Assert.*
import org.junit.Test

class AccessTypeTest {
    @Test fun onlyReadableCapabilitiesAllowAGetter() {
        assertTrue(AccessType.READ.readable())
        assertTrue(AccessType.READ_WRITE.readable())
        assertFalse(AccessType.WRITE.readable())
        assertFalse(AccessType.NONE.readable())
    }
}
