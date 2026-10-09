package com.milinko.db

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("FirebaseAdminInitializer")

/**
 * Initializes the Firebase Admin SDK, used to verify Google sign-in ID tokens and to send push
 * notifications.
 *
 * Credentials come from `FIREBASE_SERVICE_ACCOUNT_JSON` (the service account JSON as a string,
 * used on Render and in CI) or, if that is not set, from Application Default Credentials.
 */
object FirebaseAdminInitializer {
    /** Idempotent: safe to call more than once. Throws [IllegalStateException] if credentials are invalid. */
    fun init() {
        if (FirebaseApp.getApps().isNotEmpty()) {
            logger.info("[FIREBASE] Firebase je već inicijalizovan.")
            return
        }

        try {
            val serviceAccountJson = System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON")

            val credentials = if (!serviceAccountJson.isNullOrBlank()) {
                logger.info("[FIREBASE] Korišćenje FIREBASE_SERVICE_ACCOUNT_JSON from environment variable.")
                GoogleCredentials.fromStream(serviceAccountJson.byteInputStream())
            } else {
                // Local development with `gcloud auth application-default login`, or Google Cloud.
                logger.info("[FIREBASE] Korišćenje Application Default Credentials.")
                GoogleCredentials.getApplicationDefault()
            }

            val options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build()

            FirebaseApp.initializeApp(options)
            logger.info("[FIREBASE] Firebase Admin SDK je uspešno inicijalizovan!")
        } catch (e: Exception) {
            logger.error("[FIREBASE] GREŠKA pri inicijalizaciji Firebase Admin SDK-a: ${e.message}", e)
            throw IllegalStateException("Neuspešna inicijalizacija Firebase Admin SDK-a", e)
        }
    }
}

