package com.milinko.db

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseAdminInitializer {
    fun init() {
        // Ako je već inicijalizovano, ne inicijalizuj ponovo
        if (FirebaseApp.getApps().isNotEmpty()) {
            println("[FIREBASE] Firebase je već inicijalizovan.")
            return
        }

        try {
            val serviceAccountJson = System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON")

            val credentials = if (!serviceAccountJson.isNullOrBlank()) {
                // Ako je env var postavljen, koristi ga kao JSON string
                println("[FIREBASE] Korišćenje FIREBASE_SERVICE_ACCOUNT_JSON from environment variable.")
                GoogleCredentials.fromStream(serviceAccountJson.byteInputStream())
            } else {
                // Fallback na Application Default Credentials (za lokalno testiranje ili Google Cloud)
                println("[FIREBASE] Korišćenje Application Default Credentials.")
                GoogleCredentials.getApplicationDefault()
            }

            val options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .build()

            FirebaseApp.initializeApp(options)
            println("[FIREBASE] Firebase Admin SDK je uspešno inicijalizovan!")
        } catch (e: Exception) {
            println("[FIREBASE] GREŠKA pri inicijalizaciji Firebase Admin SDK-a: ${e.message}")
            e.printStackTrace()
            throw IllegalStateException("Neuspešna inicijalizacija Firebase Admin SDK-a", e)
        }
    }
}

