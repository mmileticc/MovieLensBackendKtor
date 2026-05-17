package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import io.ktor.http.content.*

@Serializable
data class ChatRequest(val message: String)
fun Application.configureRouting(geminiService: GeminiService) {
    routing {
        get("/") { call.respondText("OK") }

        post("/chat") {
            val req = call.receive<ChatRequest>()

            // Logovanje pitanja u konzolu
            println("\n[LOG] ${java.time.LocalTime.now()} | Zahtev: ${req.message}")

            call.response.status(HttpStatusCode.OK)
            call.respondTextWriter(contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8)) {
                try {
                    geminiService.streamChat(req.message).collect { chunk ->
                        write(chunk)
                        flush() // Gura podatke ka mreži momentalno
                    }
                    println("[LOG] Odgovor uspešno završen.")
                } catch (e: Throwable) {
                    println("[ERROR] Greška: ${e.message}")
                    write("\n[Greška na serveru]: ${e.message}")
                }
            }
        }

        post("/chat/text") {
            val message = call.receiveText().trim()
            if (message.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest)

            println("\n[LOG] ${java.time.LocalTime.now()} | TEXT-ONLY: $message")

            call.respondTextWriter(contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8)) {
                try {
                    geminiService.streamChat(message).collect { chunk ->
                        write(chunk)
                        flush()
                    }
                } catch (e: Throwable) {
                    write("\n[Error]: ${e.message}")
                }
            }
        }

        // Multipart endpoint for text + image (vision)
        post("/chat/vision") {
            val multipart = call.receiveMultipart()

            var message: String? = null
            var imageBytes: ByteArray? = null
            var imageMime: String = "image/jpeg"

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name == "message") {
                            message = part.value
                        }
                    }
                    is PartData.FileItem -> {
                        if (part.name == "image") {
                            val bytes = part.streamProvider().readBytes()
                            imageBytes = bytes
                            // Try to capture content type from upload
                            part.contentType?.let { ct ->
                                imageMime = ct.toString()
                            }
                        }
                    }
                    else -> {}
                }
                part.dispose()
            }

            if (message.isNullOrBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, "Missing form field: message")
            }
            if (imageBytes == null) {
                return@post call.respond(HttpStatusCode.BadRequest, "Missing file part: image")
            }

            println("\n[LOG] ${java.time.LocalTime.now()} | VISION: message='${message}' size=${imageBytes!!.size} mime=$imageMime")

            val result = geminiService.chatWithImage(message!!, imageBytes!!, imageMime)
            call.respondText(result, contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8))
        }
    }
}