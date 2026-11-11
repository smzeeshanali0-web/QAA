package com.aiclone.dialer.net

import com.aiclone.dialer.data.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BackendException(message: String) : Exception(message)

/** REST client for the AI backend. All calls are blocking-safe (run on Dispatchers.IO). */
class BackendClient(baseUrl: String, private val apiKey: String, val elevenKey: String = "", private val allowHttp: Boolean = false) {
    private val base = baseUrl.trim().trimEnd('/')
    private val http = OkHttpClient.Builder().callTimeout(240, TimeUnit.SECONDS).readTimeout(240, TimeUnit.SECONDS).build()

    /** Non-null = do not use this URL: plain http:// to a public host would send keys and passwords unencrypted. */
    fun insecureReason(): String? {
        if (allowHttp || !base.startsWith("http://", ignoreCase = true)) return null
        val host = runCatching { java.net.URI(base).host }.getOrNull().orEmpty().lowercase()
        return if (isPrivateHost(host)) null
        else "Blocked: plain http:// would send your API key, SIP password and ElevenLabs key unencrypted. Use https:// (see docs/HTTPS_SETUP.md) or switch on 'Allow http (testing only)' in Settings."
    }

    private fun isPrivateHost(h: String): Boolean {
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".ts.net")) return true
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127)
    }

    private fun builder(path: String): Request.Builder {
        val b = Request.Builder().url(base + path).header("X-API-Key", apiKey)
        if (elevenKey.isNotBlank()) b.header("X-ElevenLabs-Key", elevenKey.trim())
        return b
    }

    private fun exec(r: Request): JSONObject {
        if (base.isEmpty()) throw BackendException("Set the backend URL in Settings first.")
        insecureReason()?.let { throw BackendException(it) }
        return try {
            http.newCall(r).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()
                    throw BackendException(detail.ifEmpty { "HTTP ${resp.code}" })
                }
                if (body.isBlank()) JSONObject() else JSONObject(body)
            }
        } catch (e: java.io.IOException) {
            throw BackendException("Cannot reach backend: ${e.message}")
        }
    }

    private fun json(o: JSONObject) = o.toString().toRequestBody("application/json".toMediaType())

    suspend fun health(): JSONObject = withContext(Dispatchers.IO) { exec(builder("/health").get().build()) }

    suspend fun consentText(): String = withContext(Dispatchers.IO) { exec(builder("/v1/consent-text").get().build()).optString("text") }

    /** Step 1: upload sample. Returns the new profile id and the consent text to show. */
    suspend fun createProfile(name: String, ownerName: String, relation: String, filename: String, bytes: ByteArray, mime: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("name", name).addFormDataPart("owner_name", ownerName).addFormDataPart("owner_relation", relation)
                .addFormDataPart("file", filename, bytes.toRequestBody(mime.toMediaType())).build()
            val o = exec(builder("/v1/voice-profiles").post(body).build())
            o.getString("id") to o.optString("consent_text")
        }

    /** Step 2: accept or decline. Decline deletes the sample and profile on the server. */
    suspend fun respondConsent(id: String, accept: Boolean, disclosureAck: Boolean, typedName: String, note: String): String =
        withContext(Dispatchers.IO) {
            val o = JSONObject().put("accept", accept).put("disclosure_ack", disclosureAck).put("typed_name", typedName).put("permission_note", note)
            exec(builder("/v1/voice-profiles/$id/consent").post(json(o)).build()).optString("status")
        }

    /** Step 3: create the cloned voice (only works after consent was accepted). */
    suspend fun clone(id: String): String = withContext(Dispatchers.IO) {
        exec(builder("/v1/voice-profiles/$id/clone").post("".toRequestBody()).build()).optString("status")
    }

    suspend fun profiles(): List<VoiceProfile> = withContext(Dispatchers.IO) {
        val arr = exec(builder("/v1/voice-profiles").get().build()).getJSONArray("items")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            VoiceProfile(o.getString("id"), o.optString("name"), o.optString("owner_name"), o.optString("owner_relation"),
                o.optString("status"), o.optString("error"))
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { exec(builder("/v1/voice-profiles/$id").delete().build()); Unit }

    /** Pre-call check: send raw PCM16 16 kHz mono, get the same format back in the cloned voice. */
    suspend fun preview(id: String, pcm: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        if (base.isEmpty()) throw BackendException("Set the backend URL in Settings first.")
        insecureReason()?.let { throw BackendException(it) }
        val req = builder("/v1/voice-profiles/$id/preview").post(pcm.toRequestBody("application/octet-stream".toMediaType())).build()
        try {
            http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) {
                    val detail = runCatching { JSONObject(String(bytes)).optString("detail") }.getOrNull().orEmpty()
                    throw BackendException(detail.ifEmpty { "HTTP ${resp.code}" })
                }
                bytes
            }
        } catch (e: java.io.IOException) {
            throw BackendException("Cannot reach backend: ${e.message}")
        }
    }

    fun wsRequest(): Request {
        val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return Request.Builder().url("$wsBase/v1/calls/ws").header("X-API-Key", apiKey).build()   // header, not in the URL (URLs end up in logs)
    }

    fun newWebSocket(listener: WebSocketListener): WebSocket = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build().newWebSocket(wsRequest(), listener)
}
