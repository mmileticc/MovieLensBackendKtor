package com.milinko.db


import com.milinko.db.DatabaseFactory.dbQuery
import com.milinko.scraping.model.ScrapedMovie
import com.milinko.util.CINEMA_TIME_ZONE
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ScrapedMoviesTable")

// 1. Mapiranje tabele iz baze u Kotlin objekat
object ScrapedMoviesTable : Table("scraped_movies") {
    val id = integer("id").autoIncrement()
    val title = varchar("title", 255)
    val originalTitle = varchar("original_title", 255).nullable()
    val cinemaName = varchar("cinema_name", 100)
    val cinemaLocation = varchar("cinema_location", 255)
    val latitude = double("latitude").nullable()
    val longitude = double("longitude").nullable()
    val bookingUrl = text("booking_url")
    val isUpcoming = bool("is_upcoming").default(false)

    val tmdbId = integer("tmdb_id").nullable()
    // Eksplicitan UTC default u Kotlinu (umesto oslanjanja na podrazumevanu vrednost kolone u
    // Postgres-u koja nije vidljiva ovde) - dosledno svim ostalim *Table.kt fajlovima. Poređenje
    // datuma u Application.kt (configureScrapingLifecycle) pretpostavlja da je ovo UTC.
    val createdAt = datetime("created_at").clientDefault { Clock.System.now().toLocalDateTime(TimeZone.UTC) }
    override val primaryKey = PrimaryKey(id)

    init {
        // Indeks već postoji u bazi; mapiran ovde da ga Exposed ne prijavljuje kao nepoznat.
        index("idx_scraped_tmdb_id", false, tmdbId)
    }
}

// 2. Klasa koja sadrži logiku za rad sa podacima
class ScrapedMoviesRepository {

    /**
     * NAMERNO briše samo bioskope koji su STVARNO vratili nešto ovog ciklusa (per-bioskop zamena),
     * ne celu tabelu (`deleteAll()`, kako je ranije bilo). Ako je jedan bioskop te noći vratio
     * praznu listu (privremen pad sajta, timeout, promena strukture stranice...), njegovi stari
     * (još uvek verovatno tačni, samo blago zastareli) redovi ostaju netaknuti umesto da nestanu -
     * bez ovoga bi svaki prolazni hiccup jednog scrapera obrisao ceo repertoar tog bioskopa iz
     * aplikacije dok se scraper ponovo ne uspe, umesto da se samo vidi jučerašnji repertoar.
     */
    suspend fun insertScrapedMovies(moviesList: List<ScrapedMovie>) = dbQuery {
        val bioskopiZaZamenu = moviesList.map { it.cinemaName }.distinct()
        if (bioskopiZaZamenu.isEmpty()) {
            logger.info("[DATABASE] Nijedan scraper nije vratio nijedan film ovog ciklusa - baza se ne dira, stari podaci ostaju.")
            return@dbQuery
        }

        val obrisanoRedova = ScrapedMoviesTable.deleteWhere { ScrapedMoviesTable.cinemaName inList bioskopiZaZamenu }
        logger.info("[DATABASE] Obrisano starih zapisa za bioskope $bioskopiZaZamenu: $obrisanoRedova")

        // Jedan batch umesto ~500 pojedinačnih INSERT-a (uz reWriteBatchedInserts u JDBC URL-u
        // drajver ih spaja u višeredne INSERT naredbe).
        ScrapedMoviesTable.batchInsert(moviesList, shouldReturnGeneratedValues = false) { movie ->
            this[ScrapedMoviesTable.title] = movie.title
            this[ScrapedMoviesTable.originalTitle] = movie.originalTitle
            this[ScrapedMoviesTable.cinemaName] = movie.cinemaName
            this[ScrapedMoviesTable.cinemaLocation] = movie.cinemaLocation
            this[ScrapedMoviesTable.latitude] = movie.latitude
            this[ScrapedMoviesTable.longitude] = movie.longitude
            this[ScrapedMoviesTable.bookingUrl] = movie.bookingUrl
            this[ScrapedMoviesTable.isUpcoming] = movie.isUpcoming
            this[ScrapedMoviesTable.tmdbId] = movie.tmdbId
        }
        logger.info("[DATABASE] Skrapovani filmovi (${moviesList.size}) za ${bioskopiZaZamenu.size} bioskopa su uspešno sačuvani u Supabase!")
    }

