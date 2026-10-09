package com.milinko.auth

import com.milinko.db.UserDto
import kotlinx.serialization.Serializable

/** Body of `POST /api/auth/firebase`: the Firebase ID token from Google sign-in in the app. */
@Serializable
data class AuthFirebaseRequest(
    val idToken: String
)

/** Successful sign-in: the backend session JWT (`appToken`) and the user. */
@Serializable
data class AuthResponse(
    val appToken: String,
    val user: UserDto
)

/** Error body for authentication failures. */
@Serializable
data class AuthErrorResponse(
    val error: String
)

/** Claims carried by the backend session JWT. */
data class JwtClaims(
    val userId: String,
    val firebaseUid: String
)

