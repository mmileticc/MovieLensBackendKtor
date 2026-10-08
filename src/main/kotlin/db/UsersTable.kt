package com.milinko.db

import com.milinko.db.DatabaseFactory.dbQuery
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update
import java.util.*

/**
 * Users table za Firebase Auth integraciju.
 *
 * NAPOMENA: stvarna Supabase tabela ima `id` kolonu tipa `text`, ne `uuid` (verovatno da bi
 * ostalo prenosivo/jednostavno). Ranija verzija ovog fajla je pogrešno deklarisala `id` kao
 * `uuid()`, što se "slučajno" nije lomilo jer su generisane vrednosti uvek bile validan UUID
 * string, ali je bio type mismatch između koda i baze. Ovde generišemo isti format (random UUID)
 * samo kao String, na varchar koloni, da se poklapa sa stvarnom šemom.
 */
object UsersTable : Table("users") {
    val id = varchar("id", 64).clientDefault { UUID.randomUUID().toString() }
    val firebaseUid = varchar("firebase_uid", 255).uniqueIndex("idx_users_firebase_uid")
    val username = varchar("username", 255).nullable()
    val email = varchar("email", 255).nullable()
    val avatarUrl = text("avatar_url").nullable()
    val bio = text("bio").nullable()
    val subscriptionTier = varchar("subscription_tier", 50).default("free")
    // Stvarna kolona je jsonb DEFAULT '[]' u Supabase-u; modelovano kao text dok se ne uvede
    // jsonb podrška (exposed-json) i dok ne postoji stvarna funkcionalnost koja je koristi.
    val roles = text("roles").nullable().default("[]")
    val createdAt = datetime("created_at").clientDefault {
        Clock.System.now().toLocalDateTime(TimeZone.UTC)
    }
    val lastLogin = datetime("last_login").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        // Indeksi već postoje u bazi; mapirani ovde da ih Exposed ne prijavljuje kao nepoznate.
        index("idx_users_email", false, email)
        index("idx_users_created_at", false, createdAt)
        index("idx_users_last_login", false, lastLogin)
    }
}

/**
 * User DTO za API responses
 */
@Serializable
data class UserDto(
    val id: String,
    val firebaseUid: String,
    val username: String?,
    val email: String?,
    val avatarUrl: String?
)

/**
 * User entity klasa
 */
data class User(
    val id: String,
    val firebaseUid: String,
    val username: String?,
    val email: String?,
    val avatarUrl: String?,
    val bio: String? = null,
    val subscriptionTier: String = "free",
    val roles: String? = null,
    val createdAt: kotlinx.datetime.LocalDateTime,
    val lastLogin: kotlinx.datetime.LocalDateTime?
)

/** Instagram-stil pravilo za username: slova/brojevi/tačka/donja crta, 3-30 karaktera. */
private val USERNAME_REGEX = Regex("^[A-Za-z0-9_.]{3,30}$")
private const val BIO_MAX_LENGTH = 160

/**
 * Pretvara "sirovo" ime (npr. Google display name "Miloš Miletić") u default username validan po
 * USERNAME_REGEX-u: mala slova, razmaci -> donja crta, srpska latinična slova bez dijakritike
 * (č/ć/š/ž se skidaju preko NFD dekompozicije, đ ručno jer se NFD ne dekomponuje na d + kvačicu).
 * Ranije je default username bio sirovo Google ime SA razmakom - svaka izmena profila (čak i bez
 * dodira na username polje) je padala na regex proveru u `updateProfile` jer je stari, nevažeći
 * default ostajao pre-popunjen. Popravljeno tako da default VEĆ bude validan od trenutka kreiranja
 * naloga (videti poziv u `upsertByFirebaseUid`).
 */
private fun sanitizeUsername(raw: String): String {
    var s = raw.trim()
        .replace("đ", "dj").replace("Đ", "Dj")
    s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    s = s.lowercase()
        .replace(Regex("\\s+"), "_")
        .replace(Regex("[^a-z0-9_.]"), "")
        .replace(Regex("_{2,}"), "_")
        .trim('_', '.')
    if (s.length > 30) s = s.take(30).trimEnd('_', '.')
    return s
}

