package ai.kira.mobile.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Offline speech output. Kitten is used for English; Android's installed TTS
 * engine is used for Persian because the bundled Kitten model is English-only.
 */
class KiraSpeechService(context: Context) {
    private val app = context.applicationContext
    private val modelDir = File(app.filesDir, "models/kitten-nano-en-v0_1-fp16")
    private val archive = File(app.cacheDir, "kitten-nano-en-v0_1-fp16.tar.bz2")
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.MINUTES)
        .build()
    private val ttsReady = CountDownLatch(1)
    @Volatile private var systemTtsStatus = TextToSpeech.ERROR
    private val systemTts = TextToSpeech(app) { status ->
        systemTtsStatus = status
        ttsReady.countDown()
    }
    @Volatile private var activeTrack: AudioTrack? = null

    fun isKittenModelDownloaded(): Boolean =
        File(modelDir, MODEL_FILE).isFile &&
            File(modelDir, VOICES_FILE).isFile &&
            File(modelDir, TOKENS_FILE).isFile &&
            File(modelDir, MODEL_FILE).length() > MIN_MODEL_BYTES

    @Synchronized
    fun downloadKittenModel(onProgress: (Int) -> Unit) {
        if (isKittenModelDownloaded()) {
            onProgress(100)
            return
        }
        val parent = modelDir.parentFile ?: throw IOException("مسیر ذخیره مدل کیتن نامعتبر است.")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("ساخت پوشه مدل کیتن ناموفق بود.")
        val request = Request.Builder().url(MODEL_URL).header("User-Agent", "Kira-Android").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("دانلود Kitten TTS ناموفق بود (HTTP ${response.code}).")
            val body = response.body ?: throw IOException("فایل مدل Kitten خالی است.")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(archive, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        downloaded += count
                        if (downloaded > MAX_ARCHIVE_BYTES) throw IOException("حجم آرشیو Kitten از حد مجاز بیشتر است.")
                        output.write(buffer, 0, count)
                        if (total > 0) onProgress((downloaded * 65 / total).toInt().coerceIn(1, 65))
                    }
                    output.fd.sync()
                }
            }
        }
        val staging = File(parent, "kitten-model-staging")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("ساخت پوشه موقت مدل Kitten ناموفق بود.")
        try {
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        if (entry.isDirectory || !name.startsWith("$MODEL_DIRECTORY/")) continue
                        val relative = name.removePrefix("$MODEL_DIRECTORY/")
                        if (relative.isBlank() || relative.split('/').any { it == ".." }) {
                            throw IOException("مسیر نامعتبر داخل آرشیو Kitten.")
                        }
                        val target = File(staging, relative)
                        if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            throw IOException("مسیر استخراج خارج از پوشه مدل است.")
                        }
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var remaining = entry.size
                            while (remaining > 0) {
                                val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) throw IOException("آرشیو مدل Kitten ناقص است.")
                                if (count == 0) continue
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                        }
                    }
                }
            }
            val stagedModel = File(staging, MODEL_FILE)
            val stagedVoices = File(staging, VOICES_FILE)
            val stagedTokens = File(staging, TOKENS_FILE)
            if (!stagedModel.isFile || stagedModel.length() < MIN_MODEL_BYTES ||
                !stagedVoices.isFile || !stagedTokens.isFile) {
                throw IOException("فایل‌های مدل Kitten ناقص هستند.")
            }
            modelDir.deleteRecursively()
            if (!staging.renameTo(modelDir)) throw IOException("نصب فایل‌های مدل Kitten ناموفق بود.")
            check(isKittenModelDownloaded()) { "اعتبارسنجی مدل Kitten ناموفق بود." }
            onProgress(100)
        } finally {
            staging.deleteRecursively()
            archive.delete()
        }
    }

    fun speak(text: String, character: String) {
        val value = text.trim()
        require(value.isNotBlank()) { "متنی برای خواندن وجود ندارد." }
        if (PERSIAN_OR_ARABIC.containsMatchIn(value)) {
            speakWithSystemEngine(value)
        } else {
            check(isKittenModelDownloaded()) {
                "برای صدای Kitten ابتدا مدل را از بخش صدا دانلود کن."
            }
            speakWithKitten(value, character)
        }
    }

    @Synchronized
    private fun speakWithKitten(text: String, character: String) {
        stop()
        val model = OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kitten = OfflineTtsKittenModelConfig(
                        model = File(modelDir, MODEL_FILE).absolutePath,
                        voices = File(modelDir, VOICES_FILE).absolutePath,
                        tokens = File(modelDir, TOKENS_FILE).absolutePath,
                        dataDir = File(modelDir, "espeak-ng-data").absolutePath
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                maxNumSentences = 1
            )
        )
        try {
            val speaker = when (character.lowercase()) {
                "ren" -> 3
                "mika" -> 6
                else -> 0
            }
            val audio = model.generate(text, sid = speaker, speed = 1.0f)
            val pcm = ShortArray(audio.samples.size) { index ->
                (audio.samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
            val minimum = AudioTrack.getMinBufferSize(
                audio.sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(audio.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(max(minimum, pcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            activeTrack = track
            try {
                val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                check(written == pcm.size) { "نوشتن صدای تولیدشده ناموفق بود." }
                track.play()
                while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    track.playbackHeadPosition < pcm.size) {
                    Thread.sleep(25)
                }
            } finally {
                activeTrack = null
                runCatching { track.stop() }
                track.release()
            }
        } finally {
            model.release()
        }
    }

    private fun speakWithSystemEngine(text: String) {
        check(ttsReady.await(10, TimeUnit.SECONDS) && systemTtsStatus == TextToSpeech.SUCCESS) {
            "موتور گفتار اندروید آماده نیست."
        }
        val language = systemTts.setLanguage(Locale("fa"))
        check(language != TextToSpeech.LANG_MISSING_DATA &&
            language != TextToSpeech.LANG_NOT_SUPPORTED) {
            "صدای فارسی روی موتور گفتار این گوشی نصب نیست."
        }
        systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kira-persian")
    }

    @Synchronized
    fun stop() {
        activeTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        activeTrack = null
        systemTts.stop()
    }

    fun close() {
        stop()
        systemTts.shutdown()
    }

    private companion object {
        const val MODEL_DIRECTORY = "kitten-nano-en-v0_1-fp16"
        const val MODEL_FILE = "model.fp16.onnx"
        const val VOICES_FILE = "voices.bin"
        const val TOKENS_FILE = "tokens.txt"
        const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_1-fp16.tar.bz2"
        const val MAX_ARCHIVE_BYTES = 100L * 1024L * 1024L
        const val MIN_MODEL_BYTES = 5L * 1024L * 1024L
        val PERSIAN_OR_ARABIC = Regex("[\\u0600-\\u06FF]")
    }
}
