package com.example.core.codec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

data class CodecCapability(
    val mimeType: String,
    val name: String,
    val isEncoder: Boolean,
    val isHardwareAccelerated: Boolean,
    val maxResolutionWidth: Int,
    val maxResolutionHeight: Int,
    val maxBitrateBps: Int,
    val supportedColorFormats: List<Int> = emptyList()
)

object DeviceCodecInspector {

    private var cachedCapabilities: List<CodecCapability>? = null

    fun getSupportedEncoders(): List<CodecCapability> {
        cachedCapabilities?.let { return it }

        val codecList = try {
            MediaCodecList(MediaCodecList.ALL_CODECS)
        } catch (e: Exception) {
            return emptyList()
        }

        val results = mutableListOf<CodecCapability>()

        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue

            val isHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            }

            for (type in info.supportedTypes) {
                try {
                    val caps = info.getCapabilitiesForType(type)
                    val videoCaps = caps.videoCapabilities

                    val (maxWidth, maxHeight, maxBitrate) = if (videoCaps != null) {
                        Triple(
                            videoCaps.supportedWidths.upper ?: 1920,
                            videoCaps.supportedHeights.upper ?: 1080,
                            videoCaps.bitrateRange.upper ?: 10_000_000
                        )
                    } else {
                        Triple(1920, 1080, 5_000_000)
                    }

                    results.add(
                        CodecCapability(
                            mimeType = type,
                            name = info.name,
                            isEncoder = true,
                            isHardwareAccelerated = isHardware,
                            maxResolutionWidth = maxWidth,
                            maxResolutionHeight = maxHeight,
                            maxBitrateBps = maxBitrate,
                            supportedColorFormats = caps.colorFormats.toList()
                        )
                    )
                } catch (e: Exception) {
                    // Ignore individual codec inspection error
                }
            }
        }

        cachedCapabilities = results
        return results
    }

    fun isH265Supported(): Boolean {
        return getSupportedEncoders().any { it.mimeType.equals("video/hevc", ignoreCase = true) }
    }

    fun isH264Supported(): Boolean {
        return getSupportedEncoders().any { it.mimeType.equals("video/avc", ignoreCase = true) }
    }

    fun isVp9Supported(): Boolean {
        return getSupportedEncoders().any { it.mimeType.equals("video/x-vnd.on2.vp9", ignoreCase = true) }
    }

    fun isAacEncoderSupported(): Boolean {
        return getSupportedEncoders().any { it.mimeType.equals("audio/mp4a-latm", ignoreCase = true) }
    }

    fun getMaxVideoResolution(): Pair<Int, Int> {
        val h264Encoders = getSupportedEncoders().filter { it.mimeType.equals("video/avc", ignoreCase = true) }
        val maxWidth = h264Encoders.maxOfOrNull { it.maxResolutionWidth } ?: 1920
        val maxHeight = h264Encoders.maxOfOrNull { it.maxResolutionHeight } ?: 1080
        return Pair(maxWidth, maxHeight)
    }
}
