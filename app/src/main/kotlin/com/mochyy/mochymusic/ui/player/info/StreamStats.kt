// StreamStats.kt

package com.mochyy.mochymusic.ui.player.info

import androidx.media3.common.C
import com.mochyy.mochymusic.db.entities.FormatEntity
import com.mochyy.mochymusic.playback.PlayingFrom
import com.mochyy.mochymusic.playback.StreamDetails
import com.mochyy.mochymusic.playback.StreamFacts
import com.mochyy.mochymusic.playback.StreamInspector
import com.mochyy.mochymusic.playback.audio.AudioOutputs
import com.mochyy.mochymusic.playback.audio.SincResampler
import java.util.Locale
import kotlin.math.log2
import kotlin.math.roundToInt

/** one card of stats for nerds: a title and only the lines that have a real value */
data class StatCard(val title: String, val lines: List<Pair<String, String>>)

/** what the effects are doing, read once per sheet frame from audio lab and the settings */
data class EffectsSnapshot(
    val equalizerBands: Int? = null,
    val spatial: Boolean = false,
    val mono: Boolean = false,
    val reverb: Boolean = false,
    val loudness: Boolean = false,
    val volumeCurve: Boolean = false,
    val normalize: Boolean = false,
    val skipSilence: Boolean = false,
    val haptics: Boolean = false,
    val transitions: String? = null,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val hiResWanted: Boolean = false,
    val floatAllowed: Boolean = false,
)

data class StatsInput(
    val mediaId: String,
    val details: StreamDetails,
    val facts: StreamFacts?,
    /** youtube's record of its stream; null when something else plays the song */
    val youtubeFormat: FormatEntity?,
    val from: PlayingFrom?,
    val durationMs: Long,
    val playerVolume: Float,
    val decoderHardware: Boolean?,
    val device: AudioOutputs.Device?,
    val mixer: AudioOutputs.Mixer?,
    /** the phone's own mixer rate, which the speaker and the headphone jack run at */
    val systemMixerRate: Int?,
    val castingTo: String?,
    val effects: EffectsSnapshot,
)

/** format, decoder, resampler, dsp, output: the song's road to the speaker, nothing guessed */
fun buildStatCards(input: StatsInput): List<StatCard> = listOf(
    formatCard(input),
    decoderCard(input),
    resamplerCard(input),
    dspCard(input),
    outputCard(input),
).filter { it.lines.isNotEmpty() }

private fun formatCard(input: StatsInput): StatCard {
    val d = input.details
    val codec = input.facts?.codec ?: StreamFacts.codecOf(d.sampleMime, d.codecs)
    val lossless = codec in LosslessCodecs
    val sampleRate = d.sampleRate ?: input.facts?.sampleRate ?: input.youtubeFormat?.sampleRate
    val fileSize = d.fileSizeBytes ?: input.youtubeFormat?.contentLength?.takeIf { it > 0 }
    val statedBitrate = d.bitrate ?: input.youtubeFormat?.bitrate?.takeIf { it > 0 }
    // a flac file says nothing of its bitrate: its size over its length does
    val averageBitrate = if (statedBitrate == null && fileSize != null && input.durationMs > 0) {
        (fileSize * 8_000 / input.durationMs).toInt()
    } else null
    return StatCard(
        title = codec?.let(::formatTitle) ?: "Định dạng",
        lines = listOfNotNull(
            d.sourceBitDepth?.takeIf { lossless }?.let { "Độ sâu bit" to "$it-bit" },
            sampleRate?.let { "Tần số lấy mẫu" to kHz(it) },
            statedBitrate?.let { "Tốc độ bit" to kbps(it) } ?: averageBitrate?.let { "Tốc độ bit" to "~${kbps(it)} TB" },
            d.channels?.let { "Kênh âm thanh" to channelName(it) },
            containerName(d.containerMime)?.let { "Container" to it },
            "Mã phương tiện" to input.mediaId,
            input.from?.matchedId?.let { "Khớp với" to "${input.from.name} #$it" },
            "Âm lượng" to "${(input.playerVolume * 100).roundToInt()}%",
            input.youtubeFormat?.loudnessDb?.let { "Độ lớn âm" to String.format(Locale.US, "%+.1f dB", it) },
            fileSize?.let { "Dung lượng tệp" to megabytes(it) },
        ),
    )
}

