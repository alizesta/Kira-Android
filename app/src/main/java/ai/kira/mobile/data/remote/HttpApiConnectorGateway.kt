package ai.kira.mobile.data.remote

import ai.kira.mobile.domain.model.ConnectorExecutionConfig
import ai.kira.mobile.domain.repository.ConnectorConfigurationRepository
import ai.kira.mobile.domain.repository.ConnectorExecutionGateway
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.net.UnknownHostException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Executes approved, read-only HTTPS API connectors with DNS-pinned public destinations. */
class HttpApiConnectorGateway(
    private val configurations: ConnectorConfigurationRepository,
    private val resolveHost: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
) : ConnectorExecutionGateway {

    override fun executeIfRequested(characterId: String, assistantContent: String): String? {
        val call = parseCall(assistantContent) ?: return null
        return try {
            val config = configurations.executionConfig(characterId, call.id)
                ?: throw IllegalStateException("Connector نصب‌شده پیدا نشد یا برای این شخصیت فعال نیست.")
            execute(config, call.params)
        } catch (error: Exception) {
            "Connector error: ${error.message ?: "request failed"}"
        }
    }

    private fun parseCall(content: String): ConnectorCall? {
        val match = Regex(
            "<kira_connector>\\s*(\\{.*?\\})\\s*</kira_connector>",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(content) ?: return null
        val json = org.json.JSONObject(match.groupValues[1])
        val id = json.optString("id")
        require(id.isNotBlank()) { "شناسه Connector در درخواست مدل خالی است." }
        return ConnectorCall(id, json.optJSONObject("params") ?: org.json.JSONObject())
    }

    private fun execute(config: ConnectorExecutionConfig, params: org.json.JSONObject): String {
        val base = URL(config.baseUrl)
        require(
            base.protocol.equals("https", ignoreCase = true) &&
                base.host.isNotBlank() && base.userInfo == null &&
                base.query == null && base.ref == null &&
                (base.port == -1 || base.port == 443)
        ) { "آدرس پایه Connector معتبر نیست." }

        val template = config.pathTemplate
        require(
            template.startsWith("/") && !template.startsWith("//") &&
                !template.contains('?') && !template.contains('#') &&
                !template.contains('\\')
        ) { "مسیر Connector باید یک مسیر نسبی معتبر باشد." }

        val supplied = mutableListOf<String>()
        val keys = params.keys()
        while (keys.hasNext()) supplied += keys.next()
        require(supplied.all { it in config.allowedParameters }) {
            "Connector پارامتر ناشناخته دریافت کرد."
        }

        val placeholders = Regex("\\{([A-Za-z][A-Za-z0-9_]*)}")
            .findAll(template)
            .map { it.groupValues[1] }
            .toSet()
        require(placeholders.all { it in config.allowedParameters }) {
            "قالب مسیر Connector معتبر نیست."
        }

        val path = Regex("\\{([A-Za-z][A-Za-z0-9_]*)}").replace(template) { match ->
            val key = match.groupValues[1]
            val value = params.optString(key, "")
            require(value.isNotBlank() && value.length <= 200) { "پارامتر $key الزامی است." }
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        require(!path.contains("{") && !path.contains("}")) {
            "پارامتر مسیر ناقص است."
        }

        val query = supplied.filter { it !in placeholders }.sorted().map { key ->
            val value = params.optString(key, "")
            require(value.length <= 500) { "مقدار پارامتر بیش از حد بزرگ است." }
            URLEncoder.encode(key, StandardCharsets.UTF_8.name()) + "=" +
                URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        }
        val url = URL(config.baseUrl.trimEnd('/') + path +
            (if (query.isEmpty()) "" else "?" + query.joinToString("&")))

        require(
            url.protocol.equals("https", ignoreCase = true) &&
                url.host.equals(base.host, ignoreCase = true) &&
                (url.port == -1 || url.port == 443) &&
                url.userInfo == null && url.ref == null
        ) { "مقصد Connector معتبر نیست." }

        // Resolve once, validate every returned address, and give the exact same
        // address set to OkHttp. TLS SNI and certificate checks still use the
        // original URL hostname, while a second DNS lookup cannot rebind it.
        val pinnedAddresses = ConnectorAddressPolicy.resolvePublic(base.host, resolveHost)
        val pinnedDns = Dns { hostname ->
            if (hostname.equals(base.host, ignoreCase = true)) {
                pinnedAddresses
            } else {
                throw UnknownHostException("Connector attempted to resolve an unexpected host.")
            }
        }
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(pinnedDns)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        try {
            val requestBuilder = Request.Builder()
                .url(url.toURI().toString())
                .header("Accept", "application/json")
                .get()
            if (config.authType == "bearer") {
                require(config.apiKey.isNotBlank()) { "کلید API این Connector بازخوانی نشد." }
                requestBuilder.header("Authorization", "Bearer " + config.apiKey)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body
                if (body != null && body.contentLength() > MAX_RESPONSE_BYTES) {
                    throw IllegalStateException("پاسخ Connector از ۶۴ کیلوبایت بیشتر است.")
                }
                val text = body?.byteStream()?.use { readBounded(it) }.orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Connector با HTTP ${response.code} پاسخ داد: " + text.take(500)
                    )
                }
                if (text.isBlank()) throw IllegalStateException("Connector پاسخ خالی برگرداند.")
                return text
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun readBounded(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_RESPONSE_BYTES) {
                throw IllegalStateException("پاسخ Connector از ۶۴ کیلوبایت بیشتر است.")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private data class ConnectorCall(val id: String, val params: org.json.JSONObject)

    private companion object {
        const val MAX_RESPONSE_BYTES = 65_536
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 20L
        const val CALL_TIMEOUT_SECONDS = 30L
    }
}
