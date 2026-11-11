package kr.carlog.pleos.location

import ai.pleos.playground.location.fused.FusedLocation
import android.content.Context

/** Compile-only probe; the app does not depend on this module or request location. */
internal fun createLocationSdkProbe(context: Context): FusedLocation = FusedLocation(context.applicationContext)

internal fun supportsLocationSdk(sdk: FusedLocation): Boolean = sdk.isSupported()
