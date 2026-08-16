package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
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

/**
 * Poredi dva taste profila (self, prijatelj) - ista logika kao GenreAffinity, samo za dvoje.
 * "Entuzijazam" po zajedničkom žanru = min(prosekA, prosekB) - namerno konzervativno, oba moraju
 * stvarno voleti žanr da bi se računao kao "zajednički", ne dovoljno da samo jedno visoko oceni.
 * match_score = prosek entuzijazma preko svih zajedničkih žanrova, skalirano na 0-100 (ocene su
 * 1-10). Nema zajedničkih žanrova -> match_score 0, prazna lista - nema "izmišljanja" rezultata.
 */
fun compareTasteProfiles(self: List<GenreAffinity>, friend: List<GenreAffinity>): FriendMatch {
    val selfByGenreId = self.associateBy { it.genreId }
    val friendByGenreId = friend.associateBy { it.genreId }
    val sharedIds = selfByGenreId.keys.intersect(friendByGenreId.keys)
    if (sharedIds.isEmpty()) return FriendMatch(matchScore = 0f, commonGenres = emptyList(), sharedGenreIds = emptyList())

    val enthusiasmByGenreId = sharedIds.associateWith { genreId ->
        minOf(selfByGenreId.getValue(genreId).averageRating, friendByGenreId.getValue(genreId).averageRating)
    }
    val matchScore = (enthusiasmByGenreId.values.average() * 10.0).toFloat().coerceIn(0f, 100f)
    val sortedIds = enthusiasmByGenreId.entries.sortedByDescending { it.value }.map { it.key }
    // "Zajednički žanrovi" za prikaz = samo oni gde oboje stvarno vole (>=6/10), ne baš svaki
    // dodir - inače bi "zajednički žanr" značio i "oboje smo videli po jedan loše ocenjen film".
    val commonGenres = sortedIds
        .filter { enthusiasmByGenreId.getValue(it) >= 6.0 }
        .take(3)
        .map { selfByGenreId.getValue(it).genreName }

    return FriendMatch(matchScore = matchScore, commonGenres = commonGenres, sharedGenreIds = sortedIds)
}

class TasteProfileRepository {

    suspend fun getTasteProfile(userId: String): List<GenreAffinity> = dbQuery {
        // "WATCHED" je isti string literal kao ACTION_WATCHED u UserActionsRepository.kt (private
        // tamo, pa se ne uvozi - vidi napomenu tamo za kontekst WATCHLIST/WATCHED dizajna).
        val ratedRows = UserActionsTable
            .join(MoviesTable, JoinType.INNER, additionalConstraint = { UserActionsTable.movieId eq MoviesTable.tmdbId })
            .select {
                (UserActionsTable.userId eq userId) and
                    (UserActionsTable.actionType eq "WATCHED") and
                    (UserActionsTable.rating.isNotNull())
            }
            .map { row -> row[UserActionsTable.rating]!! to decodeGenres(row[MoviesTable.genres]) }

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
        // tebe". Ovde koristimo Bayesian shrinkage (isti princip kao "IMDB weighted rating") SAMO za
        // REDOSLED - žanru sa malo filmova se prosek "povlači" ka korisnikovom globalnom proseku dok
        // ne skupi dovoljno filmova da mu se veruje; `averageRating` u vraćenom objektu ostaje
        // NEDIRNUTO sirovo (prikaz na profilu i compareTasteProfiles i dalje rade sa pravim prosekom).
        val globalAverage = ratedRows.map { it.first }.average().takeIf { !it.isNaN() } ?: 6.5
        val priorWeight = 5.0 // "virtuelnih filmova" po globalnom proseku dodatih pre nego što žanru verujemo

        ratingSumByGenreId.keys
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
            .sortedByDescending { affinity ->
                (globalAverage * priorWeight + affinity.averageRating * affinity.movieCount) / (priorWeight + affinity.movieCount)
            }
    }
}