    suspend fun isDatabaseEmpty(): Boolean = dbQuery {
        ScrapedMoviesTable.selectAll().limit(1).empty()
    }

    /**
     * Najnoviji `created_at` u tabeli (UTC wall-clock) - MAX(), ne proizvoljan prvi red: posle
     * per-bioskop zamene u tabeli istovremeno žive redovi različite starosti (bioskop koji je pao
     * zadržava jučerašnje), pa bi `limit(1)` bez sortiranja vraćao nasumično star datum.
     * Koristi ga i periodična provera za osvežavanje semantičkog indeksa (Application.kt).
     */
    suspend fun getLatestScrapeTimestamp(): LocalDateTime? = dbQuery {
        val maxCreatedAt = ScrapedMoviesTable.createdAt.max()
        ScrapedMoviesTable.select(maxCreatedAt).singleOrNull()?.get(maxCreatedAt)
    }

    suspend fun getLatestScrapeDate(): java.time.LocalDate? {
        // createdAt je sačuvan kao UTC wall-clock (clientDefault iznad). Prvo ga tumačimo kao
        // UTC Instant, pa ga prevodimo u beogradski kalendarski dan - ne uzimamo datumski deo
        // direktno iz UTC vrednosti (to je bio uzrok bug-a: dan bi se "prevrnuo" 1-2h kasnije
        // nego što je stvarno ponoć u Beogradu, pa bi se skrejpovanje ponovo pokretalo).
        val najnoviji = getLatestScrapeTimestamp() ?: return null
        return najnoviji.toInstant(TimeZone.UTC).toLocalDateTime(CINEMA_TIME_ZONE).date.toJavaLocalDate()
    }

    // 2. Čitanje svih filmova iz baze (ako ti zatrebaju da ih vratiš kroz rutu)
    suspend fun getAllScrapedMovies(): List<ScrapedMovie> = dbQuery {
        ScrapedMoviesTable.selectAll().map { row ->
            ScrapedMovie(
                title = row[ScrapedMoviesTable.title],
                originalTitle = row[ScrapedMoviesTable.originalTitle],
                cinemaName = row[ScrapedMoviesTable.cinemaName],
                cinemaLocation = row[ScrapedMoviesTable.cinemaLocation],
                latitude = row[ScrapedMoviesTable.latitude],
                longitude = row[ScrapedMoviesTable.longitude],
                bookingUrl = row[ScrapedMoviesTable.bookingUrl],
                isUpcoming = row[ScrapedMoviesTable.isUpcoming],
                tmdbId = row[ScrapedMoviesTable.tmdbId]
            )
        }
    }

    /**
     * Svi bioskopski termini za konkretan film (Movie Detail ekran - "Bioskopski termini"
     * sekcija). `tmdbId eq tmdbId` unutar select{} bloka - eq je SqlExpressionBuilder član, radi
     * preko implicitnog receiver-a, bez potrebe za importom (isti obrazac kao ostatak baze).
     */
    suspend fun getShowtimesForMovie(tmdbId: Int): List<ScrapedMovie> = dbQuery {
        ScrapedMoviesTable.selectAll().where { ScrapedMoviesTable.tmdbId eq tmdbId }.map { row ->
            ScrapedMovie(
                title = row[ScrapedMoviesTable.title],
                originalTitle = row[ScrapedMoviesTable.originalTitle],
                cinemaName = row[ScrapedMoviesTable.cinemaName],
                cinemaLocation = row[ScrapedMoviesTable.cinemaLocation],
                latitude = row[ScrapedMoviesTable.latitude],
                longitude = row[ScrapedMoviesTable.longitude],
                bookingUrl = row[ScrapedMoviesTable.bookingUrl],
                isUpcoming = row[ScrapedMoviesTable.isUpcoming],
                tmdbId = row[ScrapedMoviesTable.tmdbId]
            )
        }
    }
}