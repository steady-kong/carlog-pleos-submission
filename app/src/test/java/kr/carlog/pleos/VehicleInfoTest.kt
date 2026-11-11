package kr.carlog.pleos

import org.junit.Assert.*
import org.junit.Test

class VehicleInfoTest {
    @Test fun optionalMetadataRequiresRecognizedFuelAndValidYear() {
        assertTrue(validVehicleInfo("차량", 0.0, null, null))
        vehicleFuelTypes.forEach { (code, _) -> assertTrue(validVehicleInfo("차량", 1.0, 2024, code)) }
        assertFalse(validVehicleInfo("차량", 1.0, 1800, "ev"))
        assertFalse(validVehicleInfo("차량", 1.0, 2024, "unknown"))
        assertFalse(validVehicleInfo("차량", Double.NaN, null, null))
        assertFalse(validVehicleInfo(" ", 1.0, null, null))
    }
}
