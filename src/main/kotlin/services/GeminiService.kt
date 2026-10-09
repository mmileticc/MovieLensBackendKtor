package com.milinko.services

import com.google.genai.Client
import com.google.genai.errors.ApiException
import com.google.genai.types.Blob
import com.google.genai.types.Content
import com.google.genai.types.Part
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors


/** Gemini is overloaded or rate-limited (HTTP 429/503); routes answer 503 with a "try again" message. */
class GeminiOverloadedException(message: String) : Exception(message)

private fun isGeminiOverloadSignal(message: String?): Boolean {
    val text = message.orEmpty()
    return listOf("overloaded", "UNAVAILABLE", "RESOURCE_EXHAUSTED", "rate limit", " 429", " 503")
        .any { text.contains(it, ignoreCase = true) }
}
/**
 * Google Gemini: movie recognition from a photo (poster or film still) and text embeddings for the
 * semantic search.
 */

class GeminiService(private val apiKey: String) {

    // The Gemini SDK call is blocking, so it runs on a dedicated thread instead of Ktor's threads.
    private val sdkDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val client = Client.builder()
        .apiKey(apiKey)
        .build()

    
    private val restClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    /**
     * Asks Gemini which movie the image shows. The prompt requires strict JSON in a fixed schema,
     * which is parsed into [GeminiMovieCandidatesResponse] (up to a few candidates, best first).
     *
     * @throws GeminiOverloadedException when Gemini is overloaded or rate-limited.
     * @throws IllegalStateException on any other SDK error or unparseable output.
     */
    suspend fun detectMoviesFromImage(
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): GeminiMovieCandidatesResponse = withContext(sdkDispatcher) {
        val instruction = buildString {
            appendLine("You are a vision assistant that identifies the movie depicted in the provided image.")
            appendLine("Ignore any user-provided text. Analyze ONLY the image.")
            appendLine()
            appendLine("Return a STRICT JSON object with the following schema (no markdown fences, no extra text):")
            appendLine("{")
            appendLine("  \"query_language\": string,   // BCP-47 language code you naturally use for notes, e.g. 'en', 'sr'\n" +
                        "  \"candidates\": [\n" +
                        "    {\n" +
                        "      \"title\": string,        // movie title best suited for TMDB search (original or widely used English title)\n" +
                        "      \"year\": number|null,   // 4-digit release year if known\n" +
                        "      \"type\": \"movie\"|\"tv\"|null,\n" +
                        "      \"confidence\": number|null, // 0..1\n" +
                        "      \"notes\": string|null\n" +
                        "    }\n" +
                        "  ],\n" +
                        "  \"not_movie_like\": boolean, // true if image is not a film poster/still\n" +
                        "  \"uncertainty_reason\": string|null // reason if candidates empty or uncertain\n")
            appendLine("}")
            appendLine()
            appendLine("Rules:")
            appendLine("- If the image is NOT a movie/TV poster or film still, set not_movie_like=true and return empty candidates.")
            appendLine("- If you are UNSURE between multiple movies, include multiple candidates ordered by confidence.")
            appendLine("- If you CANNOT identify, return empty candidates and set uncertainty_reason.")
            appendLine("- Output ONLY the JSON object. No prose, no markdown, no code fences.")
        }

        val textPart = Part.builder().text(instruction).build()
        val imagePart = Part.builder()
            .inlineData(
                Blob.builder()
                    .data(imageBytes)
                    .mimeType(mimeType)
                    .build()
            ).build()

        val content = Content.builder()
            .role("user")
            .parts(listOf(textPart, imagePart))
            .build()

        val sb = StringBuilder()
        val gemini31flashLite = "gemini-3.1-flash-lite"
        val gemini35flash = "gemini-3.5-flash"
        val gemini3flashpreview = "gemini-3-flash-preview" //KORISCEN DO SAD
        try {
            client.models.generateContentStream(gemini31flashLite, content, null)
                .forEach { response ->
                    val t = response.text()
                    if (!t.isNullOrEmpty()) sb.append(t)
                }
        } catch (e: Exception) {
            val overloadedCode = e is ApiException && (e.code() == 429 || e.code() == 503)
            if (overloadedCode || isGeminiOverloadSignal(e.message)) {
                throw GeminiOverloadedException("Gemini je trenutno preopterećen: ${e.message}")
            }
            throw IllegalStateException("Gemini SDK error: ${e.message}", e)
        }

        val raw = sb.toString().trim()
        val jsonString = sanitizeToJson(raw)
        try {
            JSON.decodeFromString(GeminiMovieCandidatesResponse.serializer(), jsonString)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to parse Gemini JSON: ${e.message}. Raw=\n$raw")
        }
    }


    /**
     * Embedding vector for [text] via the Gemini REST API (`gemini-embedding-001`).
     * [taskType] is RETRIEVAL_DOCUMENT for movie texts and RETRIEVAL_QUERY for search queries.
     */
    suspend fun embedText(text: String, taskType: String): FloatArray {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return FloatArray(0)

        val requestBody = EmbedContentRequestDto(
            model = "models/gemini-embedding-001",
            content = EmbedContentPartsDto(parts = listOf(EmbedContentTextDto(text = trimmed))),
            embedContentConfig = EmbedContentConfigDto(taskType = taskType)
        )

        val response = try {
            restClient.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent") {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body<EmbedContentResponseDto>()
        } catch (e: Exception) {
            if (isGeminiOverloadSignal(e.message)) {
                throw GeminiOverloadedException("Gemini je trenutno preopterećen: ${e.message}")
            }
            throw IllegalStateException("Gemini embedding greška: ${e.message}", e)
        }

        val values = response.embedding?.values ?: emptyList()
        return FloatArray(values.size) { i -> values[i] }
    }

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun sanitizeToJson(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            // Remove Markdown code fences such as ```json ... ```.
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            val endIdx = s.lastIndexOf("```")
            if (endIdx >= 0) s = s.substring(0, endIdx).trim()
        }
        // Extract the first balanced {...} block.
        val start = s.indexOf('{')
        if (start == -1) return s
        var depth = 0
        for (i in start until s.length) {
            if (s[i] == '{') depth++
            if (s[i] == '}') {
                depth--
                if (depth == 0) {
                    return s.substring(start, i + 1)
                }
            }
        }
        return s
    }
}

/** Gemini's answer for an image: candidate movies, or `notMovieLike` if the image is not a movie. */
@Serializable
data class GeminiMovieCandidatesResponse(
    @SerialName("query_language") val queryLanguage: String? = null,
    val candidates: List<MovieCandidate> = emptyList(),
    @SerialName("not_movie_like") val notMovieLike: Boolean = false,
    @SerialName("uncertainty_reason") val uncertaintyReason: String? = null
)

/** One movie Gemini thinks the image shows (title suitable for a TMDB search). */
@Serializable
data class MovieCandidate(
    val title: String,
    val year: Int? = null,
    val type: String? = null,
    val confidence: Double? = null,
    val notes: String? = null
)


@Serializable
private data class EmbedContentRequestDto(
    val model: String,
    val content: EmbedContentPartsDto,
    val embedContentConfig: EmbedContentConfigDto
)

@Serializable
private data class EmbedContentPartsDto(val parts: List<EmbedContentTextDto>)

@Serializable
private data class EmbedContentTextDto(val text: String)

@Serializable
private data class EmbedContentConfigDto(val taskType: String)

@Serializable
private data class EmbedContentResponseDto(val embedding: ContentEmbeddingDto? = null)

@Serializable
private data class ContentEmbeddingDto(val values: List<Float> = emptyList())