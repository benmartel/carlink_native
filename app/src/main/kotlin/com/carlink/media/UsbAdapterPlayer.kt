package com.carlink.media

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.carlink.BuildConfig
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Media3 [Player] that mirrors phone playback state coming from the Carlinkit USB adapter.
 *
 * This is a "mirror player" — no audio decode or playback happens locally. The connected
 * phone plays media over the USB link; the adapter relays metadata + transport state frames
 * (CarPlay / Android Auto MEDIA_DATA messages), and AAOS Media Center + steering-wheel
 * control gestures are routed back to the adapter as USB commands via [Callback].
 *
 * Why [SimpleBasePlayer]?
 * Media3's [Player] interface has ~40 abstract methods. [SimpleBasePlayer] reduces this to
 * one required override — [getState] — plus opt-in `handle*` methods for each advertised
 * [Player.Commands] action. It is the officially documented base class for non-playback /
 * state-mirror [Player] implementations (see CompositionPlayer, FakePlayer as references
 * in the Media3 source tree).
 *
 * Backwards compatibility with GM AAOS:
 * When wrapped by [androidx.media3.session.MediaSession], Media3 internally registers a
 * platform-level `android.media.session.MediaSession` so legacy observers (e.g. GM's
 * GMCarMediaService at `/system/priv-app/GMCarMediaService/`, which uses
 * `android.media.session.MediaSessionManager.getActiveSessions(ComponentName)` per
 * firmware-verified analysis) see this session identically to a pre-migration
 * `MediaSessionCompat`. The ClusterService → IClusterHmi widget pipeline remains intact.
 *
 * Threading:
 * - The player is pinned to [Looper.getMainLooper] at construction. Per Media3 contract
 *   (see `Player.getApplicationLooper`), all `handle*` callbacks run on that looper and all
 *   [getState] / [invalidateState] calls must be issued from that looper.
 * - External callers (USB read thread via CarlinkManager.handleMessage) call
 *   [updatePlaybackState] and [updateMediaMetadata]. These are thread-safe entry points
 *   that route the mutation + [invalidateState] back to the main looper via [Handler.post],
 *   so `invalidateState` never fires from the wrong thread (which would throw
 *   IllegalStateException per SimpleBasePlayer's javadoc).
 * - Internal state fields are guarded by [stateLock] so a post from the USB thread and a
 *   framework-driven [getState] on the main thread see consistent snapshots.
 *
 * Advertised commands:
 * Minimum set required by AAOS Media Center + typical steering-wheel routing: PLAY_PAUSE,
 * STOP, SEEK_TO_NEXT/PREVIOUS (both the "command" and "media-item" variants), SET_MEDIA_ITEM,
 * PREPARE, plus the three GET_* commands needed for controllers to read back metadata.
 * Intra-track seeking is NOT advertised: the phone is the authority for playback position;
 * we only report what it tells us.
 */
@UnstableApi
class UsbAdapterPlayer(
    private val callback: Callback,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    /**
     * Routes MediaSession control gestures to the USB adapter. Implementation is expected
     * to enqueue a single-byte command frame on the adapter write path.
     */
    interface Callback {
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onSkipToNext()
        fun onSkipToPrevious()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    // Guarded by stateLock. Written from the main thread after postToMain; read from the
    // main thread inside getState(). Cross-thread writes are queued through mainHandler.
    //
    // playbackState maps Media3 [Player.State] values:
    //   STATE_IDLE      — stopped / no source (AAOS marks session "inactive")
    //   STATE_BUFFERING — connecting / waiting for data
    //   STATE_READY     — has source, can play (AAOS marks session "active")
    //   STATE_ENDED     — playback finished
    // In Media3 there is no explicit MediaSession.setActive — AAOS / Media Center
    // derive the session's active-for-switcher state from this field, so it must
    // be set accurately for CONNECTING / STREAMING / DISCONNECTED transitions.
    private var playbackState: Int = Player.STATE_IDLE
    private var isPlaying: Boolean = false
    private var positionMs: Long = 0L
    private var durationMs: Long = C.TIME_UNSET
    private var mediaMetadata: MediaMetadata = MediaMetadata.EMPTY

    // Projection-state gate. When false, [getState] returns an EMPTY playlist with
    // STATE_IDLE — this flips the platform android.media.session.MediaSession's
    // `isActive` bit to false (Media3 derives active from `!timeline.isEmpty() ||
    // playbackState != STATE_IDLE`), which means AAOS consumers (CarMediaService,
    // CarLauncher Media card, ClusterMediaActivity) treat Carlink as NOT a valid
    // playback-eligible source. When true, [getState] returns the current 1-item
    // playlist reflecting live metadata + playback state.
    //
    // Starts false — at process start / cold launch / adapter-only-no-phone, we do
    // not claim primary-media-source eligibility. Becomes true only on PLUGGED event
    // (phone attached to adapter = projection imminent). Returns to false on
    // UNPLUGGED / DISCONNECTED / app force-stop-recovery.
    //
    // This replaces the earlier design of keeping session "always active with
    // placeholder metadata" which had the side effect of permanently occupying
    // CarMediaService's playback-primary slot, blocking other sources from
    // promoting and blocking CarLauncher's PlaybackViewModel from re-evaluating
    // its MediaController on session-token rotation (e.g., after force-stop).
    //
    // LIMITATION: toggling this field does NOT recover CarLauncher's stale Media
    // card after a force-stop. This is an AAOS platform bug, not specific to this
    // app — reproduced 2026-04-21 on Apple Music 5.2.1 (a first-party vendor app)
    // with identical symptoms. CarLauncher's PlaybackViewModel retains the
    // destroyed-session MediaController reference and never rebinds to the new
    // session token on the next ACTIVE transition. Only full package uninstall +
    // reinstall + emulator restart clears it. The toggle below remains because
    // it is architecturally correct (session should not squat as playback-primary
    // when the adapter has no phone attached), but it is not a fix for the
    // stale-card bug — that requires a patch inside AAOS itself. See
    // MediaSessionManager class KDoc "KNOWN OS DEFICIENCY" for the full write-up.
    private var isSessionActive: Boolean = false

    // COMMAND_SET_MEDIA_ITEM is intentionally NOT advertised. Per SimpleBasePlayer docs a
    // handle* method is only invoked when the corresponding command is available, and its
    // returned future signals "completion of all immediate State changes caused by this
    // call." Advertising SET_MEDIA_ITEM while handleSetMediaItems is a no-op would claim
    // success to controllers without actually changing the single synthetic item returned
    // by getState — visible-state inconsistency. The phone owns media-item selection.
    private val availableCommands: Player.Commands = Player.Commands.Builder()
        .add(Player.COMMAND_PLAY_PAUSE)
        .add(Player.COMMAND_STOP)
        .add(Player.COMMAND_SEEK_TO_NEXT)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .add(Player.COMMAND_PREPARE)
        .add(Player.COMMAND_GET_METADATA)
        .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
        .add(Player.COMMAND_GET_TIMELINE)
        .build()

    /**
     * Push a playback-state update originating from a USB MEDIA_DATA frame. Safe to call
     * from any thread; the actual state mutation + [invalidateState] are posted to the
     * main looper.
     *
     * @param playbackState one of [Player.STATE_IDLE], [Player.STATE_BUFFERING],
     *                      [Player.STATE_READY], [Player.STATE_ENDED]. AAOS derives the
     *                      "active media source" state from this, so map accurately.
     * @param playing current play/pause state reported by the phone
     * @param positionMs current playback position in milliseconds (phone-authoritative)
     * @param durationMs total track duration in milliseconds, or a non-positive value if
     *                   unknown (mapped to [C.TIME_UNSET])
     */
    fun updatePlaybackState(
        playbackState: Int,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long,
    ) {
        postToMain {
            // Invariant guard: SimpleBasePlayer's verifyApplicationThread / invalidateState
            // require the application looper. postToMain routes here, but a future refactor
            // that moves state mutation OUT of postToMain would silently violate the
            // contract on release builds until the first controller crash. Debug assertion
            // catches regressions locally without runtime cost on release APKs.
            if (BuildConfig.DEBUG) check(Looper.myLooper() == applicationLooper) {
                "UsbAdapterPlayer state mutation off the application looper"
            }
            synchronized(stateLock) {
                this.playbackState = playbackState
                this.isPlaying = playing
                this.positionMs = positionMs
                this.durationMs = if (durationMs > 0) durationMs else C.TIME_UNSET
            }
            invalidateState()
        }
    }

    /**
     * Push a metadata update (title / artist / album / artwork). Safe to call from any
     * thread. Framework fires onMediaMetadataChanged to all bound controllers after
     * [getState] is re-read.
     */
    fun updateMediaMetadata(metadata: MediaMetadata) {
        postToMain {
            if (BuildConfig.DEBUG) check(Looper.myLooper() == applicationLooper) {
                "UsbAdapterPlayer state mutation off the application looper"
            }
            synchronized(stateLock) {
                this.mediaMetadata = metadata
            }
            invalidateState()
        }
    }

    /**
     * Toggle projection-state gate. Safe to call from any thread.
     *
     * @param active true when a phone is attached and projecting (session becomes a
     *   valid playback-eligible source for AAOS consumers); false when no projection
     *   is running (session reports empty timeline + STATE_IDLE, platform
     *   MediaSession.isActive → false, AAOS can promote other sources freely).
     */
    fun setSessionActive(active: Boolean) {
        postToMain {
            if (BuildConfig.DEBUG) check(Looper.myLooper() == applicationLooper) {
                "UsbAdapterPlayer state mutation off the application looper"
            }
            synchronized(stateLock) {
                this.isSessionActive = active
            }
            invalidateState()
        }
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    override fun getState(): State {
        val snapshot = synchronized(stateLock) {
            StateSnapshot(playbackState, isPlaying, positionMs, durationMs, mediaMetadata, isSessionActive)
        }
        // INACTIVE path: empty playlist + STATE_IDLE → platform MediaSession.isActive=false.
        // AAOS CarMediaService then treats Carlink as not-a-valid-playback-source and other
        // media apps are free to promote themselves into the playback-primary slot.
        // Media3 derives active state from `!timeline.isEmpty() || playbackState != IDLE`,
        // so both conditions must be inactive to flip the bit off (see MediaSessionImpl).
        if (!snapshot.isActive) {
            return State.Builder()
                .setAvailableCommands(Player.Commands.EMPTY)
                .setPlaylist(emptyList())
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                .setIsLoading(false)
                .build()
        }

        // Derive the MediaItemData uid from the metadata content so the uid changes when
        // the song changes. SimpleBasePlayer uses MediaItemData.uid (via equals) as the
        // identity signal that drives Player.Listener.onMediaItemTransition — reusing a
        // constant uid across songs suppresses track-transition callbacks for any
        // controller that relies on them (legacy MediaControllerCompat.onMetadataChanged
        // still fires, so GM AAOS is unaffected, but a pure-Media3 controller would miss
        // transitions). Position-only updates do not change title/artist/album so the uid
        // stays stable across playback ticks.
        val itemUid = itemUidFor(snapshot.metadata)
        val mediaItem = MediaItem.Builder()
            .setMediaId(itemUid)
            .setMediaMetadata(snapshot.metadata)
            .build()
        val durationUs = if (snapshot.durationMs == C.TIME_UNSET) {
            C.TIME_UNSET
        } else {
            snapshot.durationMs * 1000L
        }
        val mediaItemData = MediaItemData.Builder(itemUid)
            .setMediaItem(mediaItem)
            .setMediaMetadata(snapshot.metadata)
            .setDurationUs(durationUs)
            .build()
        return State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlaylist(listOf(mediaItemData))
            .setCurrentMediaItemIndex(0)
            // REMOTE — "started or paused because of a remote change" — is the correct
            // reason for a mirror Player driven by external transport state (the phone
            // over USB). USER_REQUEST is reserved for local setPlayWhenReady calls.
            .setPlayWhenReady(snapshot.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(snapshot.playbackState)
            .setContentPositionMs(snapshot.positionMs)
            .setIsLoading(snapshot.playbackState == Player.STATE_BUFFERING)
            .build()
    }

    private fun itemUidFor(metadata: MediaMetadata): String {
        // Title+artist+album identity triplet. Position-only frames keep the same triplet
        // so the uid is stable across playback ticks; a song change flips at least one of
        // the three. Incremental AA frames (title-only, then artist-only) will rotate the
        // uid multiple times during the ~30 ms burst — benign because the listener debounce
        // on the controller side collapses them.
        //
        // Hash uses a delimited-string concatenation (T|…|A|…|B|…) rather than XOR of
        // per-field hashCodes: XOR is commutative/self-cancelling and would collapse the
        // field-permutation edge case (e.g., title="X" artist="" == title="" artist="X").
        // Delimiter prefixes T/A/B also prevent value-boundary collisions ("ab|c" vs "a|bc").
        val title = metadata.title?.toString() ?: ""
        val artist = metadata.artist?.toString() ?: ""
        val album = metadata.albumTitle?.toString() ?: ""
        if (title.isEmpty() && artist.isEmpty() && album.isEmpty()) return PLACEHOLDER_ITEM_ID
        val hash = "T|$title|A|$artist|B|$album".hashCode().toLong() and 0xFFFFFFFFL
        return "carlink-$hash"
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) callback.onPlay() else callback.onPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        callback.onStop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        @Player.Command seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                callback.onSkipToNext()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                callback.onSkipToPrevious()
            else -> {
                // Intra-track seeks are not supported — phone owns the position.
            }
        }
        return Futures.immediateVoidFuture()
    }

    // handleSetMediaItems is intentionally NOT overridden: COMMAND_SET_MEDIA_ITEM is not
    // advertised (see availableCommands), so SimpleBasePlayer never invokes it.

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private data class StateSnapshot(
        val playbackState: Int,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val metadata: MediaMetadata,
        val isActive: Boolean,
    )

    companion object {
        // Placeholder uid used when no metadata has arrived yet (pre-stream placeholder).
        // Real items derive their uid from metadata content via [itemUidFor] so the uid
        // rotates on track change and drives Player.Listener.onMediaItemTransition.
        private const val PLACEHOLDER_ITEM_ID = "carlink-placeholder"
    }
}
