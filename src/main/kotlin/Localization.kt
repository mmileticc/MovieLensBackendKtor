package com.milinko

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin

/**
 * Lokalizacija poruka koje backend šalje korisniku (oktobar 2026 - Android aplikacija dobila
 * engleski jezik). Aplikacija šalje `Accept-Language: en` ili `sr` uz svaki zahtev.
 *
 * Umesto da se svako od ~60 mesta sa `ErrorResponse("...")` / `IllegalArgumentException("...")`
 * menja, poruke ostaju na srpskom u kodu, a [ErrorMessageLocalization] plugin ih na izlazu
 * prevodi na engleski kad klijent to traži. Nova poruka bez prevoda se i dalje šalje (na srpskom)
 * - treba je samo dodati u [ENGLISH_MESSAGES].
 */
fun ApplicationCall.prefersEnglish(): Boolean =
    request.headers[HttpHeaders.AcceptLanguage]?.trim()?.lowercase()?.startsWith("en") == true

private val ENGLISH_MESSAGES = mapOf(
    // Opšte / StatusPages
    "Neispravan zahtev" to "Invalid request",
    "Došlo je do greške na serveru" to "Something went wrong on the server",
    "Nevalidan id" to "Invalid id",
    "Nevalidan tmdbId" to "Invalid movie id",
    "Query parametar je obavezan" to "Query parameter is required",
    "Query parametar 'q' je obavezan" to "Query parameter 'q' is required",
    "Neočekivana greška pri čuvanju" to "Unexpected error while saving",

    // Spoljni servisi (respondUpstreamError)
    "TMDB pretraga nije uspela" to "Search is currently unavailable",
    "TMDB trending nije uspeo" to "Popular movies are currently unavailable",
    "TMDB featured nije uspeo" to "Recommendations are currently unavailable",
    "TMDB žanrovi nisu dostupni" to "Genres are currently unavailable",
    "TMDB detalji nisu uspeli" to "Movie details are currently unavailable",
    "TMDB podaci o glumcu nisu uspeli" to "Person details are currently unavailable",
    "Repertoar trenutno nije dostupan" to "Cinema listings are currently unavailable",
    "Semantička pretraga nije uspela" to "Smart search failed",
    "Analiza slike trenutno nije uspela" to "Image analysis failed",
    "Gemini je trenutno preopterećen. Pokušaj ponovo za par trenutaka." to
        "The recognition service is busy right now. Please try again in a moment.",
    "Slika je prevelika - probaj sa manjom/kompresovanijom slikom" to "The image is too large - try a smaller one",
    "Nedostaje slika" to "Missing image",
    "Nepodržan format slike - koristi JPEG ili PNG" to "Unsupported image format - use JPEG or PNG",
    "Previše zahteva. Pokušaj ponovo za minut." to "Too many requests. Please try again in a minute.",
    "Prijava trenutno nije moguća, pokušaj ponovo" to "Sign-in is currently unavailable, please try again",

    // Filmovi / glumci
    "Film nije pronađen" to "Movie not found",
    "Glumac nije pronađen" to "Person not found",

    // Korisnici / profil
    "Korisnik nije pronađen" to "User not found",
    "Nemaš pristup ovom profilu" to "You don't have access to this profile",
    "Nemaš pristup ovoj listi" to "You don't have access to this list",
    "Nemaš pristup ovoj akciji" to "You don't have access to this action",
    "Korisničko ime mora imati 3-30 karaktera i sme sadržati samo slova, brojeve, tačku i donju crtu" to
        "Username must be 3-30 characters and may contain only letters, digits, dot and underscore",

    // Prijatelji
    "Nedostaje friendId" to "Missing friend id",
    "Nema zahteva za prihvatanje" to "There is no request to accept",
    "Ne možeš poslati zahtev samom sebi" to "You can't send a friend request to yourself",
    "Predlozi su dostupni samo za prijatelje" to "Suggestions are available only for friends",

    // Komentari
    "Komentar ne može biti prazan" to "Comment can't be empty",
    "Komentar nije pronađen" to "Comment not found",
    "Ne možeš lajkovati sopstveni komentar" to "You can't like your own comment",
    "Ne možeš obrisati ovaj komentar" to "You can't delete this comment"
)

// Poruke sa promenljivim delom (ime, id...).
private val ENGLISH_PATTERNS: List<Pair<Regex, (MatchResult) -> String>> = listOf(
    Regex("^Korisničko ime \"(.+)\" je već zauzeto$") to { m -> "Username \"${m.groupValues[1]}\" is already taken" },
    Regex("^Film sa tmdbId=(\\d+) ne postoji na TMDB-u$") to { m -> "Movie ${m.groupValues[1]} does not exist on TMDB" },
    Regex("^Nepoznata akcija: (.*)$") to { m -> "Unknown action: ${m.groupValues[1]}" }
)

fun translateToEnglish(message: String): String {
    ENGLISH_MESSAGES[message]?.let { return it }
    for ((regex, build) in ENGLISH_PATTERNS) {
        regex.matchEntire(message)?.let { return build(it) }
    }
    return message
}

/**
 * Odgovori koje šalje StatusPages ne prolaze kroz [ErrorMessageLocalization] (njegov onCallRespond
 * se tad već izvršio), pa ih StatusPages prevodi sam preko ove funkcije.
 */
fun ApplicationCall.localizedError(message: String): ErrorResponse =
    ErrorResponse(if (prefersEnglish()) translateToEnglish(message) else message)

/** Prevodi `ErrorResponse` telo na engleski za klijente koji šalju `Accept-Language: en`. */
val ErrorMessageLocalization = createApplicationPlugin(name = "ErrorMessageLocalization") {
    onCallRespond { call, _ ->
        if (!call.prefersEnglish()) return@onCallRespond
        transformBody { body ->
            if (body is ErrorResponse) ErrorResponse(translateToEnglish(body.error)) else body
        }
    }
}
