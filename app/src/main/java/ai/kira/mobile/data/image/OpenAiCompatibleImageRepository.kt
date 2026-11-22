package ai.kira.mobile.data.image

import ai.kira.mobile.domain.model.GeneratedImage
import ai.kira.mobile.domain.model.ImageProviderSettings
import ai.kira.mobile.domain.repository.ImageGenerationRepository
import ai.kira.mobile.domain.repository.ImageGenerationSettingsRepository
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ImageGenerationSettingsStore(context: Context) : ImageGenerationSettingsRepository {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("kira_image_generation", Context.MODE_PRIVATE)

    override fun hasSettings(): Boolean =
        !preferences.getString(BASE_URL, null).isNullOrBlank() &&
            !preferences.getString(MODEL, null).isNullOrBlank() &&
            !preferences.getString(API_KEY, null).isNullOrBlank()

    @Synchronized
    override fun save(baseUrl: String, model: String, apiKey: String) {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        val parsed = URL(normalizedUrl)
        require(parsed.protocol.equals("https", ignoreCase = true)) { "Image provider URL must use HTTPS." }
        require(parsed.host.isNotBlank() && parsed.userInfo == null && parsed.query == null && parsed.ref == null) {
            "Image provider URL is invalid."
        }
        val normalizedModel = model.trim()
        require(normalizedModel.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"))) {
            "Image model ID is invalid."
        }
        val normalizedKey = apiKey.trim()
        require(normalizedKey.isNotBlank() && normalizedKey.length <= 512) { "Image API key is invalid." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalizedKey.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(preferences.edit()
            .putString(BASE_URL, normalizedUrl)
            .putString(MODEL, normalizedModel)
            .putString(API_KEY, encoded)
            .commit()) { "ذخیره تنظیمات امن تصویر ناموفق بود." }
    }

    @Synchronized
    override fun settings(): ImageProviderSettings {
        val baseUrl = preferences.getString(BASE_URL, null).orEmpty()
        var model = preferences.getString(MODEL, null).orEmpty()
        require(baseUrl.isNotBlank() && model.isNotBlank()) { "تنظیمات Provider تصویر را کامل کن." }
        val isOpenAiEndpoint = baseUrl.trimEnd('/').equals("https://api.openai.com/v1", ignoreCase = true)
        if (isOpenAiEndpoint && model in RETIRED_OPENAI_MODELS) {
            model = CURRENT_OPENAI_IMAGE_MODEL
            check(preferences.edit().putString(MODEL, model).commit()) {
                "به‌روزرسانی مدل تصویر ناموفق بود."
            }
        }
        return ImageProviderSettings(baseUrl, model)
    }

    @Synchronized
    override fun readApiKey(): String {
        val encoded = preferences.getString(API_KEY, null)
            ?: throw IllegalStateException("کلید Provider تصویر ذخیره نشده است.")
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > IV_LENGTH) { "کلید تصویر ذخیره‌شده معتبر نیست." }
        val key = findExistingKey()
            ?: throw IllegalStateException("کلید امنیتی تصویر در دسترس نیست؛ اتصال را حذف و دوباره ثبت کن.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, payload.copyOfRange(0, IV_LENGTH))
        )
        return String(cipher.doFinal(payload.copyOfRange(IV_LENGTH, payload.size)), StandardCharsets.UTF_8)
    }

    @Synchronized
    override fun clear() {
        check(preferences.edit().clear().commit()) { "حذف تنظیمات تصویر ناموفق بود." }
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
        const val KEY_ALIAS = "kira.image.provider.key.v1"
        const val BASE_URL = "provider_base_url"
        const val MODEL = "provider_model"
        const val API_KEY = "provider_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val CURRENT_OPENAI_IMAGE_MODEL = "gpt-image-2.6-sunburst"
        val RETIRED_OPENAI_MODELS = setOf("dall-e-2", "dall-e-3", "gpt-image-1")
    }
}

