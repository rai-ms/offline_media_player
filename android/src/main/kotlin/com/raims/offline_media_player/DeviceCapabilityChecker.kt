package com.raims.offline_media_player

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaDrm
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.util.UUID

/**
 * Device Capability Checker for Offline DRM Playback
 *
 * This class checks whether a device is eligible for offline DRM (Widevine) playback.
 *
 * IMPORTANT: If audio plays but video is black, Widevine is INTENTIONALLY blocking
 * video output because the device cannot provide a secure video path. This is by design.
 *
 * Widevine Security Levels:
 * - L1: Hardware-backed security. Secure decoder + secure output required.
 *       Video decoding happens in TEE (Trusted Execution Environment).
 * - L3: Software security only. No secure decoder available.
 *       Most streaming services block offline downloads on L3 devices.
 *
 * For offline DRM playback to work:
 * 1. Device must have Widevine L1 (hardware security)
 * 2. Device must have secure video decoders
 * 3. Device must support secure surfaces
 *
 * If any of these are missing, offline playback should be BLOCKED with a user-friendly message.
 * DO NOT attempt to work around Widevine restrictions - they exist for content protection.
 */
@UnstableApi
class DeviceCapabilityChecker(private val context: Context) {

    companion object {
        private const val TAG = "DeviceCapChecker"

        // Widevine UUID (same as C.WIDEVINE_UUID but explicitly defined)
        val WIDEVINE_UUID: UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

        // Security level strings
        const val SECURITY_LEVEL_L1 = "L1"
        const val SECURITY_LEVEL_L2 = "L2"
        const val SECURITY_LEVEL_L3 = "L3"
        const val SECURITY_LEVEL_UNKNOWN = "UNKNOWN"

        // Common secure video decoder MIME types
        private val SECURE_VIDEO_MIME_TYPES = listOf(
            "video/avc",      // H.264
            "video/hevc",     // H.265
            "video/x-vnd.on2.vp9", // VP9
            "video/dolby-vision"   // Dolby Vision
        )
    }

    /**
     * Result of device capability check
     */
    data class CapabilityResult(
        val isEligible: Boolean,
        val widevineSecurityLevel: String,
        val hasSecureDecoders: Boolean,
        val secureDecoderCount: Int,
        val reason: String,
        val deviceInfo: Map<String, Any>
    )

