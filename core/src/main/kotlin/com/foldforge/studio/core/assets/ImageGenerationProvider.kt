package com.foldforge.studio.core.assets

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Image/texture generation through a user-configured endpoint implementing the widely used
 * `POST {endpoint}/images/generations` API (returns base64 PNG). 3D model generation is not offered
 * by this API, so those calls throw [UnsupportedOperationException] rather than faking a result.
 */
class HttpImageGenerationProvider(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(3, TimeUnit.MINUTES).build(),
) : AssetGenerationProvider {
    override val name = "Image API ($model)"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generateImage(prompt: String, size: String): GeneratedAsset = withContext(Dispatchers.IO) {
        require(Regex("^\\d{3,4}x\\d{3,4}$").matches(size)) { "Invalid size" }
        val body = buildJsonObject {
            put("model", model); put("prompt", prompt.take(4000)); put("size", size); put("n", 1); put("response_format", "b64_json")
        }
        val req = Request.Builder().url(endpoint.trimEnd('/') + "/images/generations")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw AssetGenerationException("Image generation failed (HTTP ${resp.code})")
            val data = ((json.parseToJsonElement(text) as JsonObject)["data"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw AssetGenerationException("No image in response")
            val b64 = (data["b64_json"] as? JsonPrimitive)?.contentOrNull ?: throw AssetGenerationException("Provider did not return base64 image data")
            val slug = prompt.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "image" }
            GeneratedAsset("$slug.png", Base64.getDecoder().decode(b64), "image/png")
        }
    }

    override suspend fun generateTexture(prompt: String): GeneratedAsset =
        generateImage("Seamless tileable game texture, top-down, even lighting: $prompt", "1024x1024")

    override suspend fun generateModel(prompt: String): String =
        throw UnsupportedOperationException("This provider does not generate 3D models. Use procedural assets or import a GLB.")

    override suspend fun getStatus(jobId: String): AssetJobStatus = AssetJobStatus.Failed("No 3D jobs on this provider")

    override suspend fun downloadAsset(jobId: String): GeneratedAsset =
        throw UnsupportedOperationException("This provider does not generate 3D models.")
}
