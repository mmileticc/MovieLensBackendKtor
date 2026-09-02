package com.milinko.services

import com.google.genai.Client
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
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Bugfix (2. septembar 2026, korisnički izveštaj - "pukla mi je greška za Gemini preopterećenost,
 * mislim da to nismo obradili") - RANIJE su sve greške iz Gemini SDK/REST poziva (vision detekcija
 * I embedding za semantičku pretragu) bacane kao generički `IllegalStateException("Gemini SDK
 * error: ${e.message}")` - taj SIROV tekst (npr. "503 UNAVAILABLE. {\"error\":{\"message\":\"The
 * model is overloaded...\"}}") se onda provlačio sve do Android klijenta i prikazivao korisniku
 * direktno, bez ikakvog prevoda. Ovaj tip omogućava rutama (Routing.kt/MovieRouting.kt) da ga
 * uhvate POSEBNO i vrate čist HTTP 503 (umesto 502/500) - Android strana onda taj status kod
 * prepoznaje i prikazuje smislenu, actionable poruku (videti VisionRepository.kt).
 */
class GeminiOverloadedException(message: String) : Exception(message)

/**
 * Prepoznaje tipične signale da je Gemini API privremeno preopterećen ili da je pogođen rate
 * limit, na osnovu teksta greške koju SDK/REST poziv vrati (Gemini ne vraća strukturisan error
 * kod kroz `com.google.genai` SDK verziju koju projekat koristi - videti napomenu uz `restClient`
 * niže - pa se oslanjamo na poznate fraze/kodove u tekstu poruke).
 */
private fun isGeminiOverloadSignal(message: String?): Boolean {
    val text = message.orEmpty()
    return listOf("overloaded", "UNAVAILABLE", "RESOURCE_EXHAUSTED", "rate limit", " 429", " 503")
        .any { text.contains(it, ignoreCase = true) }
}

class GeminiService(private val apiKey: String) {

    // Koristimo namenski thread za blokirajuće SDK pozive da ne bismo gušili Ktor
    private val sdkDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val client = Client.builder()
        .apiKey(apiKey)
        .build()

    // Faza D (17. avgust 2026) - embedText ispod NAMERNO ne koristi `client` (com.google.genai SDK)
    // iznad - verzija te biblioteke koju projekat koristi (0.1.0) je iz perioda PRE nego što je
    // embedContent uopšte postojao u SDK-u (dodato tek u 0.3.0, uz breaking change na Blob.data()
    // koji bi pogodio već testiran detectMoviesFromImage iznad). Umesto da se diže verzija cele
    // zavisnosti (rizik po već radeći vision kod, bez mogućnosti da se ovde build stvarno proveri),
    // embedContent REST endpoint se zove direktno preko Ktor HttpClient-a - isti obrazac kao
    // TmdbService (CIO engine, JSON content negotiation), potpuno izolovano od SDK verzije.
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
            if (isGeminiOverloadSignal(e.message)) {
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
     * Faza D (17. avgust 2026, semantička pretraga filmova u bioskopima) - embeduje proizvoljan
     * tekst preko Gemini REST API-ja (`models/gemini-embedding-001:embedContent`, dokumentacija:
     * ai.google.dev/api/embeddings) - direktan HTTP poziv, ne SDK (videti obrazloženje na `restClient`
     * iznad). `taskType` razdvaja embed filma ("RETRIEVAL_DOCUMENT") od embed-a korisničkog upita
     * ("RETRIEVAL_QUERY") - Gemini preporučuje ovo razdvajanje radi boljeg kvaliteta pretrage (isti
     * tekst embedovan sa pogrešnim taskType-om i dalje radi, samo lošije rangira rezultate). Vraća
     * prazan niz ako Gemini ne vrati embedding (npr. prazan tekst) - pozivalac (CinemaSemanticIndex)
     * treba da tretira to kao "film se ne indeksira", ne kao crash.
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

// Faza D (17. avgust 2026) - DTO-ovi tačno prate REST šemu iz ai.google.dev/api/embeddings
// (models.embedContent) - namerno bez zavisnosti od com.google.genai SDK-a, videti obrazloženje
// na GeminiService.restClient iznad.
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