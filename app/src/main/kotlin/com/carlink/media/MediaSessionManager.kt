@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.carlink.media

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.carlink.BuildConfig
import com.carlink.MainActivity
import com.carlink.util.LogCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "CARLINK_MEDIA"

/**
 * Cluster / system consumers that read album-art `content://` URIs from outside the
 * MediaController IPC channel (e.g. through their own ContentProvider/RHMI path) and
 * therefore don't receive the framework's implicit per-controller URI grant. We
 * pre-grant read permission so their `ContentResolver.openFileDescriptor()` succeeds.
 * Unknown packages on a given build are silently ignored by [grantUriToConsumers].
 *
 * Identified from firmware-level analysis of GM AAOS 2024 Silverado ICE
 * (see mempalace gminfo37/projection drawers on GMCarMediaService / ClusterService
 * and the IClusterHmi widget API): GM's RHMI cluster re-marshals MediaMetadata
 * through its own `gm.media.provider` ContentProvider, which strips the framework's
 * implicit grant — without an explicit grant the cluster's getBitmap() throws
 * SecurityException.
 */
private val URI_GRANT_CONSUMERS = listOf(
    "com.gm.rhmi",
    "com.gm.cluster",
    "com.gm.gmaudio.tuner",
    "com.gm.car.media.gmcarmediaservice",
    "com.android.car.media",
    "com.android.car.cluster",
    "com.android.car.cluster.home",
    "com.android.systemui",
)