// Android cilja downsample+kompresiju na ~150KB pre base64 enkodiranja (videti AvatarImage.kt) -
// base64 dodaje ~33% veličine, pa ~250KB teksta ostavlja veliku sigurnosnu marginu (avatar_url je
// `text` kolona, bez ograničenja dužine na nivou baze).
private const val AVATAR_MAX_BASE64_LENGTH = 400_000

private fun ResultRow.toUser() = User(
    id = this[UsersTable.id],
    firebaseUid = this[UsersTable.firebaseUid],
    username = this[UsersTable.username],
    email = this[UsersTable.email],
    avatarUrl = this[UsersTable.avatarUrl],
    bio = this[UsersTable.bio],
    subscriptionTier = this[UsersTable.subscriptionTier],
    roles = this[UsersTable.roles],
    createdAt = this[UsersTable.createdAt],
    lastLogin = this[UsersTable.lastLogin]
)

/**
 * UserDao - Data Access Object za User tabelu
 */
class UserDao {

    /**
     * Upsert user po Firebase UID
     * - Ako korisnik postoji, ažurira email, avatar_url i last_login (username se NE dira, videti
     *   napomenu ispod)
     * - Ako ne postoji, kreira novog korisnika
     */
    suspend fun upsertByFirebaseUid(
        firebaseUid: String,
        email: String?,
        username: String?,
        avatarUrl: String?
    ): User = dbQuery {
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        // Prvo proverim da li korisnik postoji
        val existingUser = UsersTable.select { UsersTable.firebaseUid eq firebaseUid }
            .firstOrNull()

        if (existingUser != null) {
            // Update postojećeg korisnika - username se NAMERNO ne dira ovde (videti stariji
            // komentar niže o istom bagu).
            //
            // Bugfix (25. avgust 2026, korisnička žalba "nova profilna se prikaže ali kasnije više
            // ne, i kod prijatelja se ne vide postavljene slike nego samo difoltne") - avatarUrl je
            // imao IDENTIČAN bag kao username pre popravke #107: prepisivao se Google/Firebase
            // avatarom na SVAKOM login-u (redak niže je bio `stmt[UsersTable.avatarUrl] = avatarUrl`),
            // što je brisalo ručno postavljenu sliku (UserDao.updateProfile) čim bi se korisnik
            // ponovo ulogovao (token refresh, restart aplikacije...) - baš zato se novi avatar
            // video odmah po podešavanju, a "kasnije više ne". Isti mehanizam je pogađao SVAKOG
            // korisnika, ne samo trenutnog - zato su i kod prijatelja u listi umesto ručno
            // postavljene slike ispadale difoltne (Google/Firebase) slike, jer se friendUser.avatarUrl
            // u bazi vraćao na default čim bi se TAJ prijatelj sledeći put ulogovao. Sad se, kao i
            // username, avatar NE dira na update-u - samo email/last_login i dalje prate najsvežije
            // podatke sa Google naloga.
            UsersTable.update({ UsersTable.firebaseUid eq firebaseUid }) { stmt ->
                stmt[UsersTable.email] = email
                stmt[UsersTable.lastLogin] = now
            }
        } else {
            // Insert novog korisnika - default username je sanitizovano Google display ime ("ime_prezime",
            // vidi sanitizeUsername), fallback na generisano "user_xxxxxxxx" ako Google ne vrati ime ili
            // sanitizacija ostavi manje od 3 karaktera. Od ovog trenutka je ovo korisnikov username dok
            // ga sam ne promeni (videti UserDao.updateProfile) - mora odmah biti validan po
            // USERNAME_REGEX-u, inače prva sledeća izmena profila (i bez dodira na username polje) pada.
            var finalUsername = username?.let { sanitizeUsername(it) }?.takeIf { it.length >= 3 }
                ?: "user_${firebaseUid.take(8).lowercase()}"

            // Retko: dva korisnika sa istim imenom bi se sanitizovala u isti default username -
            // dodaj kratak sufiks iz firebaseUid-a da ostane jedinstven (isti dogovor o jedinstvenosti
            // kao u updateProfile, samo primenjen i na trenutak kreiranja naloga).
            val taken = UsersTable.select { UsersTable.username.lowerCase() eq finalUsername.lowercase() }
                .limit(1).any()
            if (taken) {
                finalUsername = "${finalUsername.take(24)}_${firebaseUid.take(4).lowercase()}"
            }

            UsersTable.insert {
                it[UsersTable.firebaseUid] = firebaseUid
                it[UsersTable.email] = email
                it[UsersTable.username] = finalUsername
                it[UsersTable.avatarUrl] = avatarUrl
                it[UsersTable.lastLogin] = now
            }
        }

        // Čitaj upsertovani red
        UsersTable.select { UsersTable.firebaseUid eq firebaseUid }
            .map { it.toUser() }
            .firstOrNull() ?: throw Exception("User nije pronađen nakon upserta")
    }

