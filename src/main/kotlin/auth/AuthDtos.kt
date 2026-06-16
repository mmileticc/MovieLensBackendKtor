package com.milinko.auth

import com.milinko.db.UserDto
import kotlinx.serialization.Serializable

/**
 * Request DTO za Firebase ID token verification
 */
@Serializable
data class AuthFirebaseRequest(
    val idToken: String
)

/**
 * Response DTO nakon uspešne Firebase autentifikacije
 */
@Serializable
data class AuthResponse(
    val appToken: String,
    val user: UserDto
)

/**
 * Error response DTO
 */
@Serializable
data class AuthErrorResponse(
    val error: String
)

/**
 * JWT Claims DTO za ekstrakciju iz tokena
 */
data class JwtClaims(
    val userId: String,
    val firebaseUid: String
)

