package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import com.milinko.services.TmdbGenre
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select

/**
 * Prosečna ocena po žanru za datog korisnika - "koliko voliš X žanr" izvedeno direktno iz ocena,
 * bez skrivene matematike (Faza 4, MOVIELENS_ROADMAP.md). Namerno NEMA filtriranja po minimalnom
 * broju filmova - `movieCount` se vraća zajedno sa ocenom da bi klijent mogao transparentno da
 * pokaže "na osnovu koliko filmova" umesto da nešto sakrivamo/izmišljamo prag.
 */
data class GenreAffinity(
    val genreId: Int,
    val genreName: String,
    val averageRating: Double,
    val movieCount: Int
)

/** Rezultat poređenja dva taste profila - videti [compareTasteProfiles]. */
data class FriendMatch(
    val matchScore: Float, // 0-100
    val commonGenres: List<String>, // top zajednički žanrovi koje OBOJICA stvarno vole, za prikaz
    val sharedGenreIds: List<Int> // svi zajednički žanrovi, sortirano po "entuzijazmu" - za movienight discover upit
)

// "Kazna po neizvesnosti" (Wilson/lower-confidence-bound duh) - deljena između getTasteProfile
// (rangiranje "Tvoj ukus") i compareTasteProfiles (rangiranje zajedničkih žanrova za prijatelje/
// Movie Night) ispod, videti opširno obrazloženje u getTasteProfile. Izvučeno u jednu konstantu i
// funkciju da se formula ne duplira/razilazi između dva mesta koja rade suštinski isti posao.
private const val CONFIDENCE_PENALTY_Z = 1.5

private fun GenreAffinity.confidenceScore(): Double =
    averageRating - CONFIDENCE_PENALTY_Z / kotlin.math.sqrt(movieCount.toDouble())

/**
 * Poredi dva taste profila (self, prijatelj) - ista logika kao GenreAffinity, samo za dvoje.
 * "Entuzijazam" po zajedničkom žanru = min(penalizovani skor A, penalizovani skor B) - namerno
 * konzervativno, oba moraju stvarno I POUZDANO voleti žanr da bi visoko rangirao.
 * match_score = prosek entuzijazma preko svih zajedničkih žanrova, skalirano na 0-100 (ocene su
 * 1-10). Nema zajedničkih žanrova -> match_score 0, prazna lista - nema "izmišljanja" rezultata.
 *
 * Dorada (17. avgust 2026, korisničko pitanje "da li je primenjeno i na movie night predloge") -
 * ranije je ovde `enthusiasmByGenreId` koristio SIROV `averageRating`, dok je `getTasteProfile`
 * ispod već bio popravljen (dorada #2, 16. avgust) da koristi kaznu-po-neizvesnosti. Bez ove
 * popravke, žanr gde oboje imaju SAMO PO 1 film ocenjen 10/10 je i dalje mogao da "pobedi" žanr sa
 * 5+ zajedničkih omiljenih filmova i blago nižim prosekom - isti bug kao u taste profilu, samo za
 * DVA korisnika umesto jednog. `sortedIds` (koji ide u `FriendsRouting.kt` `topGenreIds` za
 * movienight-suggestions TMDB discover upit) i `matchScore` sad koriste penalizovan skor.
 * `commonGenres` NAMERNO i dalje proverava SIROV prosek (>=6.0) - to pitanje je "da li oboje
 * STVARNO vole ovaj žanr" (činjenica o ukusu), ne "koliko smo sigurni u tu činjenicu" (pouzdanost)
 * - pouzdanost utiče na REDOSLED/match_score, ne na da li žanr uopšte kvalifikuje kao "omiljen".
 */
fun compareTasteProfiles(self: List<GenreAffinity>, friend: List<GenreAffinity>): FriendMatch {
    val selfByGenreId = self.associateBy { it.genreId }
    val friendByGenreId = friend.associateBy { it.genreId }
    val sharedIds = selfByGenreId.keys.intersect(friendByGenreId.keys)
    if (sharedIds.isEmpty()) return FriendMatch(matchScore = 0f, commonGenres = emptyList(), sharedGenreIds = emptyList())

    val enthusiasmByGenreId = sharedIds.associateWith { genreId ->
        minOf(selfByGenreId.getValue(genreId).confidenceScore(), friendByGenreId.getValue(genreId).confidenceScore())
    }
    val matchScore = (enthusiasmByGenreId.values.average() * 10.0).toFloat().coerceIn(0f, 100f)
    val sortedIds = enthusiasmByGenreId.entries.sortedByDescending { it.value }.map { it.key }
    // "Zajednički žanrovi" za prikaz = samo oni gde oboje stvarno vole (sirov prosek >=6/10), ne
    // baš svaki dodir - inače bi "zajednički žanr" značio i "oboje smo videli po jedan loše
    // ocenjen film".
    val commonGenres = sortedIds
        .filter { genreId ->
            minOf(selfByGenreId.getValue(genreId).averageRating, friendByGenreId.getValue(genreId).averageRating) >= 6.0
        }
        .take(3)
        .map { selfByGenreId.getValue(it).genreName }

    return FriendMatch(matchScore = matchScore, commonGenres = commonGenres, sharedGenreIds = sortedIds)
}