private fun decoderCard(input: StatsInput): StatCard {
    val d = input.details
    return StatCard(
        title = "Bộ giải mã",
        lines = listOfNotNull(
            d.decoderName?.let { "Bộ giải mã" to it },
            input.decoderHardware?.let { "Chạy trên" to if (it) "Phần cứng" else "Phần mềm" },
            d.sampleMime?.let { "Định dạng" to it },
            d.codecs?.takeIf { it.isNotBlank() }?.let { "Codec" to it },
            d.decodedEncoding?.let { "Đầu ra" to pcmName(it) },
            d.decodedSampleRate?.let { "Tần số đầu ra" to kHz(it) },
            d.decodedChannels?.let { "Kênh âm thanh" to channelName(it) },
            d.decoderInitMs?.takeIf { it > 0 }?.let { "Khởi chạy" to "$it ms" },
        ),
    )
}

private fun resamplerCard(input: StatsInput): StatCard {
    val d = input.details
    val effects = input.effects
    if (d.resampledFrom != null && d.resampledTo != null) return mochyResamplerCard(d, effects)
    val inRate = d.decodedSampleRate ?: d.sampleRate ?: return StatCard("Bộ lấy mẫu lại", emptyList())
    val outRate = input.mixer?.sampleRate ?: input.systemMixerRate?.takeIf { input.device?.kind in FixedRateKinds && input.castingTo == null }
    val timeStretch = effects.speed != 1f || effects.pitch != 1f
    val resampling = outRate != null && outRate != inRate
    val type = when {
        input.castingTo != null -> "Bộ xử lý của thiết bị nhận"
        timeStretch -> "Sonic co giãn thời gian, qua bộ trộn Android"
        outRate == null -> "Bộ trộn Android (khi cần)"
        resampling -> "Bộ trộn của Android"
        else -> "Không, phát ở tần số nguyên bản"
    }
    return StatCard(
        title = "Bộ lấy mẫu lại",
        lines = listOfNotNull(
            "Kiểu xử lý" to type,
            outRate?.let { "Tần số vào/ra" to if (resampling) "${kHz(inRate)} → ${kHz(it)}" else "${kHz(inRate)} giữ nguyên" },
            // the filter has to stop at the lower rate's nyquist, half of it
            outRate?.takeIf { resampling }?.let { "Tần số cắt" to "~${kHz(minOf(inRate, it) / 2)}" },
            line("Chất lượng", "Nguyên bản".takeIf { !resampling && outRate != null && !timeStretch }),
            effects.speed.takeIf { it != 1f }?.let { "Speed" to String.format(Locale.US, "%.2f×", it) },
            effects.pitch.takeIf { it != 1f }?.let { "Cao độ" to String.format(Locale.US, "%+.1f bán cung", 12 * log2(it)) },
        ),
    )
}

// mochy's own filter did the work, so android's mixer is handed the rate it already runs at
private fun mochyResamplerCard(d: StreamDetails, effects: EffectsSnapshot): StatCard = StatCard(
    title = "Bộ lấy mẫu lại",
    lines = listOfNotNull(
        "Kiểu xử lý" to "Mochy Hi-Fi, sinc cửa sổ (Kaiser)",
        "Tần số vào/ra" to "${kHz(d.resampledFrom!!)} → ${kHz(d.resampledTo!!)}",
        d.resamplerPassbandHz?.let { "Dải thông" to "phẳng tới ${kHz(it.toInt())}" },
        "Tần số cắt" to kHz(minOf(d.resampledFrom, d.resampledTo) / 2),
        "Quality" to "−${SincResampler.StopbandDb.toInt()} dB stopband, 32-bit float",
        d.resamplerTaps?.let { "Số bậc lọc" to "$it mỗi mẫu" },
        "Bộ trộn Android" to "Không lấy mẫu lại, khớp tần số",
        effects.speed.takeIf { it != 1f }?.let { "Speed" to String.format(Locale.US, "%.2f×", it) },
    ),
)

