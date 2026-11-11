package kr.carlog.pleos.speech

import android.content.Context
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode

/** Compile-only probe. No recognition is started and the app does not depend on this module. */
internal fun createOnDeviceSpeechProbe(context: Context): SpeechToText =
    SpeechToText(context.applicationContext, Mode.ON_DEVICE)
