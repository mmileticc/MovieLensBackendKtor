package com.example

import com.google.genai.Client
import com.google.genai.types.Blob
import com.google.genai.types.Content
import com.google.genai.types.Part
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.Executors

class GeminiService(apiKey: String) {

    // Koristimo namenski thread za blokirajuće SDK pozive da ne bismo gušili Ktor
    private val sdkDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val client = Client.builder()
        .apiKey(apiKey)
        .build()

    fun streamChat(message: String): Flow<String> = callbackFlow {
        // Pokrećemo SDK poziv u posebnom coroutine-u
        val job = launch(sdkDispatcher) {
            try {
                val imageBytes = Files.readAllBytes(
                    Paths.get("lepa sela.jpg")
                )
                val base64Image = Base64.getEncoder().encodeToString(imageBytes)
                val textPart = Part.builder()
                    .text(message)
                    .build()

                val imagePart = Part.builder()
                    .inlineData(
                        Blob.builder()
                            .data(base64Image)
                            .mimeType("image/jpeg")
                            .build()
                    )
                    .build()
                val content = Content.builder()
                    .role("user")
                    .parts(
                        listOf(textPart, imagePart)
                    ).build()

                // Tačan naziv modela iz tvog uspešnog testa
                client.models.generateContentStream("gemini-3-flash-preview", content, null)
                    .forEach { response ->
                        val text = response.text()
                        if (!text.isNullOrEmpty()) {
                            trySend(text) // Šalje komadić teksta ka ruti
                        }
                    }
                close() // Signalizira da je stream gotov
            } catch (e: Exception) {
                println("[ERROR] SDK Greška: ${e.message}")
                close(e) // Šalje grešku ruti
            }
        }

        // Čisti resurse ako klijent (Android) prekine vezu pre vremena
        awaitClose { job.cancel() }
    }

    // Vision chat (non-stream): accepts image bytes from request and returns the full concatenated answer
    suspend fun chatWithImage(message: String, imageBytes: ByteArray, mimeType: String = "image/jpeg"): String =
        withContext(sdkDispatcher) {
            val base64Image = Base64.getEncoder().encodeToString(imageBytes)

            val textPart = Part.builder()
                .text(message)
                .build()

            val imagePart = Part.builder()
                .inlineData(
                    Blob.builder()
                        .data(base64Image)
                        .mimeType(mimeType)
                        .build()
                )
                .build()

            val content = Content.builder()
                .role("user")
                .parts(listOf(textPart, imagePart))
                .build()

            val sb = StringBuilder()
            try {
                client.models.generateContentStream("gemini-3-flash-preview", content, null)
                    .forEach { response ->
                        val t = response.text()
                        if (!t.isNullOrEmpty()) sb.append(t)
                    }
            } catch (e: Exception) {
                // Propagate as IllegalState to hit StatusPages mapping nicely
                throw IllegalStateException("Gemini SDK error: ${e.message}", e)
            }
            sb.toString()
        }
}