private fun dspCard(input: StatsInput): StatCard {
    val d = input.details
    val e = input.effects
    val chain = listOfNotNull(
        e.equalizerBands?.let { "Bộ chỉnh âm ($it dải)" },
        "Âm thanh không gian".takeIf { e.spatial },
        "Mono".takeIf { e.mono },
        "Tiếng vang".takeIf { e.reverb },
        "Tăng âm lượng".takeIf { e.loudness },
        "Đường cong âm lượng".takeIf { e.volumeCurve },
        e.transitions,
        "Bỏ qua khoảng lặng".takeIf { e.skipSilence },
        "Rung theo nhạc".takeIf { e.haptics },
    )
    val floatPath = d.outputEncoding == C.ENCODING_PCM_FLOAT
    return StatCard(
        title = "DSP",
        lines = listOfNotNull(
            d.outputEncoding?.let { "Định dạng PCM" to pcmName(it) },
            d.outputSampleRate?.let { "Tần số lấy mẫu" to kHz(it) },
            "Hiệu ứng" to chain.joinToString(", ").ifEmpty { "Không có, âm thanh nguyên bản" },
            line("Nhiễu nền (Dither)", "TPDF về 16-bit".takeIf { d.dithered }),
            line("Chuẩn hóa âm lượng", "Đang bật".takeIf { e.normalize }),
            when {
                floatPath -> "Đường truyền Hi-Res" to "32-bit float xuyên suốt"
                e.hiResWanted && !e.floatAllowed -> "Đường truyền Hi-Res" to "Tạm dừng khi bật hiệu ứng"
                else -> null
            },
        ),
    )
}

private fun outputCard(input: StatsInput): StatCard {
    val d = input.details
    val device = input.device
    val frameBytes = (channelCountOf(d.outputChannelConfig) ?: 2) * (bytesPerSample(d.outputEncoding) ?: 2)
    val bufferMs = d.outputBufferBytes?.let { bytes -> d.outputSampleRate?.takeIf { it > 0 }?.let { rate -> bytes * 1000L / (rate.toLong() * frameBytes) } }
    val mixer = input.mixer
    return StatCard(
        title = "Đầu ra âm thanh",
        lines = listOfNotNull(
            input.castingTo?.let { "Đang truyền tới" to it },
            device?.let { "Thiết bị" to deviceName(it) },
            device?.let { "Đường truyền" to routeName(it.kind) },
            device?.takeIf { it.kind == AudioOutputs.Kind.USB || it.kind == AudioOutputs.Kind.HDMI }?.let { dev ->
                listOfNotNull(dev.maxSampleRate?.let(::kHz), dev.bitDepths.maxOrNull()?.let { "$it-bit" })
                    .takeIf { it.isNotEmpty() }?.let { "Hỗ trợ tối đa" to it.joinToString(" · ") }
            },
            mixer?.let {
                "Bộ trộn DAC" to (if (it.bitPerfect) "Bit-perfect, " else "Khóa theo bài hát, ") +
                    listOfNotNull(kHz(it.sampleRate), StreamInspector.bitDepthOf(it.encoding)?.let { b -> "$b-bit" }).joinToString(" ")
            },
            d.outputEncoding?.let { enc ->
                "Luồng âm thanh" to listOfNotNull(pcmShort(enc), d.outputSampleRate?.let(::kHz), channelCountOf(d.outputChannelConfig)?.let(::channelName)).joinToString(" · ")
            },
            bufferMs?.takeIf { it > 0 }?.let { "Bộ đệm" to "~$it ms" },
            line("Giải mã phần cứng", "Đang bật".takeIf { d.offload }),
            line("Đường hầm (Tunneling)", "Đang bật".takeIf { d.tunneling }),
            d.underruns.takeIf { it > 0 }?.let { "Tràn bộ đệm" to "$it" },
        ),
    )
}

