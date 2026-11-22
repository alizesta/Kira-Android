package ai.kira.mobile.data.voice

import ai.kira.mobile.domain.model.ShenavaModelVariant
import ai.kira.mobile.domain.repository.ShenavaOfflineSpeechRepository
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.StatFs
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Android implementation of Shenava Koochik using sherpa-onnx's native CPU runtime. */
class SherpaShenavaOfflineSpeechRepository(context: Context) : ShenavaOfflineSpeechRepository {
    private val app = context.applicationContext
    private val modelRoot = File(app.filesDir, "models/shenava")
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES)
        .build()

    private val downloadLock = Any()
    private val recordingStateLock = Any()
    private val recordedAudioLock = Any()
    private val isRecording = AtomicBoolean(false)

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var recordingFailure: String? = null
    private var recordedAudio = ByteArrayOutputStream()

    override fun isModelDownloaded(variant: ShenavaModelVariant): Boolean {
        val directory = modelDirectory(variant)
        val model = File(directory, variant.modelFileName)
        val tokens = File(directory, TOKENS_FILE)
        return model.isFile &&
            model.length() >= variant.minimumModelBytes &&
            tokens.isFile &&
            tokens.length() >= MIN_TOKENS_BYTES
    }

    override fun downloadModel(
        variant: ShenavaModelVariant,
        onProgress: (percent: Int) -> Unit
    ) {
        synchronized(downloadLock) {
            check(!isRecording.get()) { "ابتدا ضبط صدا را متوقف کن." }
            if (isModelDownloaded(variant)) {
                onProgress(100)
                return
            }

            if (!modelRoot.exists() && !modelRoot.mkdirs()) {
                throw IOException("ساخت پوشه مدل‌های محلی کیرا ناموفق بود.")
            }
            val requiredBytes = variant.approximateSizeMb.toLong() * BYTES_PER_MB + DISK_RESERVE_BYTES
            val availableBytes = StatFs(modelRoot.absolutePath).availableBytes
            require(availableBytes >= requiredBytes) {
                "فضای خالی کافی نیست؛ برای نسخه ${variant.storageKey.uppercase()} حدود " +
                    "${variant.approximateSizeMb + DISK_RESERVE_MB} مگابایت فضای آزاد لازم است."
            }

            val directory = modelDirectory(variant)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("ساخت پوشه ذخیره مدل شنوا ناموفق بود.")
            }
            val modelFile = File(directory, variant.modelFileName)
            val tokensFile = File(directory, TOKENS_FILE)
            val modelPart = File(directory, variant.modelFileName + ".part")
            val tokensPart = File(directory, TOKENS_FILE + ".part")
            modelPart.delete()
            tokensPart.delete()

            try {
                downloadFile(
                    url = modelUrl(variant, variant.modelFileName),
                    destination = modelPart,
                    minimumBytes = variant.minimumModelBytes,
                    maximumBytes = MAX_MODEL_DOWNLOAD_BYTES
                ) { downloaded, total ->
                    val percent = if (total > 0L) {
                        ((downloaded * 96L) / total).toInt().coerceIn(0, 96)
                    } else {
                        5
                    }
                    onProgress(percent)
                }
                onProgress(97)
                downloadFile(
                    url = modelUrl(variant, TOKENS_FILE),
                    destination = tokensPart,
                    minimumBytes = MIN_TOKENS_BYTES,
                    maximumBytes = MAX_TOKENS_BYTES
                ) { _, _ -> onProgress(99) }

                check(modelPart.length() >= variant.minimumModelBytes) {
                    "فایل مدل شنوا ناقص دانلود شده است."
                }
                check(tokensPart.length() >= MIN_TOKENS_BYTES) {
                    "فایل tokens شنوا ناقص دانلود شده است."
                }

                if (modelFile.exists() && !modelFile.delete()) {
                    throw IOException("جایگزینی فایل مدل قبلی ناموفق بود.")
                }
                if (tokensFile.exists() && !tokensFile.delete()) {
                    throw IOException("جایگزینی فایل tokens قبلی ناموفق بود.")
                }
                if (!modelPart.renameTo(modelFile)) {
                    throw IOException("ذخیره فایل مدل شنوا ناموفق بود.")
                }
                if (!tokensPart.renameTo(tokensFile)) {
                    modelFile.delete()
                    throw IOException("ذخیره فایل tokens شنوا ناموفق بود.")
                }
                check(isModelDownloaded(variant)) { "اعتبارسنجی فایل‌های مدل شنوا ناموفق بود." }
                onProgress(100)
            } catch (error: Exception) {
                modelPart.delete()
                tokensPart.delete()
                throw error
            }
        }
    }

    override fun startRecording() {
        synchronized(recordingStateLock) {
            check(isRecording.compareAndSet(false, true)) {
                "ضبط صدای شنوا از قبل فعال است."
            }
            val minimumBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minimumBuffer <= 0) {
                isRecording.set(false)
                throw IllegalStateException("ضبط صدای ۱۶ کیلوهرتز روی این دستگاه پشتیبانی نمی‌شود.")
            }

            val audioRecord = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minimumBuffer * 2, SAMPLE_RATE))
                    .build()
            } catch (error: Exception) {
                isRecording.set(false)
                throw error
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                isRecording.set(false)
                audioRecord.release()
                throw IllegalStateException("میکروفون قابل راه‌اندازی نیست.")
            }

            synchronized(recordedAudioLock) {
                recordedAudio = ByteArrayOutputStream()
            }
            recordingFailure = null
            recorder = audioRecord
            try {
                audioRecord.startRecording()
                check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "شروع ضبط میکروفون ناموفق بود."
                }
                captureThread = Thread(
                    { captureLoop(audioRecord) },
                    "Kira-Shenava-Microphone"
                ).apply {
                    isDaemon = true
                    start()
                }
            } catch (error: Exception) {
                isRecording.set(false)
                recorder = null
                runCatching { audioRecord.stop() }
                audioRecord.release()
                throw error
            }
        }
    }

    override fun stopRecordingAndTranscribe(variant: ShenavaModelVariant): String {
        check(isModelDownloaded(variant)) { "مدل انتخاب‌شده شنوا دانلود نشده است." }
        val audioBytes = finishRecording()
        val failure = recordingFailure
        if (!failure.isNullOrBlank()) throw IOException(failure)
        require(audioBytes.size >= MIN_AUDIO_BYTES) {
            "صدای ضبط‌شده خیلی کوتاه است؛ حداقل یک ثانیه صحبت کن."
        }
        val samples = pcm16ToFloat(audioBytes)
        return recognize(samples, variant)
    }

    override fun cancelRecording() {
        runCatching { finishRecording() }
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val buffer = ByteArray(RECORD_BUFFER_BYTES)
        try {
            while (isRecording.get()) {
                val count = audioRecord.read(
                    buffer,
                    0,
                    buffer.size,
                    AudioRecord.READ_BLOCKING
                )
                if (count <= 0) {
                    if (isRecording.get()) {
                        recordingFailure = "خواندن صدای میکروفون ناموفق بود (کد $count)."
                        isRecording.set(false)
                    }
                    break
                }

                synchronized(recordedAudioLock) {
                    val remaining = MAX_RECORDING_BYTES - recordedAudio.size()
                    val safeCount = min(count, remaining).let { it - (it % 2) }
                    if (safeCount > 0) recordedAudio.write(buffer, 0, safeCount)
                    if (safeCount < count || recordedAudio.size() >= MAX_RECORDING_BYTES) {
                        isRecording.set(false)
                    }
                }
            }
        } catch (error: Exception) {
            if (isRecording.get()) {
                recordingFailure = "ضبط صدا متوقف شد: ${error.message ?: error.javaClass.simpleName}"
                isRecording.set(false)
            }
        }
    }

    private fun finishRecording(): ByteArray {
        val audioRecord: AudioRecord?
        val thread: Thread?
        synchronized(recordingStateLock) {
            isRecording.set(false)
            audioRecord = recorder
            thread = captureThread
            recorder = null
            captureThread = null
        }

        if (audioRecord == null) {
            synchronized(recordedAudioLock) {
                val pending = recordedAudio.toByteArray()
                recordedAudio.reset()
                return pending
            }
        }

        var stopFailure: IOException? = null
        try {
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            }
            if (thread != null && thread !== Thread.currentThread()) {
                try {
                    thread.join(RECORDING_STOP_TIMEOUT_MS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    stopFailure = IOException("توقف ضبط میکروفون به‌دلیل لغو عملیات کامل نشد.", interrupted)
                }
                if (thread.isAlive && stopFailure == null) {
                    stopFailure = IOException("توقف ضبط میکروفون کامل نشد.")
                }
            }
        } finally {
            // Release the hardware even if joining the capture thread was interrupted or timed out.
            runCatching { audioRecord.release() }
        }
        stopFailure?.let { throw it }

        return synchronized(recordedAudioLock) {
            val bytes = recordedAudio.toByteArray()
            recordedAudio.reset()
            bytes
        }
    }

    private fun recognize(samples: FloatArray, variant: ShenavaModelVariant): String {
        val directory = modelDirectory(variant)
        val modelFile = File(directory, variant.modelFileName)
        val tokensFile = File(directory, TOKENS_FILE)
        check(modelFile.isFile && tokensFile.isFile) {
            "فایل‌های مدل شنوا در حافظه محلی پیدا نشدند؛ دوباره دانلود کن."
        }

        val modelConfig = OfflineModelConfig(
            nemo = OfflineNemoEncDecCtcModelConfig(model = modelFile.absolutePath),
            tokens = tokensFile.absolutePath,
            numThreads = minOf(MAX_INFERENCE_THREADS, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)),
            debug = false,
            provider = "cpu"
        )
        val recognizerConfig = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )

        val recognizer = OfflineRecognizer(config = recognizerConfig)
        try {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                return recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        } finally {
            // Release the native model after each utterance to avoid retaining hundreds of MB in RAM.
            recognizer.release()
        }
    }

    private fun pcm16ToFloat(bytes: ByteArray): FloatArray {
        val sampleCount = bytes.size / 2
        return FloatArray(sampleCount) { index ->
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            val signed = (high shl 8) or low
            signed.toShort().toFloat() / 32768.0f
        }
    }

    private fun downloadFile(
        url: String,
        destination: File,
        minimumBytes: Long,
        maximumBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Kira-Android")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("دانلود مدل شنوا ناموفق بود (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("پاسخ دانلود شنوا خالی بود.")
            val totalBytes = body.contentLength()
            if (totalBytes > maximumBytes) {
                throw IOException("حجم فایل دریافتی از حد مجاز بیشتر است.")
            }

            var downloaded = 0L
            body.byteStream().use { input ->
                FileOutputStream(destination, false).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        downloaded += count
                        if (downloaded > maximumBytes) {
                            throw IOException("حجم فایل دانلودی از حد مجاز بیشتر شد.")
                        }
                        output.write(buffer, 0, count)
                        onProgress(downloaded, totalBytes)
                    }
                    output.fd.sync()
                }
            }

            if (totalBytes > 0L && downloaded != totalBytes) {
                throw IOException("دانلود فایل شنوا ناقص است؛ اتصال را بررسی و دوباره تلاش کن.")
            }
            if (downloaded < minimumBytes) {
                throw IOException("فایل دانلودشده معتبر نیست یا ناقص است.")
            }
        }
    }

    private fun modelDirectory(variant: ShenavaModelVariant): File =
        File(modelRoot, variant.storageKey)

    private fun modelUrl(variant: ShenavaModelVariant, filename: String): String =
        "https://huggingface.co/${variant.repositoryId}/resolve/main/${filename}?download=true"

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val TOKENS_FILE = "tokens.txt"
        const val MIN_TOKENS_BYTES = 1_000L
        const val MAX_TOKENS_BYTES = 2L * 1024L * 1024L
        const val MAX_MODEL_DOWNLOAD_BYTES = 600L * 1024L * 1024L
        const val BYTES_PER_MB = 1024L * 1024L
        const val DISK_RESERVE_MB = 24
        const val DISK_RESERVE_BYTES = DISK_RESERVE_MB.toLong() * BYTES_PER_MB
        const val MAX_INFERENCE_THREADS = 4
        const val RECORD_BUFFER_BYTES = 4_096
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val MAX_RECORDING_BYTES = SAMPLE_RATE * 2 * 120 // 16 kHz, mono, 16-bit, two minutes
        const val MIN_AUDIO_BYTES = SAMPLE_RATE * 2 // Require at least one second of speech.
        const val RECORDING_STOP_TIMEOUT_MS = 5_000L
    }
}
