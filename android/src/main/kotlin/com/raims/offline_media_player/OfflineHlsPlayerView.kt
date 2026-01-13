package com.raims.offline_media_player

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.raims.offline_media_player.R
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Platform View Factory for creating OfflineHlsPlayerView instances
 */
@UnstableApi
class OfflineHlsPlayerViewFactory(
    private val playerManager: () -> OfflineHlsPlayerManager?
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    companion object {
        private const val TAG = "OfflineHlsPlayerViewFactory"
    }

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        Log.d(TAG, "╔═══════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║     PLATFORM VIEW FACTORY - CREATE CALLED                 ║")
        Log.d(TAG, "╚═══════════════════════════════════════════════════════════╝")
        Log.d(TAG, "   viewId: $viewId")
        Log.d(TAG, "   args: $args")
        try {
            @Suppress("UNCHECKED_CAST")
            val creationParams = args as? Map<String, Any?>
            Log.d(TAG, "   creationParams: $creationParams")
            val view = OfflineHlsPlayerView(context, viewId, creationParams, playerManager)
            Log.d(TAG, "   ✅ PlatformView created successfully")
            return view
        } catch (e: Exception) {
            Log.e(TAG, "   ❌ ERROR creating PlatformView: ${e.message}", e)
            throw e
        }
    }
}

/**
 * Platform View that displays ExoPlayer for offline HLS + DRM playback
 *
 * CRITICAL FOR DRM OFFLINE PLAYBACK:
 * 1. Uses SurfaceView (NOT TextureView) - set via XML surface_type="surface_view"
 * 2. SurfaceView is marked as SECURE for Widevine DRM
 * 3. Surface is attached BEFORE ExoPlayer.prepare() is called
 * 4. Must use Hybrid Composition in Flutter (initSurfaceAndroidView)
 *
 * Without secure surface, Widevine offline playback will render audio only.
 */
@UnstableApi
class OfflineHlsPlayerView(
    context: Context,
    private val viewId: Int,
    creationParams: Map<String, Any?>?,
    private val playerManagerProvider: () -> OfflineHlsPlayerManager?
) : PlatformView {

    companion object {
        private const val TAG = "OfflineHlsPlayerView"
    }

    private val playerView: PlayerView

    private var surfaceView: SurfaceView? = null
    private var isSurfaceReady = false

    init {
        Log.d(TAG, "=== Creating PlayerView for viewId=$viewId ===")

        // CRITICAL: Inflate PlayerView from XML layout that specifies surface_type="surface_view"
        playerView = LayoutInflater.from(context)
            .inflate(R.layout.offline_hls_player_view, null) as PlayerView

        // Set layout params to fill container
        playerView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Find SurfaceView recursively in the view hierarchy
        surfaceView = findSurfaceViewRecursively(playerView)

        // CRITICAL: Mark the SurfaceView as SECURE for DRM content
        // Without this, Widevine L1 will fail to render video (audio only)
        var isSecureSurfaceSet = false
        if (surfaceView != null) {
            surfaceView!!.setSecure(true)
            isSecureSurfaceSet = true
            Log.d(TAG, "✅ SurfaceView.setSecure(true) - SECURE SURFACE ENABLED")
            Log.d(TAG, "   SurfaceView class: ${surfaceView!!.javaClass.name}")

            // Add SurfaceHolder callback to detect when surface is actually ready
            surfaceView!!.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.d(TAG, "╔═══════════════════════════════════════════════════════════╗")
                    Log.d(TAG, "║     SURFACE CREATED - NOW READY FOR PLAYBACK              ║")
                    Log.d(TAG, "╚═══════════════════════════════════════════════════════════╝")
                    Log.d(TAG, "   holder.surface: ${holder.surface}")
                    Log.d(TAG, "   holder.surface.isValid: ${holder.surface?.isValid}")
                    isSurfaceReady = true

                    // Now attach to player manager - surface is actually ready
                    val playerManager = playerManagerProvider()
                    if (playerManager != null) {
                        Log.d(TAG, "   Attaching PlayerView to PlayerManager (surface is now valid)")
                        playerManager.attachPlayerView(playerView)
                        Log.d(TAG, "   ✅ PlayerView attached after surface created")
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    Log.d(TAG, "surfaceChanged: format=$format, size=${width}x${height}")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.d(TAG, "surfaceDestroyed")
                    isSurfaceReady = false
                }
            })
        } else {
            Log.e(TAG, "❌ WARNING: Could not find SurfaceView in PlayerView hierarchy!")
            Log.e(TAG, "   Dumping view hierarchy for debugging:")
            dumpViewHierarchy(playerView, "   ")
        }

        // Debug logging for verification
        Log.d(TAG, "PlayerView configuration:")
        Log.d(TAG, "   viewId=$viewId")
        Log.d(TAG, "   surface_type=surface_view (from XML)")
        Log.d(TAG, "   useController=${playerView.useController}")
        Log.d(TAG, "   SurfaceView found: ${surfaceView != null}")
        Log.d(TAG, "   SurfaceView.setSecure(true) called: $isSecureSurfaceSet")
        Log.d(TAG, "   Waiting for surfaceCreated callback before attaching to PlayerManager...")
    }

    /**
     * Recursively find a SurfaceView in the view hierarchy
     */
    private fun findSurfaceViewRecursively(view: View): SurfaceView? {
        if (view is SurfaceView) {
            return view
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val result = findSurfaceViewRecursively(child)
                if (result != null) {
                    return result
                }
            }
        }

        return null
    }

    /**
     * Dump view hierarchy for debugging
     */
    private fun dumpViewHierarchy(view: View, indent: String) {
        Log.d(TAG, "$indent${view.javaClass.simpleName} (${view.width}x${view.height})")
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dumpViewHierarchy(view.getChildAt(i), "$indent  ")
            }
        }
    }

    override fun getView(): View = playerView

    override fun dispose() {
        Log.d(TAG, "Disposing OfflineHlsPlayerView viewId=$viewId")
        val playerManager = playerManagerProvider()
        playerManager?.detachPlayerView()
        playerView.player = null
        isSurfaceReady = false
    }
}
