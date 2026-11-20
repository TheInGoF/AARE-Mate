package net.prototux.aare.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Clock times as the region writes them (18.11.2026: "Achte bei den Zeiten auf regionale Zeiten, 7:38 AM gibt es in
 * Deutschland nicht"): 24 hours where the device says so or its language is German, "h:mm a" only where the device is
 * set to 12 hours. One place for every clock time Mate shows (arrival, trip plan, route strip, the car's cluster).
 */
object Clock {
    fun h24(): Boolean {
        val l = Locale.getDefault()
        if (l.language == "de" || l.country in setOf("DE", "AT", "CH")) return true
        return runCatching { android.text.format.DateFormat.is24HourFormat(net.prototux.aare.AareApp.instance) }.getOrDefault(true)
    }

    fun format(millis: Long, zone: TimeZone? = null): String =
        SimpleDateFormat(if (h24()) "HH:mm" else "h:mm a", Locale.getDefault()).apply { zone?.let { timeZone = it } }.format(Date(millis))
}
