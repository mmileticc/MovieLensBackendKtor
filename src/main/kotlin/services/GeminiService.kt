package com.milinko.services

import com.google.genai.Client
import com.google.genai.types.Blob
import com.google.genai.types.Content
import com.google.genai.types.Part
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.concurrent.Executors

class GeminiService(apiKey: String) {

    // Koristimo namenski thread za blokirajuće SDK pozive da ne bismo gušili Ktor
    private val sdkDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val client = Client.builder()
        .apiKey(apiKey)
        .build()

    /**
     * Detekcija filma sa slike: ignoriše se korisnički tekst i šalje se strogi uput Gemini‑ju
     * da vrati isključivo JSON prema definisanoj šemi. Rezultat se parsira u [GeminiMovieCandidatesResponse].
     */
    suspend fun detectMoviesFromImage(
        imageBytes: ByteArray,
        mimeType: String = "image/jpeg"
    ): GeminiMovieCandidatesResponse = withContext(sdkDispatcher) {
        val base64Image = Base64.getEncoder().encodeToString(imageBytes)

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
                    .data(base64Image)
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

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun sanitizeToJson(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            // remove markdown fences like ```json ... ```
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            val endIdx = s.lastIndexOf("```")
            if (endIdx >= 0) s = s.substring(0, endIdx).trim()
        }
        // Heuristics: extract first {...} balanced block
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

@Serializable
data class GeminiMovieCandidatesResponse(
    @SerialName("query_language") val queryLanguage: String? = null,
    val candidates: List<MovieCandidate> = emptyList(),
    @SerialName("not_movie_like") val notMovieLike: Boolean = false,
    @SerialName("uncertainty_reason") val uncertaintyReason: String? = null
)

@Serializable
data class MovieCandidate(
    val title: String,
    val year: Int? = null,
    val type: String? = null,
    val confidence: Double? = null,
    val notes: String? = null
)