class OpenAiCompatibleImageRepository(
    context: Context,
    private val settingsStore: ImageGenerationSettingsRepository
) : ImageGenerationRepository {
    private val app = context.applicationContext
    private val historyPreferences = app.getSharedPreferences("kira_image_history", Context.MODE_PRIVATE)

    override fun generate(prompt: String, size: String): GeneratedImage {
        val settings = settingsStore.settings()
        val endpoint = URL(settings.baseUrl + "/images/generations")
        require(endpoint.protocol == "https") { "Image generation endpoint must use HTTPS." }

        val connection = endpoint.openConnection() as HttpURLConnection
        val imageBytes: ByteArray
        try {
            val body = JSONObject()
                .put("model", settings.model)
                .put("prompt", prompt)
                .put("size", size)
                .put("n", 1)
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + settingsStore.readApiKey())
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { readLimited(it, MAX_JSON_BYTES) }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
                }.getOrDefault("")
                throw IllegalStateException("Image provider HTTP " + status + ": " + detail.take(300))
            }

            val first = JSONObject(raw).optJSONArray("data")?.optJSONObject(0)
                ?: throw IllegalStateException("Image provider returned no image data.")
            val base64 = first.optString("b64_json")
            imageBytes = if (base64.isNotBlank()) {
                require(base64.length <= MAX_BASE64_CHARS) { "Generated image is too large." }
                Base64.decode(base64, Base64.DEFAULT).also {
                    require(it.size <= MAX_IMAGE_BYTES) { "Generated image is too large." }
                }
            } else {
                val imageUrl = first.optString("url")
                require(imageUrl.isNotBlank()) { "Image provider returned neither image bytes nor a URL." }
                downloadImage(imageUrl)
            }
        } finally {
            connection.disconnect()
        }

        val format = detectFormat(imageBytes)
        val id = UUID.randomUUID().toString()
        val directory = File(app.filesDir, "generated_images")
        require(directory.exists() || directory.mkdirs()) { "Cannot create image storage directory." }
        val target = File(directory, id + "." + format.extension)
        val temporary = File(directory, id + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(imageBytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IllegalStateException("Could not persist generated image.")
        } catch (error: Exception) {
            temporary.delete()
            target.delete()
            throw error
        }

        val image = GeneratedImage(
            id = id,
            prompt = prompt,
            model = settings.model,
            localPath = target.absolutePath,
            createdAt = System.currentTimeMillis()
        )
        appendHistory(image)
        return image
    }

    @Synchronized
    override fun history(limit: Int): List<GeneratedImage> {
        val json = runCatching {
            JSONArray(historyPreferences.getString(HISTORY, "[]"))
        }.getOrDefault(JSONArray())
        val result = mutableListOf<GeneratedImage>()
        for (index in json.length() - 1 downTo 0) {
            if (result.size >= limit) break
            val row = json.optJSONObject(index) ?: continue
            val path = row.optString("localPath")
            if (path.isBlank() || !File(path).isFile) continue
            result += GeneratedImage(
                id = row.optString("id"),
                prompt = row.optString("prompt"),
                model = row.optString("model"),
                localPath = path,
                createdAt = row.optLong("createdAt")
            )
        }
        return result
    }

    @Synchronized
    private fun appendHistory(image: GeneratedImage) {
        val current = runCatching {
            JSONArray(historyPreferences.getString(HISTORY, "[]"))
        }.getOrDefault(JSONArray())
        current.put(
            JSONObject()
                .put("id", image.id)
                .put("prompt", image.prompt)
                .put("model", image.model)
                .put("localPath", image.localPath)
                .put("createdAt", image.createdAt)
        )
        while (current.length() > HISTORY_LIMIT) {
            val old = current.optJSONObject(0)
            old?.optString("localPath")?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            val replacement = JSONArray()
            for (index in 1 until current.length()) replacement.put(current.get(index))
            historyPreferences.edit().putString(HISTORY, replacement.toString()).apply()
            return
        }
        historyPreferences.edit().putString(HISTORY, current.toString()).apply()
    }

    private fun downloadImage(value: String): ByteArray {
        val url = URL(value)
        require(url.protocol.equals("https", ignoreCase = true) && url.host.isNotBlank() && url.userInfo == null) {
            "Image provider returned an unsafe image URL."
        }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "image/png,image/jpeg,image/webp")
            val status = connection.responseCode
            require(status in 200..299) { "Image download failed with HTTP " + status }
            val contentType = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
            require(contentType in setOf("image/png", "image/jpeg", "image/webp")) {
                "Image provider returned an unsupported content type."
            }
            return connection.inputStream.use { readLimitedBytes(it, MAX_IMAGE_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    private fun detectFormat(bytes: ByteArray): ImageFormat {
        require(bytes.size >= 12) { "Image data is empty or incomplete." }
        if (
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return ImageFormat("png")
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return ImageFormat("jpg")
        }
        if (
            bytes.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(StandardCharsets.US_ASCII) == "WEBP"
        ) return ImageFormat("webp")
        throw IllegalStateException("Image provider returned an unsupported image format.")
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): String =
        String(readLimitedBytes(input, limit), StandardCharsets.UTF_8)

    private fun readLimitedBytes(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IllegalStateException("Provider response is too large.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private data class ImageFormat(val extension: String)

    private companion object {
        const val HISTORY = "generated_images"
        const val HISTORY_LIMIT = 50
        const val MAX_JSON_BYTES = 28 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_BASE64_CHARS = 28 * 1024 * 1024
    }
}