/**
 * MediaSessionManager — Media3-backed MediaSession lifecycle + metadata/state mirror
 * for AAOS media source integration.
 *
 * PURPOSE
 * Exposes the Carlink app as a selectable media source on Android Automotive OS (AAOS)
 * using the modern androidx.media3 stack. The app does not play audio locally — the
 * connected phone plays over USB; this class only mirrors the phone's state + forwards
 * transport-control gestures back to the USB adapter.
 *
 * ARCHITECTURE
 * ```
 * CarPlay/AA projection → USB adapter → CarlinkManager.processMediaMetadata
 *                                                      │
 *                                                      ▼
 *                                       MediaSessionManager
 *                                                      │
 *                               ┌──────────────────────┼─────────────────────┐
 *                               │                      │                     │
 *                               ▼                      ▼                     ▼
 *                       UsbAdapterPlayer      AlbumArtCache          androidx.media3
 *                       (SimpleBasePlayer)   (FileProvider URIs)     .session.MediaSession
 *                               │                      │                     │
 *                               │                      └──→ setArtworkUri    │
 *                               │   ByteArray ────────────→ setArtworkData   │
 *                               │                                            │
 *                               └──→ handleSetPlayWhenReady / handleSeek ────┘
 *                                     (Player routes via Callback)
 *                                                      │
 *                                                      ▼
 *                                           MediaControlCallback
 *                                     (CarlinkManager.sendKey(CommandMapping))
 * ```
 *
 * THREAD SAFETY
 * [MediaSession] is not inherently thread-safe; [UsbAdapterPlayer] is pinned to the
 * main Looper. [updateMetadata] / [updatePlaybackState] / [setStateStopped] /
 * [setStateConnecting] are called from the USB read thread; [initialize] / [release]
 * from the main/lifecycle thread. All mutations of session + internal dedup state are
 * wrapped in `synchronized(sessionLock)`. UsbAdapterPlayer internally posts state
 * updates back to the main looper before calling [Player.invalidateState].
 *
 * DUAL-CARRIER ALBUM ART (preserved from legacy MediaSessionCompat path)
 * Media3's [MediaMetadata] supports both [MediaMetadata.Builder.setArtworkData]
 * (raw bytes, decoded by the consumer) and [MediaMetadata.Builder.setArtworkUri]
 * (`content://` URI via FileProvider). We populate BOTH:
 * - setArtworkData is the PRIMARY carrier — raw JPEG/PNG bytes from the USB
 *   MEDIA_DATA frame flow straight through (no local decode; Media3 consumers decode
 *   on their side). Guaranteed to render even if the URI path fails.
 * - setArtworkUri is ADDITIVE — only set after [AlbumArtCache.put] confirms the
 *   backing file is on disk. Consumers that prefer URIs (e.g. GM RHMI) pick this up.
 *
 * WHY DUAL CARRIER: GM's GMCarMediaService reads metadata via MediaControllerCompat
 * (gm_apks/GMCarMediaService/sources/c0/g.java:111 new MediaControllerCompat(ctx, token)
 * → getMetadata() at c0/g.java:261). An unresolvable artworkUri can nullify the bundle.
 * Inline bytes guarantee text + art always render.
 *
 * SEEK DEDUP (2000 ms threshold)
 * USB MEDIA_DATA position frames arrive ~60–100 ms apart; ~95% are position-only ticks
 * (CarlinkManager.kt:2441) which AAOS already extrapolates. Dedup pushes to the Player
 * only on play/pause transition or when position drifts >2 s from the extrapolated
 * value. Verified 2026-04-20 POTATO session 154850: all surviving "seek" events are
 * genuine track-changes/scrubs, zero false positives across ~6000 MEDIA_DATA frames.
 *
 * NO EXPLICIT AUDIO ATTRIBUTES / NO setPlaybackToLocal
 * Intentionally do not configure audio attributes on the Player. Committing to
 * `CONTENT_TYPE_MUSIC` (equivalent of the legacy `setPlaybackToLocal`) would hard-route
 * hardware volume keys to the MEDIA volume group on GM AAOS, bypassing the focus-aware
 * fallback for SIRI / PHONE_CALL / navigation-prompt contexts. The default AudioAttributes
 * preserves AAOS focus routing.
 *
 * MEDIA3 SESSION "ACTIVE" STATE
 * Media3's [MediaSession] does not expose `isActive` — AAOS Media Center derives the
 * session's "available media source" status from the backing [Player]'s state:
 *   STATE_IDLE + empty timeline → inactive (stopped / disconnected)
 *   STATE_BUFFERING             → active (connecting / waiting for data)
 *   STATE_READY                 → active (has source; play or pause)
 *   STATE_ENDED                 → active (playback finished)
 * [setProjectionActive] / [setInactive] set the appropriate Player state so the
 * AAOS switcher visibility matches the USB adapter lifecycle.
 *
 * KNOWN OS DEFICIENCY — stale homescreen Media card after force-stop
 * ------------------------------------------------------------------
 * AAOS platform limitation, not specific to this app. Reproduced on:
 *   - zeno.carlink (our Media3 projection app) — 2026-04-21.
 *   - Apple Music 5.2.1 (com.apple.android.music), a first-party vendor app using
 *     standard MediaBrowserService + MediaSession — 2026-04-21, same symptom.
 * Any media app in the AAOS ecosystem hits this; do not build app-side workarounds.
 * By extension the same failure is expected on the GM WidgetPanel media widget and
 * the GM Cluster media panel (they consume MediaSession through the same plumbing).
 *
 * Symptom: force-stopping the app and relaunching (with the data source — phone,
 *   streaming service, etc. — still producing content) leaves CarLauncher's homescreen
 *   Media card showing only the app name with no metadata/artwork. The new session
 *   token is ACTIVE, CarMediaService dumpsys reports `current playback media component:
 *   <package>` and `media playback state: 3` (PLAYING), but the card stays blank.
 *
 * Root cause is NOT fixable from app code:
 *   - CarLauncher's PlaybackViewModel binds a MediaController reference to the original
 *     session token. When force-stop tears down the session, `onSessionDestroyed` nulls
 *     the controller, but no code path re-binds to the freshly minted session (same
 *     package, new session token) on the next inactive→active transition.
 *   - CarMediaService additionally persists "last playback primary = zeno.carlink" to
 *     SharedPreferences at `/data/user/0/com.android.car/`; on boot the service replays
 *     that selection to CarLauncher, which then rebinds to its cached-but-stale
 *     controller reference.
 *
 * Workarounds tried (all failed):
 *   - Two-step source switch (select another source, switch back).
 *   - Self-MediaBrowser rebind / MAIN+APP_MUSIC self-intent.
 *   - Making the session INACTIVE-by-default until PLUGGED event (this current design).
 *   - Emulator restart.
 *   - Force-stop + relaunch with fresh session token.
 *
 * Only fix: full package **uninstall + reinstall + emulator restart**. `adb install -r`
 * (replace) does NOT clear the cached state; the install must remove the package so
 * CarMediaService's SharedPreferences purge the stored primary-source reference.
 *
 * Implication: the [setProjectionActive]/[setInactive] toggle below is architecturally
 * correct (session should not squat as playback-primary when the adapter is idle) but
 * does not and cannot resolve the stale-card bug — that requires a patch to
 * CarLauncher's PlaybackViewModel inside AAOS itself.
 *
 * BACKWARDS COMPATIBILITY WITH GM AAOS OBSERVERS
 * Media3 [MediaSession] internally registers a platform-level
 * `android.media.session.MediaSession` for legacy observers. Decompiled
 * GMCarMediaService (c0/g.java:105 MediaSessionCompat.Token.a(..getSessionToken()),
 * :176 getSystemService("media_session"), :241 android.media.session.MediaController
 * .registerCallback) confirms GM binds via MediaBrowserCompat/MediaControllerCompat
 * shims over platform APIs — never touches androidx directly. Media3 migration is
 * transparent to the ClusterService → IClusterHmi widget pipeline.
 */