// a line with nothing to say isn't drawn at all, rather than drawn as "-"
private fun line(label: String, value: String?): Pair<String, String>? = value?.let { label to it }

private val LosslessCodecs = setOf("FLAC", "ALAC", "PCM")
private val FixedRateKinds = setOf(AudioOutputs.Kind.PHONE, AudioOutputs.Kind.WIRED)

// a dot and the codec like the mock (".flac" shown as .Flac), but acronyms said letter by letter stay in capitals
private fun formatTitle(codec: String): String =
    if (codec in setOf("FLAC", "OPUS", "VORBIS", "ALAC")) ".${codec.lowercase().replaceFirstChar { it.uppercase() }}" else ".$codec"

private fun kHz(rate: Int): String {
    val khz = rate / 1000.0
    return if (khz % 1.0 == 0.0) "${khz.toInt()} kHz" else String.format(Locale.US, "%.${if ((rate % 100) == 0) 1 else 2}f kHz", khz)
}

private fun kbps(bitsPerSecond: Int): String = String.format(Locale.getDefault(), "%,d kbps", (bitsPerSecond + 500) / 1000)

private fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / 1_048_576.0)

private fun channelName(count: Int): String = when (count) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1 surround"
    8 -> "7.1 surround"
    else -> "$count channels"
}

private fun containerName(mime: String?): String? = when (mime?.lowercase()) {
    null -> null
    "audio/webm", "video/webm" -> "WebM"
    "audio/mp4", "video/mp4" -> "MP4"
    "audio/flac" -> "FLAC"
    "audio/ogg" -> "Ogg"
    "audio/mpeg" -> "MP3"
    "audio/wav", "audio/x-wav" -> "WAV"
    "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "HLS"
    "application/dash+xml" -> "DASH"
    else -> mime
}

private fun pcmName(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_16BIT -> "16-bit integer PCM"
    C.ENCODING_PCM_24BIT -> "24-bit integer PCM"
    C.ENCODING_PCM_32BIT -> "32-bit integer PCM"
    C.ENCODING_PCM_FLOAT -> "32-bit float PCM"
    C.ENCODING_PCM_8BIT -> "8-bit PCM"
    else -> "Encoded, passed through"
}

private fun pcmShort(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_FLOAT -> "32-bit float"
    else -> StreamInspector.bitDepthOf(encoding)?.let { "$it-bit" } ?: "Encoded"
}

private fun bytesPerSample(encoding: Int?): Int? = when (encoding) {
    C.ENCODING_PCM_16BIT -> 2
    C.ENCODING_PCM_24BIT -> 3
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
    else -> null
}

private fun channelCountOf(channelConfig: Int?): Int? = channelConfig?.let { Integer.bitCount(it).takeIf { n -> n > 0 } }

private fun routeName(kind: AudioOutputs.Kind): String = when (kind) {
    AudioOutputs.Kind.PHONE -> "Loa trong máy"
    AudioOutputs.Kind.WIRED -> "Có dây"
    AudioOutputs.Kind.USB -> "Âm thanh USB"
    AudioOutputs.Kind.BLUETOOTH -> "Bluetooth"
    AudioOutputs.Kind.HDMI -> "HDMI"
}

internal fun deviceName(device: AudioOutputs.Device): String = device.name.ifBlank {
    when (device.kind) {
        AudioOutputs.Kind.PHONE -> "Điện thoại này"
        AudioOutputs.Kind.WIRED -> "Tai nghe có dây"
        AudioOutputs.Kind.USB -> "USB DAC"
        AudioOutputs.Kind.BLUETOOTH -> "Thiết bị Bluetooth"
        AudioOutputs.Kind.HDMI -> "HDMI"
    }
}
