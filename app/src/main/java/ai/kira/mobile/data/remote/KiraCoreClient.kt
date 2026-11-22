package ai.kira.mobile.data.remote

import android.content.Context
import ai.kira.mobile.data.local.KiraCoreSessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class KiraCoreException(
    val statusCode: Int,
    message: String
) : IOException(message)

/**
 * Stores Kira Core session tokens encrypted with a device-bound Android Keystore key.
 * Provider API keys are not stored here: they belong in Kira Core's encrypted credential store.
 */

/**
 * Provider-neutral client for the single Kira Core used by Windows and Android.
 *
 * All blocking calls must run on a background thread/Executor, never on the Android UI thread.
 * The caller supplies the deployed Core URL; no fake production hostname is hard-coded.
 */
class KiraCoreClient(
    context: Context,
    serverBaseUrl: String,
    private val sessionStore: KiraCoreSessionStore = KiraCoreSessionStore(context)
) {
    private val baseUrl = serverBaseUrl.trim().trimEnd('/')

    init {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://127.0.0.1")) {
            "Kira Core URL must use HTTPS (HTTP is allowed only for local development)."
        }
        require(baseUrl.isNotBlank()) { "Kira Core URL is not configured." }
    }

    fun hasSession(): Boolean =
        sessionStore.accessToken().isNotBlank() && sessionStore.refreshToken().isNotBlank()

    fun register(email: String, password: String, displayName: String = ""): JSONObject {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("display_name", displayName)
        return storeSession(request("POST", "/v1/auth/register", body, authenticated = false))
    }

    fun login(email: String, password: String): JSONObject {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
        return storeSession(request("POST", "/v1/auth/login", body, authenticated = false))
    }

    fun logout() {
        val refreshToken = sessionStore.refreshToken()
        if (refreshToken.isNotBlank()) {
            runCatching {
                request("POST", "/v1/auth/logout", JSONObject().put("refresh_token", refreshToken))
            }
        }
        sessionStore.clear()
    }

    fun account(): JSONObject = request("GET", "/v1/account")
    fun characters(): JSONObject = request("GET", "/v1/characters")

    fun saveCharacterProfile(characterId: String, revision: Int, profile: JSONObject): JSONObject =
        request(
            "PUT",
            "/v1/characters/$characterId",
            JSONObject().put("revision", revision).put("profile", profile)
        )

    fun deleteCharacterProfile(characterId: String) {
        request("DELETE", "/v1/characters/$characterId")
    }

    fun getSettings(): JSONObject = request("GET", "/v1/settings")

    fun saveSettings(revision: Int, settings: JSONObject): JSONObject =
        request("PUT", "/v1/settings", JSONObject().put("revision", revision).put("settings", settings))

    fun saveProviderCredential(providerId: String, apiKey: String, accountId: String? = null) {
        val body = JSONObject().put("api_key", apiKey)
        if (!accountId.isNullOrBlank()) body.put("account_id", accountId)
        request("PUT", "/v1/settings/providers/$providerId/credential", body)
    }

    fun deleteProviderCredential(providerId: String) {
        request("DELETE", "/v1/settings/providers/$providerId/credential")
    }

    fun conversations(characterId: String? = null): JSONObject {
        val suffix = if (characterId.isNullOrBlank()) "" else "?character_id=${encode(characterId)}"
        return request("GET", "/v1/conversations$suffix")
    }

    fun createConversation(characterId: String, title: String = ""): JSONObject =
        request(
            "POST",
            "/v1/conversations",
            JSONObject().put("character_id", characterId).put("title", title)
        )

    fun messages(conversationId: String): JSONObject =
        request("GET", "/v1/conversations/$conversationId/messages")

    fun sendMessage(
        conversationId: String,
        characterId: String,
        content: String,
        attachmentIds: JSONArray = JSONArray(),
        approvedScreenContext: Boolean = false,
        deviceId: String? = null
    ): JSONObject {
        val body = JSONObject()
            .put("conversation_id", conversationId)
            .put("character_id", characterId)
            .put("content", content)
            .put("attachment_ids", attachmentIds)
            .put("approved_screen_context", approvedScreenContext)
        if (!deviceId.isNullOrBlank()) body.put("device_id", deviceId)
        return request("POST", "/v1/chat/messages", body)
    }

    fun getMemory(characterId: String): JSONObject =
        request("GET", "/v1/memory/$characterId")

    fun saveMemory(characterId: String, revision: Int, items: JSONArray): JSONObject =
        request(
            "PUT",
            "/v1/memory/$characterId",
            JSONObject().put("revision", revision).put("items", items)
        )

    fun registerDevice(
        deviceId: String,
        deviceName: String,
        appVersion: String,
        localCapabilities: JSONArray = JSONArray()
    ): JSONObject {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("platform", "android")
            .put("device_name", deviceName)
            .put("app_version", appVersion)
            .put("local_capabilities", localCapabilities)
        return request("POST", "/v1/devices", body)
    }

    fun devices(): JSONObject = request("GET", "/v1/devices")
    fun restoreSnapshot(): JSONObject = request("GET", "/v1/sync/snapshot")

    fun importDesktopData(
        importId: String,
        sharedData: JSONObject,
        providerCredentials: JSONObject? = null
    ): JSONObject {
        val body = JSONObject()
            .put("import_id", importId)
            .put("shared_data", sharedData)
        if (providerCredentials != null) body.put("provider_credentials", providerCredentials)
        return request("POST", "/v1/sync/import", body)
    }

    private fun storeSession(response: JSONObject): JSONObject {
        val access = response.optString("access_token")
        val refresh = response.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) {
            throw KiraCoreException(502, "Kira Core returned an incomplete session.")
        }
        sessionStore.save(access, refresh)
        return response
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        authenticated: Boolean = true
    ): JSONObject {
        return try {
            perform(method, path, body, authenticated)
        } catch (first: KiraCoreException) {
            if (authenticated && first.statusCode == 401 && sessionStore.refreshToken().isNotBlank()) {
                try {
                    val refreshed = request(
                        "POST",
                        "/v1/auth/refresh",
                        JSONObject().put("refresh_token", sessionStore.refreshToken()),
                        authenticated = false
                    )
                    storeSession(refreshed)
                    perform(method, path, body, authenticated = true)
                } catch (_: Exception) {
                    sessionStore.clear()
                    throw KiraCoreException(401, "Kira Core session expired. Sign in again.")
                }
            } else {
                throw first
            }
        }
    }

    private fun perform(
        method: String,
        path: String,
        body: JSONObject?,
        authenticated: Boolean
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/json")
            if (body != null) setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (authenticated) {
                val token = sessionStore.accessToken()
                if (token.isBlank()) throw KiraCoreException(401, "Sign in to Kira Core first.")
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) doOutput = true
        }

        try {
            if (body != null) {
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(responseText).optString("detail") }
                    .getOrNull().orEmpty().ifBlank { "Kira Core request failed." }
                throw KiraCoreException(code, message)
            }
            return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        } catch (error: KiraCoreException) {
            throw error
        } catch (error: Exception) {
            throw KiraCoreException(0, error.message ?: "Unable to reach Kira Core.")
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
