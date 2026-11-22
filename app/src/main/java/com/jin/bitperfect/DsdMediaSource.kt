package com.jin.bitperfect

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

/**
 * A deliberately inert MediaSource for DSF/DFF.
 *
 * ExoPlayer has no DSF/DFF extractor. Feeding it the real URI makes
 * Stagefright/MediaCodec attempt a decode and can crash inside the platform
 * codec process. The DSD audio is produced by AudioPlaybackService's native
 * DoP/U32 engine, so ExoPlayer only needs a valid timeline/media item and a
 * period that never becomes prepared. Keeping it buffering avoids touching
 * MediaCodec while the wrapper reports the real DSD playback state.
 */
@UnstableApi
class DsdMediaSource(
    private val mediaItem: MediaItem,
    private val durationUs: Long
) : BaseMediaSource() {

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(transferListener: TransferListener?) {
        val safeDuration = durationUs.coerceAtLeast(1L)
        refreshSourceInfo(
            SinglePeriodTimeline(
                safeDuration,
                true,
                false,
                false,
                null,
                mediaItem
            )
        )
    }

    override fun maybeThrowSourceInfoRefreshError() {
        // No network or file loading is performed here.
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod = PendingPeriod()

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        // Nothing to release.
    }

    override fun releaseSourceInternal() {
        // Nothing to release.
    }

    private class PendingPeriod : MediaPeriod {
        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            // Intentionally never call onPrepared(). The native DSD engine is
            // the source of truth for playback state and position.
        }

        override fun maybeThrowPrepareError() {
            // No preparation error.
        }

        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY

        override fun selectTracks(
            selections: Array<ExoTrackSelection>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream>,
            streamResetFlags: BooleanArray,
            positionUs: Long
        ): Long = positionUs

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
            // Nothing is buffered.
        }

        override fun readDiscontinuity(): Long = C.TIME_UNSET

        override fun seekToUs(positionUs: Long): Long = positionUs

        override fun getAdjustedSeekPositionUs(
            positionUs: Long,
            seekParameters: SeekParameters
        ): Long = positionUs

        override fun getBufferedPositionUs(): Long = C.TIME_UNSET

        override fun getNextLoadPositionUs(): Long = C.TIME_UNSET

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false

        override fun isLoading(): Boolean = false

        override fun reevaluateBuffer(positionUs: Long) {
            // Nothing to reevaluate.
        }
    }
}
