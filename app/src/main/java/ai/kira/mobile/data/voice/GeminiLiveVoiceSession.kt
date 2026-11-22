package ai.kira.mobile.data.voice

import ai.kira.mobile.domain.model.RealtimeVoiceSessionEvent
import ai.kira.mobile.domain.repository.RealtimeVoiceSession
import ai.kira.mobile.domain.repository.VoiceCredentialRepository
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GeminiLiveCredentialStore(context: Context) : VoiceCredentialRepository {
    private val preferences = context.applicationContext.getSharedPreferences("kira_gemini_live", Context.MODE_PRIVATE)

    override fun hasApiKey(): Boolean = preferences.contains(KEY_ENTRY)

    @Synchronized
    override fun saveApiKey(value: String) {
        val normalized = value.trim()
        require(normalized.isNotEmpty() && normalized.length <= 512) { "Gemini Live API key is invalid." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(KEY_ENTRY, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit()) {
            "ذخیره کلید Gemini Live ناموفق بود."
        }
    }

    @Synchronized
    override fun readApiKey(): String {
        val encoded = preferences.getString(KEY_ENTRY, null)
            ?: throw IllegalStateException("ابتدا کلید Gemini Live را ذخیره کن.")
        val payload = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("کلید ذخیره‌شده Gemini Live خراب است؛ آن را حذف و دوباره ثبت کن.", error)
        }
        require(payload.size > IV_LENGTH) { "Gemini Live key payload is invalid." }
        val key = findExistingKey()
            ?: throw IllegalStateException("کلید امنیتی Gemini Live در دسترس نیست؛ کلید را حذف و دوباره ثبت کن.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, payload.copyOfRange(0, IV_LENGTH))
        )
        return String(cipher.doFinal(payload.copyOfRange(IV_LENGTH, payload.size)), Charsets.UTF_8)
    }

    @Synchronized
    override fun clearApiKey() {
        check(preferences.edit().remove(KEY_ENTRY).commit()) { "حذف کلید Gemini Live ناموفق بود." }
    }

    private fun findExistingKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        findExistingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "kira.gemini.live.key.v1"
        const val KEY_ENTRY = "api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}

class GeminiLiveVoiceSession(
    context: Context,
    private val apiKey: String,
    private val onEvent: (RealtimeVoiceSessionEvent) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (speaker: String, text: String) -> Unit
) : RealtimeVoiceSession {
    private val app = context.applicationContext
    private val active = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val setupComplete = AtomicBoolean(false)
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val audioLock = Any()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousAudioMode = AudioManager.MODE_NORMAL
    @Volatile private var audioModeChanged = false
    
    override fun start() {
        check(!disposed.get()) { "Voice session has already ended." }
        check(apiKey.isNotBlank()) { "Gemini Live API key is empty." }
        check(active.compareAndSet(false, true)) { "Voice session is already active." }
        setupComplete.set(false)
        muted.set(false)
        try {
            preparePlayer()
            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioModeChanged = true
            onStatus("در حال اتصال به Gemini Live…")

            val url = HttpUrl.Builder()
                .scheme("https")
                .host("generativelanguage.googleapis.com")
                .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
                .addQueryParameter("key", apiKey)
                .build()
            socket = client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val setup = JSONObject().put(
                            "setup",
                            JSONObject()
                                .put("model", "models/" + MODEL)
                                .put("responseModalities", JSONArray().put("AUDIO"))
                                .put("inputAudioTranscription", JSONObject())
                                .put("outputAudioTranscription", JSONObject())
                                .put(
                                    "systemInstruction",
                                    JSONObject().put(
                                        "parts",
                                        JSONArray().put(
                                            JSONObject().put(
                                                "text",
                                                "You are Kira, a real-time Persian-speaking voice companion. Reply naturally and briefly in Persian unless the user speaks another language. Speak directly; do not describe hidden reasoning."
                                            )
                                        )
                                    )
                                )
                        )
                        if (!webSocket.send(setup.toString())) fail("ارسال تنظیمات اتصال صوتی انجام نشد.")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleServerMessage(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        fail("اتصال صوتی قطع شد: " + (t.message ?: "خطای شبکه"))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (active.get()) fail("نشست صوتی بسته شد.")
                    }
                }
            )
        } catch (error: Exception) {
            fail(error.message ?: "شروع تماس صوتی ناموفق بود.")
            throw error
        }
    }

    override fun setMuted(value: Boolean) {
        check(active.get() && setupComplete.get()) { "Voice session is not connected." }
        synchronized(audioLock) {
            if (muted.get() == value) return
            val audioRecord = recorder ?: throw IllegalStateException("Microphone is unavailable.")
            if (value) {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
                muted.set(true)
                onEvent(RealtimeVoiceSessionEvent.Muted)
                onStatus("میکروفون بی‌صداست.")
            } else {
                audioRecord.startRecording()
                muted.set(false)
                onEvent(RealtimeVoiceSessionEvent.Unmuted)
                onStatus("تماس زنده است؛ می‌توانی صحبت کنی.")
            }
        }
    }

    override fun isMuted(): Boolean = muted.get()

    override fun stop() {
        if (!disposed.compareAndSet(false, true)) return
        active.set(false)
        setupComplete.set(false)
        runCatching { socket?.close(1000, "client ended call") }
        socket = null
        synchronized(audioLock) {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
        }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        runCatching { player?.pause() }
        runCatching { player?.flush() }
        runCatching { player?.release() }
        player = null
        if (audioModeChanged) {
            runCatching { audioManager.mode = previousAudioMode }
            audioModeChanged = false
        }
        runCatching { captureExecutor.shutdownNow() }
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
        onEvent(RealtimeVoiceSessionEvent.Ended)
        onStatus("تماس صوتی پایان یافت.")
    }

    private fun preparePlayer() {
        val minBuffer = AudioTrack.getMinBufferSize(
            OUTPUT_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "Audio output is not supported on this device." }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(OUTPUT_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        player = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, OUTPUT_RATE / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also {
                require(it.state == AudioTrack.STATE_INITIALIZED) { "Audio output could not be initialized." }
                it.play()
            }
    }

    private fun handleServerMessage(raw: String) {
        try {
            val message = JSONObject(raw)
            if (message.has("setupComplete")) {
                if (setupComplete.compareAndSet(false, true)) {
                    startMicrophone()
                    if (active.get() && recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        onEvent(RealtimeVoiceSessionEvent.Connected)
                        onStatus("تماس زنده برقرار شد؛ صحبت کن.")
                    }
                }
            }

            val serverContent = message.optJSONObject("serverContent") ?: return
            val inputTranscript = serverContent.optJSONObject("inputTranscription")?.optString("text").orEmpty()
            if (inputTranscript.isNotBlank()) onTranscript("تو", inputTranscript)
            val outputTranscript = serverContent.optJSONObject("outputTranscription")?.optString("text").orEmpty()
            if (outputTranscript.isNotBlank()) onTranscript("Kira", outputTranscript)

            if (serverContent.optBoolean("interrupted")) {
                player?.let {
                    runCatching { it.pause(); it.flush(); it.play() }
                }
            }
            val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
            for (index in 0 until parts.length()) {
                val inlineData = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
                val mimeType = inlineData.optString("mimeType")
                if (!mimeType.startsWith("audio/pcm")) continue
                val encoded = inlineData.optString("data")
                if (encoded.isBlank()) continue
                val audio = Base64.decode(encoded, Base64.DEFAULT)
                val track = player ?: continue
                if (active.get()) track.write(audio, 0, audio.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (error: Exception) {
            fail("خواندن پاسخ صوتی ناموفق بود: " + (error.message ?: "خطای داده"))
        }
    }

    private fun startMicrophone() {
        if (!active.get()) return
        try {
            val minimum = AudioRecord.getMinBufferSize(
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            require(minimum > 0) { "Microphone recording is not supported on this device." }
            val audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(INPUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minimum * 2, INPUT_RATE / 2))
                .build()
            require(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                "Microphone could not be initialized."
            }
            recorder = audioRecord
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }
            audioRecord.startRecording()
            captureExecutor.execute { captureLoop(audioRecord) }
        } catch (error: Exception) {
            fail("فعال‌سازی میکروفون ناموفق بود: " + (error.message ?: "خطا"))
        }
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val buffer = ByteArray(CHUNK_BYTES)
        while (active.get()) {
            if (muted.get() || !setupComplete.get()) {
                try { Thread.sleep(30) } catch (_: InterruptedException) { break }
                continue
            }
            val count = try {
                audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (error: Exception) {
                if (active.get() && !muted.get()) fail("خواندن میکروفون ناموفق بود.")
                break
            }
            if (count <= 0) {
                if (muted.get()) {
                    try { Thread.sleep(30) } catch (_: InterruptedException) { break }
                    continue
                }
                if (active.get()) fail("جریان ورودی میکروفون متوقف شد.")
                break
            }
            val chunk = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
            val payload = JSONObject().put(
                "realtimeInput",
                JSONObject().put(
                    "audio",
                    JSONObject()
                        .put("data", chunk)
                        .put("mimeType", "audio/pcm;rate=16000")
                )
            )
            if (socket?.send(payload.toString()) != true) {
                if (active.get()) fail("ارسال صدا به Gemini Live ناموفق بود.")
                break
            }
        }
    }

    private fun fail(message: String) {
        if (!active.getAndSet(false)) return
        if (!disposed.compareAndSet(false, true)) return
        setupComplete.set(false)
        synchronized(audioLock) {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
        }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        runCatching { player?.pause(); player?.flush(); player?.release() }
        player = null
        runCatching { socket?.close(1011, "voice session failed") }
        socket = null
        if (audioModeChanged) {
            runCatching { audioManager.mode = previousAudioMode }
            audioModeChanged = false
        }
        runCatching { captureExecutor.shutdownNow() }
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
        onEvent(RealtimeVoiceSessionEvent.Failed(message))
        onStatus(message)
    }

    private companion object {
        const val MODEL = "gemini-3.8-live"
        const val INPUT_RATE = 16_000
        const val OUTPUT_RATE = 24_000
        const val CHUNK_BYTES = 3_200
    }
}
