package kr.carlog.pleos.location

import ai.pleos.playground.location.fused.model.LocationData

/** SDK timestamp semantics are unverified; keep it separate from the app's receipt time. */
internal data class LocationObservation(
    val latitude: Double,
    val longitude: Double,
    val sdkTimestampMillis: Long,
    val receivedAtMillis: Long,
)

internal fun locationObservation(data: LocationData?, receivedAtMillis: Long): LocationObservation? {
    if (data == null || !data.latitude.isFinite() || data.latitude !in -90.0..90.0 ||
        !data.longitude.isFinite() || data.longitude !in -180.0..180.0) return null
    return LocationObservation(data.latitude, data.longitude, data.timestampMillis, receivedAtMillis)
}