class TasteProfileRepository {

    suspend fun getTasteProfile(userId: String): List<GenreAffinity> =
        getTasteProfiles(listOf(userId))[userId].orEmpty()

    /**
     * Taste profili za više korisnika jednim upitom (npr. lista prijatelja, gde se svaki prijatelj
     * poredi sa korisnikom). Korisnik bez ocena dobija praznu listu.
     */
    suspend fun getTasteProfiles(userIds: List<String>): Map<String, List<GenreAffinity>> {
        if (userIds.isEmpty()) return emptyMap()
        // "WATCHED" je isti string literal kao ACTION_WATCHED u UserActionsRepository.kt (private
        // tamo, pa se ne uvozi - vidi napomenu tamo za kontekst WATCHLIST/WATCHED dizajna).
        val ratedRowsByUser = dbQuery {
            UserActionsTable
                .join(MoviesTable, JoinType.INNER, additionalConstraint = { UserActionsTable.movieId eq MoviesTable.tmdbId })
                .select {
                    (UserActionsTable.userId inList userIds) and
                        (UserActionsTable.actionType eq "WATCHED") and
                        (UserActionsTable.rating.isNotNull())
                }
                .map { row ->
                    row[UserActionsTable.userId] to (row[UserActionsTable.rating]!! to decodeGenres(row[MoviesTable.genres]))
                }
                .groupBy({ it.first }, { it.second })
        }
        return userIds.associateWith { buildTasteProfile(ratedRowsByUser[it].orEmpty()) }
    }
}

private fun buildTasteProfile(ratedRows: List<Pair<Int, List<TmdbGenre>>>): List<GenreAffinity> {
    // Grupiše se po genreId (ne po imenu - stabilnije, izbegava eventualne duple/lokalizovane
    // nazive), ime se čuva samo za prikaz.
    val ratingSumByGenreId = mutableMapOf<Int, Double>()
    val countByGenreId = mutableMapOf<Int, Int>()
    val nameByGenreId = mutableMapOf<Int, String>()
    for ((rating, genres) in ratedRows) {
        for (genre in genres) {
            ratingSumByGenreId[genre.id] = (ratingSumByGenreId[genre.id] ?: 0.0) + rating
            countByGenreId[genre.id] = (countByGenreId[genre.id] ?: 0) + 1
            nameByGenreId[genre.id] = genre.name
        }
    }

    // Bag korigovan (16. avgust 2026, korisnička žalba "predlozi nekad bas brljaju"): ranije se
    // rangiralo ČISTO po averageRating (pa tek onda po movieCount kao tie-breaker), što znači da
    // je JEDAN film ocenjen 10/10 u sporednom žanru upadao na vrh profila iznad žanra sa npr. 15
    // filmova i prosekom 8/10 - taj slučajni/sporedni žanr bi onda dominirao "Predloženo za
    // tebe".
    //
    // Dorada #2 (16. avgust 2026, korisnik pokazao konkretan primer): prvi pokušaj (Bayesian
    // shrinkage ka korisnikovom globalnom proseku) NIJE bio dovoljan - npr. Fantasy(10.0, 1 film)
    // je ostajao iznad Action(9.5, 4 filma), jer shrinkage-ka-proseku matematički NE MOŽE da
    // obrne redosled dva žanra čiji sirovi proseci padaju sa suprotnih strana proseka (žanr iznad
    // proseka se povlači naniže ali nikad ne padne ispod njega samo zbog malog uzorka). Zamenjeno
    // sa "kazna po neizvesnosti" (isti duh kao Wilson/lower-confidence-bound rangiranje) -
    // oduzima se CONFIDENCE_PENALTY_Z / sqrt(movieCount) direktno od ocene, bez oslanjanja na bilo
    // čiji prosek - žanr sa 1 filmom uvek dobija veliku kaznu (z=1.5 -> -1.5) nezavisno od ostatka
    // profila, dok žanr sa 4+ filmova dobija mnogo manju. `averageRating` u vraćenom objektu
    // ostaje NEDIRNUTO sirovo (prikaz na profilu i dalje radi sa pravim prosekom - ista logika
    // sad postoji i na Android strani, ProfileScreen.kt TasteProfileSection, za "Tvoj ukus"
    // prikaz koji ne prolazi kroz ovu rutu). Deljena `confidenceScore()` funkcija (iznad
    // klase) - `compareTasteProfiles` (prijatelji/Movie Night) koristi istu formulu, videti
    // dorada 17. avgust 2026.
    return ratingSumByGenreId.keys
        .map { genreId ->
            val count = countByGenreId.getValue(genreId)
            val average = ratingSumByGenreId.getValue(genreId) / count
            GenreAffinity(
                genreId = genreId,
                genreName = nameByGenreId.getValue(genreId),
                averageRating = average,
                movieCount = count
            )
        }
        .sortedByDescending { affinity -> affinity.confidenceScore() }
}
