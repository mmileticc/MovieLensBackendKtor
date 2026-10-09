package com.milinko.services

import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns text into an embedding vector, so [CinemaSemanticIndex] does not depend on one provider.
 *
 * Two implementations exist side by side (Gemini and a local MiniLM model) to compare quality.
 * Documents and queries are embedded separately because Gemini supports a task type for each
 * (better results); the local model treats both the same.
 */
interface TextEmbedder {
    /** Short name for logs ("Gemini", "Lokalno (MiniLM)"); does not affect the vector. */
    val label: String
    /** Value of the `engine` API parameter ("gemini" or "local"); also the cache key in MovieEmbeddingsTable. */
    val key: String
    /** Embeds a document (a movie's title and overview) for the index. */
    suspend fun embedDocument(text: String): FloatArray
    /** Embeds a user's search query. */
    suspend fun embedQuery(text: String): FloatArray
}

/** Gemini embeddings via [GeminiService.embedText] (REST). */
class GeminiTextEmbedder(private val geminiService: GeminiService) : TextEmbedder {
    override val label = "Gemini"
    override val key = "gemini"
    override suspend fun embedDocument(text: String): FloatArray = geminiService.embedText(text, "RETRIEVAL_DOCUMENT")
    override suspend fun embedQuery(text: String): FloatArray = geminiService.embedText(text, "RETRIEVAL_QUERY")
}

/**
 * Local MiniLM (all-MiniLM-L6-v2, 384 dimensions) through langchain4j. The ONNX runtime and the
 * model weights ship inside the jar: no download, no network call, no API key. The model is loaded
 * lazily once (expensive) and then reused (each embed() call is fast).
 *
 * Trade-off: MiniLM is much smaller than Gemini's model and trained mostly on English, so Serbian
 * queries against English overviews work noticeably worse. It is kept for comparison and is only
 * enabled with ENABLE_LOCAL_EMBEDDING=true (it does not fit in Render's free-tier memory).
 */
class LocalTextEmbedder : TextEmbedder {
    override val label = "Lokalno (MiniLM)"
    override val key = "local"
    private val model by lazy { AllMiniLmL6V2EmbeddingModel() }

    override suspend fun embedDocument(text: String): FloatArray = embed(text)
    override suspend fun embedQuery(text: String): FloatArray = embed(text)

    private suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext FloatArray(0)
        model.embed(trimmed).content().vector()
    }
}
