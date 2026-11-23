package net.prototux.aare.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.prototux.aare.Storage

/**
 * A move of maps, speech models or music under way, held outside the pages (23.11.2026: "Er zeigt den laufenden Umzug beim
 * Scrollen sowieso nicht mehr an"): a card scrolled out of the list or a page left and opened again shows the progress
 * again, and the move is not tied to the card. One move at a time.
 */
internal object StorageMove {
    /** Phase, bytes done, bytes in all; null when none runs. */
    val progress = mutableStateOf<Triple<Storage.Phase, Long, Long>?>(null)
    val error = mutableStateOf<String?>(null)
    /** Counts finished moves: the pages read their numbers again. */
    val finished = mutableIntStateOf(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts [work] unless a move runs already; [failed] words an error. */
    fun start(total: Long, failed: (Throwable) -> String, after: () -> Unit = {}, work: ((Storage.Phase, Long, Long) -> Unit) -> Unit): Boolean {
        if (progress.value != null) return false
        progress.value = Triple(Storage.Phase.COPY, 0L, total); error.value = null
        scope.launch {
            var shown = 0L
            val r = runCatching { work { ph, d, a ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - shown > 500 || d == a) { shown = now; progress.value = Triple(ph, d, a) }
            } }
            error.value = r.exceptionOrNull()?.let(failed)
            progress.value = null
            finished.intValue++
            runCatching(after)
        }
        return true
    }

    /** A failed move in the device's language: the reason of [Storage.MoveFailed], else the error's own text. */
    fun describe(context: android.content.Context, e: Throwable): String {
        val why = (e as? Storage.MoveFailed)?.why?.let { w -> context.getString(when (w) {
            Storage.MoveFailed.Why.NO_ROOM -> net.prototux.aare.R.string.storage_reason_room
            Storage.MoveFailed.Why.TOO_LARGE -> net.prototux.aare.R.string.storage_reason_fat32
            Storage.MoveFailed.Why.NOT_WRITABLE -> net.prototux.aare.R.string.storage_reason_not_writable
            Storage.MoveFailed.Why.VERIFY -> net.prototux.aare.R.string.storage_reason_verify
        }) }
        return context.getString(net.prototux.aare.R.string.storage_move_failed, why ?: e.message ?: e.javaClass.simpleName)
    }
}
