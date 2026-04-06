package com.carlink.audio

import android.media.AudioFormat

/**
 * Audio format configuration matching CPC200-CCPA protocol decode types.
 */
data class AudioFormatConfig(
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int = 16,
) {
    val channelConfig: Int
        get() =
            if (channelCount == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

    val encoding: Int
        get() = AudioFormat.ENCODING_PCM_16BIT
}

/**
 * Predefined audio formats from CPC200-CCPA protocol.
 *
 * Maps decode_type values to audio format specifications for playback.
 *
 * decode_type serves dual purposes:
 * 1. Audio format specification (sample rate, channels, bit depth)
 * 2. Semantic context for the audio command (discovered Dec 2025 capture research)
 *
 * Semantic meanings (from CPC200 adapter capture analysis):
 * - decode_type=2: Stop/cleanup operations (seen with MEDIA_STOP, PHONECALL_STOP)
 * - decode_type=4: Standard CarPlay audio output (MEDIA_START, NAVI_*, ALERT_*, OUTPUT_*)
 * - decode_type=5: Mic/input related operations (SIRI_*, PHONECALL_START, INPUT_*, INCOMING_CALL_INIT)
 *
 * Note: decode_type appears in the 13-byte audio command packet:
 *   [decode_type:4][volume:4][audio_type:4][command:1]
 */
object AudioFormats {
    val FORMAT_1 = AudioFormatConfig(48000, 2) // Music stereo
    val FORMAT_2 = AudioFormatConfig(48000, 2) // Commands/stop signals (decode_type=2, no PCM at 48kHz default)
    val FORMAT_3 = AudioFormatConfig(8000, 1) // Phone calls
    val FORMAT_4 = AudioFormatConfig(48000, 2) // High-quality
    val FORMAT_5 = AudioFormatConfig(16000, 1) // Siri/voice
    val FORMAT_6 = AudioFormatConfig(24000, 1) // Enhanced voice
    val FORMAT_7 = AudioFormatConfig(16000, 2) // Stereo voice

    fun fromDecodeType(decodeType: Int): AudioFormatConfig =
        when (decodeType) {
            1 -> FORMAT_1
            2 -> FORMAT_2
            3 -> FORMAT_3
            4 -> FORMAT_4
            5 -> FORMAT_5
            6 -> FORMAT_6
            7 -> FORMAT_7
            else -> FORMAT_4 // Default to high-quality
        }
}