    /**
     * Check if device is eligible for offline DRM playback
     *
     * @return CapabilityResult with detailed information about device capabilities
     */
    fun checkDeviceEligibility(): CapabilityResult {
        Log.d(TAG, "")
        Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║         DEVICE CAPABILITY CHECK                          ║")
        Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")

        val deviceInfo = collectDeviceInfo()
        logDeviceInfo(deviceInfo)

        // Check 1: Widevine availability and security level
        val (widevineAvailable, securityLevel) = checkWidevineSecurityLevel()

        // Check 2: Secure video decoder availability
        val (hasSecureDecoders, secureDecoderCount, secureDecoderNames) = checkSecureDecoders()

        // Determine eligibility
        val isL1 = securityLevel == SECURITY_LEVEL_L1
        val isEligible = widevineAvailable && isL1 && hasSecureDecoders

        val reason = when {
            !widevineAvailable -> "Widevine DRM is not supported on this device"
            securityLevel == SECURITY_LEVEL_L3 -> "Device has Widevine L3 (software only). Offline playback requires L1 hardware security."
            securityLevel == SECURITY_LEVEL_L2 -> "Device has Widevine L2. Some content may not play offline."
            !hasSecureDecoders -> "Device does not have secure video decoders for protected content"
            isEligible -> "Device is eligible for offline DRM playback"
            else -> "Device does not meet requirements for offline DRM playback"
        }

        Log.d(TAG, "")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "📊 CAPABILITY CHECK RESULT:")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "   Widevine Available: $widevineAvailable")
        Log.d(TAG, "   Security Level: $securityLevel")
        Log.d(TAG, "   Has Secure Decoders: $hasSecureDecoders")
        Log.d(TAG, "   Secure Decoder Count: $secureDecoderCount")
        Log.d(TAG, "   Secure Decoders: $secureDecoderNames")
        Log.d(TAG, "   IS ELIGIBLE: $isEligible")
        Log.d(TAG, "   Reason: $reason")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "")

        return CapabilityResult(
            isEligible = isEligible,
            widevineSecurityLevel = securityLevel,
            hasSecureDecoders = hasSecureDecoders,
            secureDecoderCount = secureDecoderCount,
            reason = reason,
            deviceInfo = deviceInfo
        )
    }

    /**
     * Check Widevine availability and security level
     *
     * @return Pair of (isWidevineAvailable, securityLevel)
     */
    fun checkWidevineSecurityLevel(): Pair<Boolean, String> {
        Log.d(TAG, "")
        Log.d(TAG, "🔐 Checking Widevine Security Level...")

        return try {
            // Check if Widevine is supported
            if (!MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)) {
                Log.w(TAG, "   ❌ Widevine DRM not supported on this device")
                return Pair(false, SECURITY_LEVEL_UNKNOWN)
            }

            // Create MediaDrm instance to query security level
            val mediaDrm = MediaDrm(WIDEVINE_UUID)

            try {
                // Query security level
                val securityLevel = mediaDrm.getPropertyString("securityLevel")
                Log.d(TAG, "   ✅ Widevine is supported")
                Log.d(TAG, "   📊 Security Level: $securityLevel")

                // Log additional Widevine properties
                try {
                    val vendor = mediaDrm.getPropertyString("vendor")
                    val version = mediaDrm.getPropertyString("version")
                    val algorithms = mediaDrm.getPropertyString("algorithms")
                    val systemId = mediaDrm.getPropertyString("systemId")

                    Log.d(TAG, "   📋 Widevine Properties:")
                    Log.d(TAG, "      Vendor: $vendor")
                    Log.d(TAG, "      Version: $version")
                    Log.d(TAG, "      SystemID: $systemId")
                    Log.d(TAG, "      Algorithms: $algorithms")
                } catch (e: Exception) {
                    Log.d(TAG, "   (Some Widevine properties not available)")
                }

                val normalizedLevel = normalizeSecurityLevel(securityLevel)

                when (normalizedLevel) {
                    SECURITY_LEVEL_L1 -> {
                        Log.d(TAG, "   ✅ L1 (Hardware Security) - ELIGIBLE for offline DRM")
                    }
                    SECURITY_LEVEL_L2 -> {
                        Log.w(TAG, "   ⚠️ L2 - Partial hardware security")
                    }
                    SECURITY_LEVEL_L3 -> {
                        Log.w(TAG, "   ❌ L3 (Software Only) - NOT eligible for offline DRM")
                        Log.w(TAG, "      Offline playback will show black video with audio")
                    }
                }

                Pair(true, normalizedLevel)
            } finally {
                mediaDrm.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Error checking Widevine: ${e.message}", e)
            Pair(false, SECURITY_LEVEL_UNKNOWN)
        }
    }

    /**
     * Normalize security level string to standard format (L1, L2, L3)
     */
    private fun normalizeSecurityLevel(level: String?): String {
        if (level == null) return SECURITY_LEVEL_UNKNOWN

        return when {
            level.contains("L1", ignoreCase = true) -> SECURITY_LEVEL_L1
            level.contains("L2", ignoreCase = true) -> SECURITY_LEVEL_L2
            level.contains("L3", ignoreCase = true) -> SECURITY_LEVEL_L3
            level == "1" -> SECURITY_LEVEL_L1
            level == "2" -> SECURITY_LEVEL_L2
            level == "3" -> SECURITY_LEVEL_L3
            else -> level.uppercase()
        }
    }

    /**
     * Check for secure video decoder availability
     *
     * Secure decoders have names ending with ".secure"
     * They are required for DRM content playback
     *
     * @return Triple of (hasSecureDecoders, count, list of decoder names)
     */
    fun checkSecureDecoders(): Triple<Boolean, Int, List<String>> {
        Log.d(TAG, "")
        Log.d(TAG, "🎬 Checking Secure Video Decoders...")

        val secureDecoders = mutableListOf<String>()

        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val codecInfos = codecList.codecInfos

            for (codecInfo in codecInfos) {
                // Only check decoders, not encoders
                if (codecInfo.isEncoder) continue

                val name = codecInfo.name

                // Check if this is a secure decoder
                if (name.endsWith(".secure", ignoreCase = true)) {
                    // Check if it supports video MIME types
                    for (mimeType in SECURE_VIDEO_MIME_TYPES) {
                        try {
                            if (codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                                secureDecoders.add("$name ($mimeType)")
                                Log.d(TAG, "   ✅ Found secure decoder: $name for $mimeType")
                            }
                        } catch (e: Exception) {
                            // MIME type not supported by this codec
                        }
                    }
                }
            }

            if (secureDecoders.isEmpty()) {
                Log.w(TAG, "   ❌ No secure video decoders found!")
                Log.w(TAG, "      DRM video will not render (audio only)")
            } else {
                Log.d(TAG, "   ✅ Found ${secureDecoders.size} secure decoder(s)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Error checking decoders: ${e.message}", e)
        }

        return Triple(secureDecoders.isNotEmpty(), secureDecoders.size, secureDecoders)
    }

    /**
     * Check if secure surface is available
     * This is harder to detect programmatically - we rely on SurfaceView.setSecure()
     */
    fun isSecureSurfaceAvailable(): Boolean {
        // Secure surfaces are generally available on Android 4.4+
        // The actual test happens when SurfaceView.setSecure(true) is called
        // and Widevine tries to render to it
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
    }

    /**
     * Collect device information for logging/debugging
     */
    private fun collectDeviceInfo(): Map<String, Any> {
        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "brand" to Build.BRAND,
            "product" to Build.PRODUCT,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT,
            "board" to Build.BOARD,
            "hardware" to Build.HARDWARE
        )
    }

    /**
     * Log device information
     */
    private fun logDeviceInfo(info: Map<String, Any>) {
        Log.d(TAG, "")
        Log.d(TAG, "📱 Device Information:")
        Log.d(TAG, "   Manufacturer: ${info["manufacturer"]}")
        Log.d(TAG, "   Model: ${info["model"]}")
        Log.d(TAG, "   Brand: ${info["brand"]}")
        Log.d(TAG, "   Android Version: ${info["androidVersion"]} (SDK ${info["sdkInt"]})")
        Log.d(TAG, "   Hardware: ${info["hardware"]}")
    }

    /**
     * Get a user-friendly message for why offline playback is not available
     */
    fun getUserFriendlyMessage(result: CapabilityResult): String {
        return when {
            result.isEligible -> "Your device supports offline playback"
            result.widevineSecurityLevel == SECURITY_LEVEL_L3 ->
                "Offline downloads are not available on this device. " +
                "Your device uses software-only security (Widevine L3), " +
                "but offline playback requires hardware security (Widevine L1)."
            !result.hasSecureDecoders ->
                "Offline downloads are not available on this device. " +
                "Your device does not have the secure video decoders required " +
                "for protected content playback."
            result.widevineSecurityLevel == SECURITY_LEVEL_UNKNOWN ->
                "Offline downloads are not available on this device. " +
                "Your device does not support the required DRM protection."
            else -> result.reason
        }
    }
}
