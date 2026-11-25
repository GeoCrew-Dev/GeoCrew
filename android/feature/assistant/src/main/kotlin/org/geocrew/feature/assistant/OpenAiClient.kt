package org.geocrew.feature.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.geocrew.core.model.action.OpenAiWire
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Talks to any OpenAI-compatible chat completions endpoint the user configured. */
@Singleton
class OpenAiClient @Inject constructor(base: OkHttpClient, private val config: AssistantConfig) {
    private val http = base.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    private fun request(path: String, key: String) = Request.Builder()
        .url(config.values.value.baseUrl.trimEnd('/') + path)
        .header("Authorization", "Bearer $key")

    suspend fun chat(body: JsonObject): OpenAiWire.Reply = withContext(Dispatchers.IO) {
        val key = config.key() ?: throw IllegalStateException("还没有填 API Key")
        val req = request("/chat/completions", key).post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful && !text.contains("\"error\"")) throw IllegalStateException("接口返回 ${r.code}")
            OpenAiWire.parse(text)
        }
    }

    /** Lists models, which also checks that the address and key work. */
    suspend fun models(): List<String> = withContext(Dispatchers.IO) {
        val key = config.key() ?: throw IllegalStateException("还没有填 API Key")
        http.newCall(request("/models", key).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("接口返回 ${r.code}")
            OpenAiWire.models(r.body?.string().orEmpty())
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
