@file:JvmName("Open")

package net.prototux.aare.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import net.prototux.aare.R

/** Mate's own page in the system's app settings (permissions, notifications). */
internal fun appDetails(ctx: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))

/**
 * Opens a settings screen or a link. Some phones lack the screen (Go builds and OEM ROMs drop some)
 * or have no browser: then [fallback] opens instead, or a short message, rather than a crash. No
 * resolveActivity() check first: package visibility hides browsers from it without a <queries> entry.
 */
internal fun Context.startSafely(intent: Intent, fallback: Intent? = null) {
    val opened = listOfNotNull(intent, fallback).any { runCatching { startActivity(it) }.isSuccess }
    if (!opened) Toast.makeText(this, R.string.setup_nothing_to_open, Toast.LENGTH_SHORT).show()
}