class MediaSessionManager(
    private val context: Context,
    private val logCallback: LogCallback,
) {

    /** Routed transport controls from AAOS / steering wheel / cluster. Forwarded by
     *  [UsbAdapterPlayer.Callback] → this callback → CarlinkManager.sendKey(). */
    interface MediaControlCallback {
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onSkipToNext()
        fun onSkipToPrevious()
    }

    private var player: UsbAdapterPlayer? = null
    private var mediaSession: MediaLibrarySession? = null

    // @Volatile: the setter ([setMediaControlCallback]) is public and release() clears the
    // field under sessionLock while playerCallback trampoline reads it from the main thread
    // (UsbAdapterPlayer posts to main before invoking). @Volatile gives a well-defined
    // happens-before on cross-thread writes/reads without requiring callers to take the
    // lock. In practice CarlinkManager sets it once during initialize() and clears via
    // release(), so the field is only written from the main thread, but @Volatile codifies
    // the contract so a future off-main caller won't silently race.
    @Volatile
    private var mediaControlCallback: MediaControlCallback? = null

    // Current reported state — cached so updatePlaybackState can supply duration with
    // each Player update (duration arrives on metadata frames, not playback frames).
    private var currentDurationMs: Long = 0L

    // Dedup state for playback-state pushes. Matches the legacy PlaybackStateCompat
    // dedup — SimpleBasePlayer has its own equality-based suppression, but this
    // outer filter cuts USB-rate updates (60–100 ms) to at most one per state/seek.
    private var lastPushedPlaying: Boolean? = null
    private var lastPushedPositionMs: Long = 0L
    private var lastPushedTimeNanos: Long = 0L

    /** Position must drift more than this from the AAOS-extrapolated value to be
     *  considered a genuine user seek. 2 s absorbs USB/cluster jitter without suppressing
     *  seeks. Validated 2026-04-20 POTATO session 154850 — all surviving seek events were
     *  real track-changes / scrubs. */
    private val seekThresholdMs: Long = 2_000L

    // Album art cache — produces FileProvider content:// URIs for the URI path of the
    // dual-carrier. Decoding-to-Bitmap (legacy decodeDisplayIcon) is NOT used on the
    // Media3 path: raw ByteArray goes straight into MediaMetadata.setArtworkData and
    // consumers decode on their side, saving the USB thread the decode cost.
    private val albumArtCache = AlbumArtCache(context)

    // Last-published art dedup + dual-carrier state.
    private var lastArtHash: Int = 0
    private var lastArtUri: Uri? = null
    private var lastArtBytes: ByteArray? = null
    private var lastArtJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionLock = Any()

    /**
     * [MediaLibrarySession.Callback] for AAOS Media Center browse-tree queries. carlink_native
     * is a projection app — the browse tree is empty (no local library). The root node is
     * marked browsable-but-not-playable with no children, matching the legacy
     * MediaBrowserServiceCompat.onGetRoot(EMPTY_ROOT) + onLoadChildren(emptyList()) pattern.
     *
     * Callbacks run on the application main thread. The session itself is supplied as
     * [session] argument and mirrors [mediaSession].
     */
    private val libraryCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onGetLibraryRoot caller=${browser.packageName} uid=${browser.uid} " +
                        "ifaceVer=${browser.interfaceVersion} ctrlVer=${browser.controllerVersion} " +
                        "paramsExtras=${params?.extras}",
                )
            }
            // Root extras replicate the legacy AAOS content-style hints so the media
            // source switcher lists Carlink with the expected card presentation even
            // though the browse tree below is empty.
            val rootExtras = Bundle().apply {
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
                putBoolean("android.media.browse.SEARCH_SUPPORTED", false)
            }
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Carlink")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(
                LibraryResult.ofItem(rootItem, MediaLibraryService.LibraryParams.Builder().setExtras(rootExtras).build()),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onGetChildren caller=${browser.packageName} uid=${browser.uid} " +
                        "ifaceVer=${browser.interfaceVersion} parentId=$parentId page=$page pageSize=$pageSize " +
                        "(empty tree)",
                )
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onGetItem caller=${browser.packageName} uid=${browser.uid} " +
                        "ifaceVer=${browser.interfaceVersion} mediaId=$mediaId",
                )
            }
            // Legacy MediaBrowserCompat clients (AAOS Media Center) may call getItem(rootId)
            // to verify the root node; per the MediaLibrarySession.Callback.onGetItem doc
            // "To allow getting the item, return a LibraryResult with RESULT_SUCCESS and a
            // MediaItem with a valid mediaId." Resolve ROOT_ID so the verification succeeds;
            // all other IDs return RESULT_ERROR_BAD_VALUE (the ID is invalid for this
            // projection app's empty browse tree, not merely unsupported).
            if (mediaId == ROOT_ID) {
                val rootItem = MediaItem.Builder()
                    .setMediaId(ROOT_ID)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Carlink")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build(),
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(rootItem, null))
            }
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }

        /**
         * [PROBE] Diagnostic for the AOSP CarLauncher homescreen Media card vs.
         * ClusterMediaDisplay divergence (2026-04-23 session). ClusterMediaDisplay uses
         * platform `MediaSessionManager.getActiveSessions()` and renders correctly;
         * CarLauncher's card uses the MediaBrowserCompat→MediaModel chain and stays stuck
         * on the "source resolved, no metadata" placeholder even though the session has
         * 11 metadata keys, active=true, state=PLAYING, and 8 controllers bound. These
         * overrides log every handshake step with caller package + interface version
         * (ifaceVer==0 signals the legacy MediaBrowserCompat path; ifaceVer>0 is a
         * native Media3 controller) so a logcat capture can distinguish:
         *   (a) CarLauncher connects as a controller but the MBS-legacy onGetLibraryRoot
         *       dispatch never fires → Media3 legacy-bridge wiring bug.
         *   (b) onGetLibraryRoot fires but onSubscribe/onGetChildren never follow →
         *       BrowserRoot shape (mediaId/extras) rejected by AOSP MediaBrowserConnector.
         *   (c) Full handshake completes cleanly yet the card stays blank → failure is
         *       deeper in AOSP MediaModel, outside anything we can fix app-side.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onConnect caller=${controller.packageName} uid=${controller.uid} " +
                        "ifaceVer=${controller.interfaceVersion} ctrlVer=${controller.controllerVersion} " +
                        "connectionHints=${controller.connectionHints}",
                )
            }
            return super.onConnect(session, controller)
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onDisconnected caller=${controller.packageName} uid=${controller.uid} " +
                        "ifaceVer=${controller.interfaceVersion}",
                )
            }
            super.onDisconnected(session, controller)
        }

        override fun onSubscribe(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "[MEDIA_SESSION] [PROBE] onSubscribe caller=${browser.packageName} uid=${browser.uid} " +
                        "ifaceVer=${browser.interfaceVersion} parentId=$parentId paramsExtras=${params?.extras}",
                )
            }
            return super.onSubscribe(session, browser, parentId, params)
        }
    }

    /**
     * Trampoline from [UsbAdapterPlayer.Callback] → [MediaControlCallback]. The Player's
     * Callback runs on the main thread per SimpleBasePlayer's contract; we forward
     * synchronously so CarlinkManager's sendKey() sees main-thread callbacks as before.
     */
    private val playerCallback = object : UsbAdapterPlayer.Callback {
        override fun onPlay() {
            log("[MEDIA_SESSION] onPlay received")
            mediaControlCallback?.onPlay()
        }
        override fun onPause() {
            log("[MEDIA_SESSION] onPause received")
            mediaControlCallback?.onPause()
        }
        override fun onStop() {
            log("[MEDIA_SESSION] onStop received")
            mediaControlCallback?.onStop()
        }
        override fun onSkipToNext() {
            log("[MEDIA_SESSION] onSkipToNext received")
            mediaControlCallback?.onSkipToNext()
        }
        override fun onSkipToPrevious() {
            log("[MEDIA_SESSION] onSkipToPrevious received")
            mediaControlCallback?.onSkipToPrevious()
        }
    }

    /**
     * Create the UsbAdapterPlayer + MediaSession. Called once from
     * CarlinkManager.initialize() on the main thread during plugin attachment.
     * Safe to call multiple times — re-entry is a no-op once initialized.
     */
    fun initialize() {
        if (mediaSession != null) {
            log("[MEDIA_SESSION] Already initialized — skipping")
            return
        }
        try {
            val p = UsbAdapterPlayer(playerCallback)
            player = p
            // Note: MediaLibrarySession.Builder has two overloads — (Context, Player, Callback)
            // and (MediaLibraryService, Player, Callback). We use the Context overload because
            // this manager is created by CarlinkManager with an application Context, not by
            // the service itself. The service obtains the live session via the companion
            // accessor [getMediaLibrarySession] when AAOS calls [MediaLibraryService.onGetSession].
            mediaSession = MediaLibrarySession.Builder(context, p, libraryCallback)
                .setId(SESSION_ID)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java).apply {
                            // NEW_TASK is required because PendingIntent.getActivity is
                            // launched by the system (controller notification, now-playing
                            // card) outside any existing Activity context — without it
                            // Android throws AndroidRuntimeException. SINGLE_TOP avoids
                            // stacking duplicate MainActivity instances when the app is
                            // already visible.
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .build()
            currentInstance = this

            // Session starts INACTIVE. UsbAdapterPlayer's default isSessionActive=false
            // produces an empty playlist + STATE_IDLE from getState() — platform
            // MediaSession.isActive is false until CarlinkManager's state machine hits
            // DEVICE_CONNECTED (phone PLUGGED on adapter) and calls [setProjectionActive].
            //
            // This replaces the earlier "always-active with 'Not connected' placeholder"
            // design. The old design permanently occupied AAOS's playback-primary slot,
            // blocking other media apps from promoting and blocking CarLauncher's
            // PlaybackViewModel from re-evaluating its MediaController after session-token
            // changes (e.g., post-force-stop-relaunch). Starting inactive lets AAOS
            // properly observe a later inactive→active transition as a bona-fide
            // primary-source-eligible event.
            log("[MEDIA_SESSION] Initialized INACTIVE (Media3 session id=$SESSION_ID)")
        } catch (e: Exception) {
            // Log then rethrow so CarlinkManager's outer try/catch observes the failure.
            // Swallowing here would leave `mediaSessionManager` assigned to an instance with
            // mediaSession == null: AAOS would see the session registered but
            // CarlinkMediaBrowserService.onGetSession → getMediaLibrarySession() returns null
            // → permanent controller rejection. Fail-fast matches Media3's canonical pattern
            // (official sample does not try/catch MediaLibrarySession.Builder.build()).
            log("[MEDIA_SESSION] Failed to initialize: ${e.message}")
            throw e
        }
    }

    /**
     * Release session + player, cancel coroutines, reset dedup state. Call during
     * plugin detachment from the main thread.
     */
    fun release() {
        try {
            // Cancel scope outside the lock: non-blocking, and any in-flight
            // mainHandler.post will observe mediaSession == null under the lock.
            scope.cancel()
            synchronized(sessionLock) {
                lastArtJob = null
                // Revoke outstanding URI grants before releasing the session so consumer
                // packages don't retain access to a cache entry the session no longer
                // advertises. Context.revokeUriPermission (API 26+) is safe on min SDK 32.
                lastArtUri?.let { revokeUriFromConsumers(it) }
                // Per Media3 docs (developer.android.com/media/media3/session/serve-content
                // sample `onDestroy`), the Player is NOT released by MediaSession.release();
                // the app must release it explicitly. Release player BEFORE the session so
                // the session's controller teardown sees a still-valid Player reference.
                player?.release()
                mediaSession?.release()
                mediaSession = null
                player = null
                mediaControlCallback = null
                if (currentInstance === this) currentInstance = null

                // Reset dedup / art state so a subsequent initialize() starts fresh.
                currentDurationMs = 0L
                lastArtHash = 0
                lastArtUri = null
                lastArtBytes = null
                lastPushedPlaying = null
                lastPushedPositionMs = 0L
                lastPushedTimeNanos = 0L
            }
            log("[MEDIA_SESSION] Released")
        } catch (e: Exception) {
            log("[MEDIA_SESSION] Error during release: ${e.message}")
        }
    }

    /** Register the transport-control sink (CarlinkManager routes to USB commands). */
    fun setMediaControlCallback(callback: MediaControlCallback?) {
        mediaControlCallback = callback
    }

    /**
     * Push now-playing metadata from a CarPlay/AA MEDIA_DATA frame. Safe to call from
     * the USB read thread. Builds a [MediaMetadata] and routes it through the Player
     * (the session syncs to connected controllers automatically).
     *
     * @param title song title
     * @param artist artist
     * @param album album name
     * @param appName source app ("Spotify", "Apple Music", ...) — goes into subtitle
     * @param albumArt raw JPEG/PNG bytes from the adapter (nullable)
     * @param duration total track duration in ms (0 if unknown)
     */
    fun updateMetadata(
        title: String?,
        artist: String?,
        album: String?,
        appName: String?,
        albumArt: ByteArray?,
        duration: Long = 0L,
    ) {
        // Hold sessionLock across the entire read+decide+publish so release()
        // cannot null out player/mediaSession in the middle. The lock is reentrant;
        // the inner publishMediaMetadata's synchronized(sessionLock) re-acquisition
        // is legal (JVM monitor counter). scope.launch inside the lock only enqueues
        // — the coroutine body runs on Dispatchers.IO after the lock is released.
        synchronized(sessionLock) {
            val p = player ?: return
            val hash = albumArt?.let { if (it.isEmpty()) 0 else it.contentHashCode() } ?: 0
            currentDurationMs = if (duration > 0) duration else 0L

            // PRIMARY carrier: raw ByteArray for MediaMetadata.setArtworkData.
            // Fallback to lastArtBytes on null/empty or unchanged hash so we keep
            // the prior song's art visible during the ~100 ms gap between AA's
            // text-only JSON frame and the follow-up ALBUM_COVER_AA frame.
            val artBytesForNow: ByteArray? = when {
                albumArt == null || albumArt.isEmpty() -> lastArtBytes
                hash == lastArtHash -> lastArtBytes
                else -> albumArt
            }
            if (artBytesForNow != null && artBytesForNow !== lastArtBytes) {
                lastArtBytes = artBytesForNow
            }

            // URI on the synchronous path: only reuse if this frame's hash matches
            // the previously-published art. Never publish a stale-song URI for new
            // bytes.
            val artUriForNow: Uri? = if (hash == lastArtHash) lastArtUri else null

            publishMetadata(
                player = p,
                title = title,
                artist = artist,
                album = album,
                appName = appName,
                duration = duration,
                artBytes = artBytesForNow,
                artUri = artUriForNow,
            )

            // ADDITIVE carrier: off-main file write → URI published once verified.
            // Cancellation caveat (unchanged from legacy): lastArtJob?.cancel() stops
            // the coroutine mid-flight, but a mainHandler.post already queued still
            // runs. All fields used inside the post are captured at launch time, so
            // the stale post publishes a CONSISTENT older-song tuple — never a
            // mismatched hash/URI/text pair. Relies on single-producer (USB read
            // thread).
            if (albumArt != null && hash != lastArtHash) {
                lastArtJob?.cancel()
                val titleCapture = title
                val artistCapture = artist
                val albumCapture = album
                val appNameCapture = appName
                val durationCapture = duration
                val bytesCapture = artBytesForNow
                lastArtJob = scope.launch {
                    val uri = try {
                        albumArtCache.put(albumArt)
                    } catch (e: Exception) {
                        log("[MEDIA_SESSION] AlbumArtCache.put failed: ${e.message}")
                        null
                    }
                    if (uri == null) return@launch  // Inline bytes already render; skip URI publish.
                    mainHandler.post {
                        synchronized(sessionLock) {
                            val p2 = player ?: return@synchronized
                            // Revoke the previous art's grants before replacing — any
                            // already-issued controllers will re-resolve through the new
                            // URI on the metadata update that publishMetadata will fire.
                            lastArtUri?.takeIf { it != uri }?.let { revokeUriFromConsumers(it) }
                            lastArtHash = hash
                            lastArtUri = uri
                            publishMetadata(
                                player = p2,
                                title = titleCapture,
                                artist = artistCapture,
                                album = albumCapture,
                                appName = appNameCapture,
                                duration = durationCapture,
                                artBytes = bytesCapture,
                                artUri = uri,
                            )
                        }
                    }
                }
            }
            log(
                "[MEDIA_SESSION] Metadata updated: $title - $artist " +
                    "(inlineBytes=${artBytesForNow != null}, uriPending=${albumArt != null && hash != lastArtHash})",
            )
        }
    }

    /**
     * Build a [MediaMetadata] and hand it to the Player. Single place for the
     * dual-carrier art logic + URI grants.
     *
     * Media3 note: the setter names differ from legacy — `setAlbumTitle` (not
     * `setAlbum`), `setSubtitle` (not `setDisplaySubtitle`), `setDurationMs` (not
     * `putLong(METADATA_KEY_DURATION)`). [setArtworkData] requires a PictureType —
     * we use [MediaMetadata.PICTURE_TYPE_FRONT_COVER] (ID3 v2.4 tag 3, "Cover (front)")
     * per the ID3 spec convention for album art.
     */
    private fun publishMetadata(
        player: UsbAdapterPlayer,
        title: String?,
        artist: String?,
        album: String?,
        appName: String?,
        duration: Long,
        artBytes: ByteArray?,
        artUri: Uri?,
    ) {
        synchronized(sessionLock) {
            try {
                val builder = MediaMetadata.Builder()
                    .setTitle(title ?: "Unknown")
                    .setArtist(artist ?: "Unknown")
                    .setAlbumTitle(album ?: "")
                    .setSubtitle(appName ?: "Carlink")
                if (duration > 0) {
                    builder.setDurationMs(duration)
                }
                if (artBytes != null && artBytes.isNotEmpty()) {
                    builder.setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
                if (artUri != null) {
                    // Grant read permission BEFORE attaching the URI — some consumers
                    // fetch the URI asynchronously as soon as they observe metadata
                    // change, so the grant must already be in place.
                    grantUriToConsumers(artUri)
                    builder.setArtworkUri(artUri)
                }
                player.updateMediaMetadata(builder.build())
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to publish metadata: ${e.message}")
            }
        }
    }

    private fun grantUriToConsumers(uri: Uri) {
        for (pkg in URI_GRANT_CONSUMERS) {
            try {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Package not installed on this build; ignore.
            }
        }
    }

    /**
     * Revoke read permission previously granted via [grantUriToConsumers]. Called when a
     * URI is about to be superseded by a new art URI, on session stop, and on release —
     * grants persist until explicit revoke or device reboot (per
     * developer.android.com/reference/android/content/Context#revokeUriPermission(String,Uri,int),
     * API 26+). Without this, the grant table grows unboundedly for the process lifetime.
     *
     * Uses the per-package overload so we only revoke grants WE issued through
     * [grantUriToConsumers]; the no-argument overload would also drop grants that came in
     * via clipboard / activity launches and is documented as "potentially dangerous".
     */
    private fun revokeUriFromConsumers(uri: Uri) {
        for (pkg in URI_GRANT_CONSUMERS) {
            try {
                context.revokeUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Grant already gone / package uninstalled; ignore.
            }
        }
    }

    /**
     * Push playback state (play/pause + position) from a CarPlay/AA MEDIA_DATA frame.
     * Dedup suppresses redundant pushes (position ticks within 2 s of the AAOS
     * extrapolated value). Safe to call from any thread.
     */
    fun updatePlaybackState(playing: Boolean, position: Long = 0L) {
        synchronized(sessionLock) {
            val p = player ?: return
            val stateChanged = playing != lastPushedPlaying
            val now = System.nanoTime()
            val elapsedMs = (now - lastPushedTimeNanos) / 1_000_000L
            val expectedPosition = if (lastPushedPlaying == true) {
                lastPushedPositionMs + elapsedMs
            } else {
                lastPushedPositionMs
            }
            val seekDetected = kotlin.math.abs(position - expectedPosition) > seekThresholdMs
            if (!stateChanged && !seekDetected) return

            try {
                val durationMs = if (currentDurationMs > 0) currentDurationMs else C.TIME_UNSET
                p.updatePlaybackState(Player.STATE_READY, playing, position, durationMs)
                lastPushedPlaying = playing
                lastPushedPositionMs = position
                lastPushedTimeNanos = now
                val reason = if (stateChanged) "state change" else "seek"
                log(
                    "[MEDIA_SESSION] Playback: ${if (playing) "PLAYING" else "PAUSED"} " +
                        "($reason, pos=${position}ms)",
                )
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to update playback state: ${e.message}")
            }
        }
    }

    /**
     * Switch the session into the CONNECTING state — Player reports STATE_BUFFERING +
     * placeholder metadata. AAOS Media Center still lists the session as an available
     * source during this phase. Called on CarlinkManager's CONNECTING state.
     */
    /**
     * Mark the session ACTIVE — a phone is attached and projection is starting.
     *
     * Called on CarlinkManager's DEVICE_CONNECTED state (adapter PLUGGED event received
     * from the phone-attached iAP2 / AA handshake, BEFORE media frames arrive). Session
     * flips the platform android.media.session.MediaSession.isActive → true, becoming
     * a valid playback-primary candidate. Player enters STATE_BUFFERING with placeholder
     * metadata pending real MEDIA_DATA frames.
     *
     * Distinct from [setInactive]: this is the trigger that makes Carlink eligible for
     * homescreen Media card / cluster Media card binding. Without this transition,
     * AAOS consumers treat Carlink as not-a-valid-media-source and fall back to other
     * apps (Radio, etc.).
     *
     * LIMITATION: this transition does NOT recover CarLauncher's homescreen Media card
     * after a force-stop — see class KDoc "KNOWN OS DEFICIENCY". CarLauncher caches
     * the pre-force-stop MediaController reference and does not re-bind on the new
     * session token.
     */
    fun setProjectionActive() {
        synchronized(sessionLock) {
            val p = player ?: return
            try {
                // Flip active FIRST so getState() returns a non-empty playlist before
                // the playback-state and metadata updates hit invalidateState.
                p.setSessionActive(true)
                p.updatePlaybackState(Player.STATE_BUFFERING, false, 0L, C.TIME_UNSET)
                p.updateMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Carlink")
                        .setArtist("Connecting...")
                        .build(),
                )
                // Reset dedup so the next real playback frame isn't elided.
                lastPushedPlaying = null
                lastPushedPositionMs = 0L
                lastPushedTimeNanos = 0L
                log("[MEDIA_SESSION] State: ACTIVE (projection starting)")
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to set projection-active state: ${e.message}")
            }
        }
    }

    /**
     * Mark the session INACTIVE — no phone is projecting.
     *
     * Called on CarlinkManager's DISCONNECTED or CONNECTING state (app cold start,
     * adapter disconnected, adapter attached but phone not plugged, phone unplugged).
     * Session reports an empty playlist + STATE_IDLE; the platform
     * android.media.session.MediaSession.isActive bit flips to false. AAOS
     * CarMediaService/CarLauncher/ClusterMediaActivity treat Carlink as not a valid
     * playback-primary source and are free to promote any other source.
     *
     * The session record itself stays registered (no session.release()) so reactivation
     * on next PLUGGED event is fast — the SAME session token transitions back to
     * active, avoiding the consumer-rebinding issues that fresh session IDs cause.
     *
     * Note: keeping the session registered only helps when the process stays alive.
     * A force-stop tears the session down unconditionally, and on relaunch the new
     * session gets a new token — at which point CarLauncher's stale-controller bug
     * kicks in (see class KDoc "KNOWN OS DEFICIENCY"). There is no app-side mitigation.
     */
    fun setInactive() {
        synchronized(sessionLock) {
            val p = player ?: return
            try {
                p.setSessionActive(false)
                p.updatePlaybackState(Player.STATE_IDLE, false, 0L, C.TIME_UNSET)
                p.updateMediaMetadata(MediaMetadata.EMPTY)
                // Reset dedup + art state. Revoke any still-published URI grant so
                // consumers don't retain access to album art from an ended session.
                lastArtUri?.let { revokeUriFromConsumers(it) }
                currentDurationMs = 0L
                lastArtHash = 0
                lastArtUri = null
                lastArtBytes = null
                lastArtJob?.cancel()
                lastArtJob = null
                lastPushedPlaying = null
                lastPushedPositionMs = 0L
                lastPushedTimeNanos = 0L
                log("[MEDIA_SESSION] State: INACTIVE (no projection)")
            } catch (e: Exception) {
                log("[MEDIA_SESSION] Failed to set inactive state: ${e.message}")
            }
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
        logCallback.log(message)
    }

    companion object {
        /** Session ID — must be unique within the app package. */
        private const val SESSION_ID = "CarlinkMediaSession"

        /** Root mediaId for the (empty) browse tree — projection app. */
        private const val ROOT_ID = "carlink_root"

        /**
         * Single live [MediaSessionManager] instance. Set by [initialize]; cleared by
         * [release]. Accessed by [CarlinkMediaBrowserService.onGetSession] to obtain the
         * live [MediaLibrarySession] without holding an explicit cross-object reference.
         *
         * StaticFieldLeak suppressed: the instance reachable through this static holds
         * a [Context] field, but [MainActivity] constructs [MediaSessionManager] with
         * `applicationContext` (see MainActivity.kt around the "applicationContext"
         * comment in [initializeCarlinkManager] — revision 2026-04-23 static-analysis
         * cleanup F2). Because the stored Context is process-scoped rather than
         * Activity-scoped, the field cannot leak an Activity across configuration
         * changes or re-creation. Lint's detector sees only the declared `Context`
         * type and cannot distinguish application vs Activity context at compile time,
         * hence the suppression is required to silence the false positive.
         */
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var currentInstance: MediaSessionManager? = null

        /**
         * Return the currently-live [MediaLibrarySession], or `null` if no manager has
         * been initialized (pre-plugin-attach or post-release).
         *
         * Race note: AAOS may call [MediaLibraryService.onGetSession] before
         * [MediaSessionManager.initialize] has created the session. `null` is a
         * permanent connection rejection per Media3 docs. Empirical:
         * - POTATO GM AAOS 2026-04-20 063729: Initialized 06:37:11.771, first GM
         *   onGetLibraryRoot 06:38:39.768 (88s gap, GM binds lazily after handshake).
         *   Zero "rejecting bind" warnings across 3 sessions.
         * - AAOS emulator 2026-04-20 21:55: early MediaController probe arrived 434ms
         *   BEFORE initialize, logged "rejecting bind" once — handled cleanly (probe
         *   was pre-discovery, subsequent real bind succeeded).
         *
         * Atomic snapshot: the [currentInstance] read and the subsequent [mediaSession]
         * read happen as one critical section on the instance's [sessionLock], so
         * [release] cannot null [mediaSession] between the two reads. Without this,
         * a caller could observe `currentInstance != null` and then see
         * `currentInstance.mediaSession == null` during an in-flight release, returning
         * null for a session that was live when the lookup started.
         */
        fun getMediaLibrarySession(): MediaLibrarySession? {
            val inst = currentInstance ?: return null
            return synchronized(inst.sessionLock) { inst.mediaSession }
        }
    }
}
