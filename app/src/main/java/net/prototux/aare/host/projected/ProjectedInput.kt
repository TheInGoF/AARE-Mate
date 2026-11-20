package net.prototux.aare.host.projected

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Text entry for a projected app (Google Maps' search field, O97): Maps asks its host for a keyboard (callback
 * tx 16, with the field's EditorInfo and its own IProxyInputConnection). Mate shows its own car keyboard instead
 * and hands the finished text to Maps as a search (geo:0,0?q=…), which opens Maps' result list.
 */
object ProjectedInput {
    /** The hint of the field waiting for text, or null while no keyboard is wanted. */
    var hint by mutableStateOf<String?>(null)
        private set
    var text by mutableStateOf("")

    fun request(fieldHint: String) { text = ""; hint = fieldHint }
    fun close() { hint = null }

    fun submit() {
        val q = text.trim()
        close()
        if (q.isNotEmpty()) ProjectedHost.deliver(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(q))))
    }
}
