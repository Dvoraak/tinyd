package io.github.dvoraak.tinyd

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Application-scoped event bus between the ViewModel and the
 * [CompressionService]. The service lives independently of the Activity /
 * ViewModel lifecycle — it can outlive a configuration change, survive
 * onPause, and Doze can't suspend its process — so we can't simply pass it
 * a reference to the ViewModel. Instead the ViewModel writes its compress
 * progress into [notificationState] and the service collects from it to
 * update the persistent notification. The cancel button in the
 * notification posts to [cancelRequested]; the ViewModel collects and
 * calls cancelCompression() on its own scope.
 *
 * Plain singletons are deliberate here: the lifetime is the process, both
 * producer and consumer are intra-process, and there's no benefit to
 * dependency-injecting this. If the process dies the encode is dead too —
 * persisting events would be pointless.
 */
/**
 * Process-scoped holder for [CompressorUiState]. Android can (and does)
 * destroy our MainActivity while the foreground service keeps the process
 * alive — once that happens, a per-Activity ViewModel would lose its
 * batch queue, the in-place finalization list, and the pending delete
 * dialog request. When the user taps the notification a fresh Activity
 * spawns; with a per-Activity ViewModel it would see an empty state and
 * the recovery pass would scoop up the in-flight IS_PENDING files into
 * Movies/Compressor/Recovered_* before the delete dialog ever runs.
 *
 * Putting the state on this singleton means the new Activity's new
 * ViewModel inherits everything: LaunchedEffect(pendingDeleteUris)
 * fires, the delete dialog appears, finalizeInPlace runs, and the
 * recovery pass is guarded against running concurrently with that flow.
 */
object CompressionStore {
    val state: kotlinx.coroutines.flow.MutableStateFlow<CompressorUiState> =
        kotlinx.coroutines.flow.MutableStateFlow(CompressorUiState())
}

object CompressionEvents {

    data class NotificationState(
        /** True while any encoding work is in flight or scheduled. False ends the service. */
        val active: Boolean = false,
        /** Display text shown under the title, e.g. "Compressing PXL_xxx.mp4". */
        val text: String = "",
        /**
         * Compact "1 / 3" style indicator surfaced into the notification's subText
         * so it shows in the collapsed view as well as the expanded one. Empty
         * for non-batch flows.
         */
        val subText: String = "",
        /**
         * 0..100 for a determinate progress bar. -1 = indeterminate spinner
         * (codec init / inter-item). NO_PROGRESS = hide the bar entirely
         * (final phases where a bar would lie about ongoing work).
         */
        val progressPercent: Int = NO_PROGRESS,
    ) {
        companion object {
            const val NO_PROGRESS = -2
        }
    }

    /**
     * Emitted by the notification's Cancel button. Buffer 1 / drop-oldest so
     * a stuck collector can't lose user intent.
     */
    val cancelRequested = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
}

/**
 * Single source of truth for the foreground notification's rendering — both
 * the ViewModel-side state mirror and the Service consume this. Keeping the
 * mapping in one place stops the notification text from going stale: there's
 * no second buffer to update.
 */
fun deriveNotificationState(s: CompressorUiState, context: android.content.Context): CompressionEvents.NotificationState {
    val active = s.isCompressing ||
        s.batchActive ||
        s.pendingFinalizations.isNotEmpty() ||
        s.pendingDeleteUris.isNotEmpty()
    if (!active) return CompressionEvents.NotificationState(active = false)
    val name = s.originalName ?: ""
    val subText = if (s.isBatch) "${s.batchPosition} / ${s.batchTotal}" else ""

    val (text, pct) = when {
        s.isCompressing -> {
            val txt = if (name.isNotBlank()) {
                context.getString(R.string.notif_text_compressing, name)
            } else {
                context.getString(R.string.notif_default_text)
            }
            val p = if (s.progress > 0f) (s.progress * 100f).toInt().coerceIn(0, 100) else -1
            txt to p
        }
        // batchActive must be checked BEFORE the pending lists. Between batch items
        // (item N just saved, item N+1 not started yet), pendingFinalizations is
        // non-empty with a count of the in-flight items so far — but the batch is
        // very much still running. Falling into the "Replacing N" branch here would
        // show a misleading mid-batch number that only matches the partial count
        // (e.g. "Replacing 2 of 3"). The pending-lists branch only applies once
        // batchActive is false, which is when the encode is genuinely done.
        s.batchActive -> {
            val txt = if (name.isNotBlank()) {
                context.getString(R.string.notif_text_compressing, name)
            } else context.getString(R.string.notif_default_text)
            txt to -1
        }
        // Encoding's done — both the awaiting-confirmation phase and the
        // brief sub-second rename phase get the same wording so the
        // notification doesn't flicker between two different sentences
        // when the user taps Allow. NO_PROGRESS removes the bar entirely
        // because a bar of any kind reads as "still working", and there's
        // nothing left to compress.
        s.pendingDeleteUris.isNotEmpty() || s.pendingFinalizations.isNotEmpty() -> {
            val count = if (s.pendingDeleteUris.isNotEmpty()) s.pendingDeleteUris.size
                        else s.pendingFinalizations.size
            context.getString(R.string.notif_text_replacing, count) to
                CompressionEvents.NotificationState.NO_PROGRESS
        }
        else -> context.getString(R.string.notif_default_text) to -1
    }
    return CompressionEvents.NotificationState(
        active = true,
        text = text,
        subText = subText,
        progressPercent = pct,
    )
}
