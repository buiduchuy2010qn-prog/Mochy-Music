// MusicRecognitionService.kt

package com.mochyy.mochymusic.recognition

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.music.shazamkit.Shazam
import com.music.shazamkit.models.RecognitionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteOrder

object MusicRecognitionService {

    private const val RECORDING_SAMPLE_RATE = 44100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private const val RECORDING_DURATION_MS = 10000L

    private val _recognitionStatus = MutableStateFlow<RecognitionStatus>(RecognitionStatus.Ready)
    val recognitionStatus: StateFlow<RecognitionStatus> = _recognitionStatus.asStateFlow()

    fun hasRecordPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun recognize(context: Context): RecognitionStatus = withContext(Dispatchers.IO) {
        if (!hasRecordPermission(context)) {
            val err = RecognitionStatus.Error("Chưa cấp quyền sử dụng micro")
            _recognitionStatus.value = err
            return@withContext err
        }

        _recognitionStatus.value = RecognitionStatus.Listening

        try {
            val audioData = recordAudio()
            recognizePcmBytes(
                pcmData = audioData,
                sampleRate = RECORDING_SAMPLE_RATE,
                channelCount = 1,
            )
        } catch (e: CancellationException) {
            _recognitionStatus.value = RecognitionStatus.Ready
            throw e
        } catch (e: Exception) {
            val status = RecognitionStatus.Error(localizeRecognitionError(e))
            _recognitionStatus.value = status
            status
        }
    }

