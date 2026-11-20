package net.prototux.aare

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * The one door to HiddenApiBypass. It keeps one set of prefixes and hands all of them to ART on every call; two threads
 * doing so at once (the audio thread and the guidance at start-up) raced inside ART's setHiddenApiExemptions and crashed
 * the app with a null pointer (16.11.2026, 6 s after a start, thread "aare-audio-…"). One lock for all callers.
 */
object HiddenApi {
    @Synchronized
    fun exempt(vararg prefixes: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P || HiddenApiBypass.addHiddenApiExemptions(*prefixes)
}
