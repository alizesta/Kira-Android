package ai.kira.mobile.data.remote

import ai.kira.mobile.domain.model.InternetSearchResponse
import ai.kira.mobile.domain.model.InternetSearchSource
import ai.kira.mobile.domain.repository.InternetSearchRepository
import ai.kira.mobile.domain.repository.SearchCredentialRepository
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SearchCredentialStore(context: Context) : SearchCredentialRepository {
    private val preferences = context.applicationContext
        .getSharedPreferences("kira_search_credentials", Context.MODE_PRIVATE)

    override fun hasApiKey(): Boolean = preferences.contains(KEY_ENTRY)

    @Synchronized
    override fun saveApiKey(value: String) {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Search API key must not be blank." }
        require(normalized.length <= MAX_KEY_LENGTH) { "Search API key is too long." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
        val stored = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(KEY_ENTRY, stored).commit()) {
            "ذخیره امن کلید جستجو در حافظه دستگاه ناموفق بود."
        }
    }

    @Synchronized
    fun readApiKey(): String {
        val encoded = preferences.getString(KEY_ENTRY, null)
            ?: throw IllegalStateException("ابتدا کلید Tavily Search را ذخیره کن.")
        val payload = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("کلید ذخیره‌شده جستجو خراب است؛ آن را حذف و دوباره ثبت کن.", error)
        }
        require(payload.size > IV_LENGTH) { "کلید ذخیره‌شده جستجو معتبر نیست." }
        val key = findExistingKey()
            ?: throw IllegalStateException("کلید امنیتی دستگاه در دسترس نیست؛ کلید جستجو را حذف و دوباره ثبت کن.")
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val encrypted = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return try {
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalStateException("بازکردن کلید ذخیره‌شده جستجو ممکن نشد؛ آن را حذف و دوباره ثبت کن.", error)
        }
    }

    @Synchronized
    override fun clearApiKey() {
        check(preferences.edit().remove(KEY_ENTRY).commit()) {
            "حذف کلید جستجو از حافظه دستگاه ناموفق بود."
        }
    }

    private fun findExistingKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateKey(): SecretKey {
        findExistingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "kira.search.tavily.key.v1"
        const val KEY_ENTRY = "tavily_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val MAX_KEY_LENGTH = 512
    }
}

class TavilySearchRepository(private val credentials: SearchCredentialStore) : InternetSearchRepository {
    override fun search(query: String, maxResults: Int): InternetSearchResponse {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            val body = JSONObject()
                .put("query", query)
                .put("search_depth", "basic")
                .put("max_results", maxResults.coerceIn(1, MAX_RESULTS))
                .put("include_answer", true)
                .put("include_raw_content", "markdown")
                .put("include_images", false)

            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + credentials.readApiKey())
            connection.setRequestProperty("User-Agent", "Kira-Android")
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.use { readLimited(it, MAX_RESPONSE_BYTES) }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching {
                    val error = JSONObject(raw)
                    error.optString("detail").ifBlank { error.optString("message") }
                }.getOrDefault("")
                throw IllegalStateException("جستجوی وب با HTTP $status شکست خورد. ${detail.take(220)}".trim())
            }

            val json = JSONObject(raw)
            val rows = json.optJSONArray("results")
            val results = buildList {
                if (rows != null) for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val title = row.optString("title").trim()
                    val url = row.optString("url").trim()
                    val excerpt = row.optString("content").trim()
                    val extracted = row.optString("raw_content").trim().ifBlank { excerpt }
                    if (title.isBlank() || !isHttpsUrl(url) || (excerpt.isBlank() && extracted.isBlank())) continue
                    add(
                        InternetSearchSource(
                            number = size + 1,
                            title = title.take(MAX_TITLE_LENGTH),
                            url = url,
                            excerpt = excerpt.take(MAX_EXCERPT_LENGTH),
                            extractedContent = extracted.take(MAX_EXTRACTED_LENGTH),
                            score = row.optDouble("score", 0.0).takeIf { it.isFinite() } ?: 0.0
                        )
                    )
                    if (size >= MAX_RESULTS) break
                }
            }

            return InternetSearchResponse(
                query = query,
                answer = json.optString("answer").trim().take(MAX_ANSWER_LENGTH),
                results = results,
                provider = "Tavily"
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun readLimited(input: java.io.InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IllegalStateException("پاسخ جستجو بیش از حد بزرگ است.")
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    }

    private companion object {
        const val ENDPOINT = "https://api.tavily.com/search"
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 25_000
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val MAX_RESULTS = 8
        const val MAX_TITLE_LENGTH = 300
        const val MAX_EXCERPT_LENGTH = 2_000
        const val MAX_EXTRACTED_LENGTH = 4_000
        const val MAX_ANSWER_LENGTH = 6_000
    }
}