    /**
     * Nhận diện bài hát trực tiếp từ luồng PCM 16-bit LE (không mở micro),
     * tự động gộp về mono nếu là đa kênh và chuẩn hóa về 16 kHz cho Shazam.
     */
    suspend fun recognizePcmBytes(
        pcmData: ByteArray,
        sampleRate: Int,
        channelCount: Int,
    ): RecognitionStatus = withContext(Dispatchers.IO) {
        if (pcmData.size < 2 || sampleRate <= 0 || channelCount <= 0) {
            val err = RecognitionStatus.Error("Không có dữ liệu âm thanh để nhận diện")
            _recognitionStatus.value = err
            return@withContext err
        }

        _recognitionStatus.value = RecognitionStatus.Processing

        try {
            val monoData = if (channelCount > 1) {
                downmixToMono16Bit(pcmData, channelCount)
            } else {
                val alignedLen = pcmData.size - (pcmData.size % 2)
                if (alignedLen == pcmData.size) pcmData else pcmData.copyOf(alignedLen)
            }

            if (monoData.size < 2) {
                val err = RecognitionStatus.Error("Đoạn âm thanh quá ngắn để nhận diện")
                _recognitionStatus.value = err
                return@withContext err
            }

            val decodedAudio = DecodedAudio(
                data = monoData,
                channelCount = 1,
                sampleRate = sampleRate,
                pcmEncoding = AUDIO_FORMAT,
            )

            val resampledAudio = AudioResampler.resample(
                decodedAudio,
                VibraSignature.REQUIRED_SAMPLE_RATE,
            ).getOrElse { error ->
                val err = RecognitionStatus.Error(
                    "Không thể xử lý âm thanh: ${error.message ?: "lỗi không xác định"}",
                )
                _recognitionStatus.value = err
                return@withContext err
            }

            if (
                resampledAudio.channelCount != 1 ||
                resampledAudio.sampleRate != VibraSignature.REQUIRED_SAMPLE_RATE ||
                resampledAudio.pcmEncoding != AudioFormat.ENCODING_PCM_16BIT ||
                ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN ||
                resampledAudio.data.isEmpty() ||
                resampledAudio.data.size % 2 != 0
            ) {
                val err = RecognitionStatus.Error("Định dạng âm thanh không hợp lệ để nhận diện")
                _recognitionStatus.value = err
                return@withContext err
            }

            val signature = try {
                VibraSignature.fromI16(resampledAudio.data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = RecognitionStatus.Error(
                    "Không thể tạo dấu vân tay âm thanh: ${e.message ?: "lỗi không xác định"}",
                )
                _recognitionStatus.value = err
                return@withContext err
            }

            val sampleDurationMs = (resampledAudio.data.size / 2) * 1000L / VibraSignature.REQUIRED_SAMPLE_RATE
            val result = Shazam.recognize(signature, sampleDurationMs)

            result.fold(
                onSuccess = { recognitionResult ->
                    _recognitionStatus.value = RecognitionStatus.Success(recognitionResult)
                },
                onFailure = { error ->
                    val message = error.message.orEmpty()
                    _recognitionStatus.value = if (message.contains("No match", ignoreCase = true)) {
                        RecognitionStatus.NoMatch("Không tìm thấy bài hát phù hợp. Hãy thử lại với âm thanh rõ hơn.")
                    } else {
                        RecognitionStatus.Error(localizeRecognitionError(error))
                    }
                },
            )

            _recognitionStatus.value
        } catch (e: CancellationException) {
            _recognitionStatus.value = RecognitionStatus.Ready
            throw e
        } catch (e: Exception) {
            val status = RecognitionStatus.Error(localizeRecognitionError(e))
            _recognitionStatus.value = status
            status
        }
    }

    private fun downmixToMono16Bit(pcmData: ByteArray, channelCount: Int): ByteArray {
        val bytesPerFrame = channelCount * 2
        val frameCount = pcmData.size / bytesPerFrame
        if (frameCount <= 0) return ByteArray(0)
        val out = ByteArray(frameCount * 2)
        for (frame in 0 until frameCount) {
            var sum = 0
            val frameBase = frame * bytesPerFrame
            for (ch in 0 until channelCount) {
                val idx = frameBase + ch * 2
                val lo = pcmData[idx].toInt() and 0xFF
                val hi = pcmData[idx + 1].toInt()
                val sample = (hi shl 8) or lo
                sum += sample
            }
            val mono = (sum / channelCount).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val outIdx = frame * 2
            out[outIdx] = (mono and 0xFF).toByte()
            out[outIdx + 1] = ((mono shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun localizeRecognitionError(error: Throwable): String {
        val raw = error.message.orEmpty().trim()
        return when {
            raw.contains("No match", ignoreCase = true) ->
                "Không tìm thấy bài hát phù hợp. Hãy thử lại với âm thanh rõ hơn."
            raw.contains("Too many requests", ignoreCase = true) ->
                "Dịch vụ Shazam đang quá tải, vui lòng thử lại sau ít phút."
            raw.contains("temporarily unavailable", ignoreCase = true) ->
                "Dịch vụ Shazam tạm thời không khả dụng."
            raw.contains("Request queue is full", ignoreCase = true) ->
                "Hàng đợi nhận diện đang đầy, vui lòng chờ giây lát."
            error is IOException ||
                raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ->
                "Không thể kết nối máy chủ Shazam. Hãy kiểm tra kết nối mạng."
            raw.contains("Recognition failed", ignoreCase = true) || raw.isBlank() ->
                "Nhận diện bài hát thất bại"
            else -> raw
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordAudio(): ByteArray = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            RECORDING_SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBuffer, RECORDING_SAMPLE_RATE / 5 * 2)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RECORDING_SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { audioRecord.release() }
            throw IllegalStateException("Không thể khởi tạo micro để thu âm")
        }

        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        val startTime = System.currentTimeMillis()

        try {
            audioRecord.startRecording()

            while (System.currentTimeMillis() - startTime < RECORDING_DURATION_MS && isActive) {
                val bytesRead = audioRecord.read(buffer, 0, bufferSize)
                if (bytesRead > 0) {
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
        } finally {
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
        }

        outputStream.toByteArray()
    }

    fun reset() {
        _recognitionStatus.value = RecognitionStatus.Ready
    }
}
