package com.foldforge.studio.core.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

object AIProviderFactory {
    fun create(config: AiConfig): AIProvider {
        if (!config.isConfigured) throw AiException("AI provider is not configured. Open Settings → AI to connect a provider.")
        return when (config.provider) {
            ProviderKind.ANTHROPIC -> AnthropicProvider(config)
            ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(config)
        }
    }
}

/** Claude via the official Anthropic Java SDK. */
class AnthropicProvider(config: AiConfig) : BaseAIProvider(config) {
    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(config.apiKey)
            .apply { if (config.endpoint.isNotBlank()) baseUrl(config.endpoint.trimEnd('/')) }
            .maxRetries(2)
            .build()
    }

    override suspend fun complete(request: CompletionRequest): CompletionResponse = withContext(Dispatchers.IO) {
        val builder = MessageCreateParams.builder()
            .model(config.model)
            .maxTokens(request.maxTokens.toLong())
            .system(request.system)
        for (m in request.messages) {
            if (m.role == "assistant") {
                builder.addAssistantMessage(m.content)
            } else if (m.imagePng != null) {
                val image = ImageBlockParam.builder()
                    .source(
                        Base64ImageSource.builder()
                            .data(Base64.getEncoder().encodeToString(m.imagePng))
                            .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                            .build(),
                    ).build()
                builder.addUserMessageOfBlockParams(
                    listOf(ContentBlockParam.ofImage(image), ContentBlockParam.ofText(TextBlockParam.builder().text(m.content).build())),
                )
            } else {
                builder.addUserMessage(m.content)
            }
        }
        if (config.model in SERVER_FALLBACK_MODELS && config.endpoint.isBlank()) {
            // Server-side refusal fallback: a declined request is re-run on Anthropic's recommended model.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        val message = try {
            client.messages().create(builder.build())
        } catch (e: NotFoundException) {
            throw AiException("Model '${config.model}' was not found. Check the model name in Settings → AI.", cause = e)
        } catch (e: UnauthorizedException) {
            throw AiException("The API key was rejected (401). Update it in Settings → AI.", cause = e)
        } catch (e: PermissionDeniedException) {
            throw AiException("The API key has no permission for this request (403).", cause = e)
        } catch (e: RateLimitException) {
            throw AiException("Rate limited by the provider (429). Try again shortly.", retryable = true, cause = e)
        } catch (e: AnthropicServiceException) {
            throw AiException("AI request failed (HTTP ${e.statusCode()}).", retryable = e.statusCode() >= 500, cause = e)
        } catch (e: AnthropicIoException) {
            throw AiException("Network error while contacting the AI provider.", retryable = true, cause = e)
        }
        val stop = message.stopReason().map { it.toString() }.orElse(null)
        if (stop == "refusal") throw AiException("The model declined this request (refusal). Rephrase the request and try again.")
        val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        if (stop == "max_tokens" && request.jsonMode) {
            throw AiException("The response hit the output limit before finishing. Increase max output tokens or narrow the request.")
        }
        CompletionResponse(text, message.model().toString(), message.usage().inputTokens(), message.usage().outputTokens(), stop)
    }

    companion object {
        val SERVER_FALLBACK_MODELS = setOf("claude-opus-5", "claude-fable-5-1")
    }
}

/**
 * Any provider exposing the OpenAI-style `POST {endpoint}/chat/completions` API (OpenAI, local LLM
 * servers, gateways). Used only when the user configures such an endpoint.
 */
class OpenAiCompatibleProvider(
    config: AiConfig,
    private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).callTimeout(12, TimeUnit.MINUTES).build(),
) : BaseAIProvider(config) {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(request: CompletionRequest): CompletionResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", config.temperature)
            put("max_tokens", request.maxTokens)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", request.system) })
                    for (m in request.messages) {
                        add(
                            buildJsonObject {
                                put("role", if (m.role == "assistant") "assistant" else "user")
                                if (m.imagePng != null) {
                                    put(
                                        "content",
                                        buildJsonArray {
                                            add(buildJsonObject { put("type", "text"); put("text", m.content) })
                                            add(
                                                buildJsonObject {
                                                    put("type", "image_url")
                                                    put("image_url", buildJsonObject { put("url", "data:image/png;base64," + Base64.getEncoder().encodeToString(m.imagePng)) })
                                                },
                                            )
                                        },
                                    )
                                } else {
                                    put("content", m.content)
                                }
                            },
                        )
                    }
                },
            )
        }
        val url = config.endpoint.trimEnd('/') + "/chat/completions"
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val responseText = try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.let { (it as JsonPrimitive).contentOrNull } }.getOrNull()
                    throw AiException("AI request failed (HTTP ${resp.code})${msg?.let { ": ${it.take(200)}" } ?: ""}", retryable = resp.code == 429 || resp.code >= 500)
                }
                text
            }
        } catch (e: IOException) {
            throw AiException("Network error while contacting the AI provider.", retryable = true, cause = e)
        }
        val root = json.parseToJsonElement(responseText) as? JsonObject ?: throw AiException("Unexpected response from provider")
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw AiException("Provider returned no choices")
        val content = ((choice["message"] as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val usage = root["usage"] as? JsonObject
        CompletionResponse(
            content,
            (root["model"] as? JsonPrimitive)?.contentOrNull ?: config.model,
            (usage?.get("prompt_tokens") as? JsonPrimitive)?.longOrNull,
            (usage?.get("completion_tokens") as? JsonPrimitive)?.longOrNull,
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull,
        )
    }
}
