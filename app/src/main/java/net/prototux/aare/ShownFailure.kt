package net.prototux.aare

import android.content.Context
import androidx.annotation.StringRes
import java.io.IOException

/**
 * A failure with a text for the screen in the device's language ([text] with [args]); the message itself is English
 * and goes to the log only. Refusals of IDmate's read API, Plex's errors, a move of maps that stopped.
 */
open class ShownFailure(log: String, @StringRes val text: Int, vararg val args: Any?) : IOException(log) {
    companion object {
        /** Why it failed, in the device's language; for any other error its message, else its kind. */
        @JvmStatic
        fun text(c: Context, e: Throwable): String =
            if (e is ShownFailure) c.getString(e.text, *e.args) else e.message ?: e.javaClass.simpleName
    }
}