    /**
     * Pronađi korisnika po Firebase UID
     */
    suspend fun findByFirebaseUid(firebaseUid: String): User? = dbQuery {
        UsersTable.select { UsersTable.firebaseUid eq firebaseUid }
            .map { it.toUser() }
            .firstOrNull()
    }

    /**
     * Pronađi korisnika po ID-u (text, ne uuid — vidi napomenu uz UsersTable)
     */
    suspend fun findById(userId: String): User? = dbQuery {
        UsersTable.select { UsersTable.id eq userId }
            .map { it.toUser() }
            .firstOrNull()
    }

    /**
     * Pretraga korisnika za dodavanje prijatelja (case-insensitive), isključuje samog sebe.
     * Username: delimično podudaranje (search-as-you-type). Email: SAMO tačno podudaranje, i samo
     * kad upit liči na email - delimična pretraga po emailu bi omogućila da se upitom "@gmail"
     * izlistaju tuđe adrese.
     */
    suspend fun searchUsers(query: String, excludeUserId: String, limit: Int = 20): List<User> = dbQuery {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return@dbQuery emptyList()

        val matchesQuery = if ('@' in trimmed) {
            UsersTable.email.lowerCase() eq trimmed
        } else {
            UsersTable.username.lowerCase() like "%$trimmed%"
        }
        UsersTable.select { (UsersTable.id neq excludeUserId) and matchesQuery }
            .limit(limit)
            .map { it.toUser() }
    }

    /**
     * Ažurira username (jedinstven među korisnicima, case-insensitive - kao Instagram handle),
     * bio ("mini status" ispod imena) i opciono avatar (base64 `data:image/...` URI - Android
     * kompresuje/downsample-uje sliku pre slanja, videti AvatarImage.kt). `avatar == null` znači
     * "ne diraj postojeću sliku" (korisnik menja samo username/bio, nije birao novu sliku) - kolona
     * se piše samo kad je stvarno prosleđena nova vrednost. Baca `IllegalArgumentException` za sve
     * validacione greške (dužina/karakteri/zauzeto ime/prevelika slika) - rute ga hvataju eksplicitno
     * i vraćaju 400 sa porukom (isti obrazac kao `FriendshipsRepository.sendRequest`).
     */
    suspend fun updateProfile(userId: String, username: String, bio: String?, avatar: String?): User = dbQuery {
        val trimmedUsername = username.trim()
        if (!USERNAME_REGEX.matches(trimmedUsername)) {
            throw IllegalArgumentException(
                "Korisničko ime mora imati 3-30 karaktera i sme sadržati samo slova, brojeve, tačku i donju crtu"
            )
        }
        val trimmedBio = bio?.trim()?.take(BIO_MAX_LENGTH)?.ifBlank { null }
        if (avatar != null && avatar.length > AVATAR_MAX_BASE64_LENGTH) {
            throw IllegalArgumentException("Slika je prevelika - probaj sa manjom/kompresovanijom slikom")
        }

        val taken = UsersTable.select {
            (UsersTable.id neq userId) and (UsersTable.username.lowerCase() eq trimmedUsername.lowercase())
        }.limit(1).any()
        if (taken) {
            throw IllegalArgumentException("Korisničko ime \"$trimmedUsername\" je već zauzeto")
        }

        UsersTable.update({ UsersTable.id eq userId }) { stmt ->
            stmt[UsersTable.username] = trimmedUsername
            stmt[UsersTable.bio] = trimmedBio
            if (avatar != null) {
                stmt[UsersTable.avatarUrl] = avatar
            }
        }

        UsersTable.select { UsersTable.id eq userId }
            .map { it.toUser() }
            .firstOrNull() ?: throw IllegalStateException("Korisnik nije pronađen nakon ažuriranja")
    }
}





