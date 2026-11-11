package kr.carlog.pleos

import android.content.Intent
import android.content.Context
import android.content.ActivityNotFoundException
import android.net.Uri

/** Only a valid same-event, same-time coordinate pair may leave the app. */
fun eventMapIntent(latitude: SampleEntity, longitude: SampleEntity): Intent {
    require(isValidEventLocation(latitude, longitude)) { "Invalid event location" }
    val coordinates = "${latitude.value},${longitude.value}"
    return Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=$coordinates"))
}

fun openEventMap(context: Context, latitude: SampleEntity, longitude: SampleEntity): Boolean {
    val intent = eventMapIntent(latitude, longitude)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
