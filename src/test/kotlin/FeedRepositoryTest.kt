package com.milinko

import com.milinko.db.CommentsTable
import com.milinko.db.FeedRepository
import com.milinko.db.FeedType
import com.milinko.db.FriendshipsRepository
import com.milinko.db.FriendshipsTable
import com.milinko.db.MoviesTable
import com.milinko.db.ScrapedMoviesTable
import com.milinko.db.UserActionsTable
import com.milinko.db.UserDao
import com.milinko.db.UsersTable
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feed and invite queries on an in-memory database (H2 in PostgreSQL mode). */
class FeedRepositoryTest {

    private val tables = arrayOf(UsersTable, MoviesTable, UserActionsTable, CommentsTable, ScrapedMoviesTable, FriendshipsTable)

    @BeforeTest
    fun setUp() {
        Database.connect("jdbc:h2:mem:feed${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(*tables)
            listOf("me", "ana", "marko", "stranger").forEach { id ->
                UsersTable.insert { it[UsersTable.id] = id; it[firebaseUid] = "f-$id"; it[username] = id }
            }
            (1L..4L).forEach { id -> MoviesTable.insert { it[tmdbId] = id; it[title] = "Movie $id" } }
            fun action(user: String, movie: Long, type: String, day: Int, rating: Int? = null) = UserActionsTable.insert {
                it[userId] = user; it[movieId] = movie; it[actionType] = type; it[UserActionsTable.rating] = rating
                it[createdAt] = LocalDateTime(2026, 10, day, 12, 0)
            }
            // Watchlists: me {1, 2}, ana {2, 3}, marko {2}, stranger {1}.
            action("me", 1, "WATCHLIST", 1); action("me", 2, "WATCHLIST", 1)
            action("ana", 2, "WATCHLIST", 1); action("ana", 3, "WATCHLIST", 1)
            action("marko", 2, "WATCHLIST", 1); action("stranger", 1, "WATCHLIST", 1)
            // Activity: ana rated 4 (day 10), marko watched 3 (day 12), stranger rated 1 (day 13).
            action("ana", 4, "WATCHED", 10, rating = 9); action("marko", 3, "WATCHED", 12)
            action("stranger", 1, "WATCHED", 13, rating = 2)
            CommentsTable.insert { it[userId] = "ana"; it[movieId] = 4; it[body] = "Odličan!"; it[createdAt] = LocalDateTime(2026, 10, 11, 12, 0) }
            // Movie 2 plays in two locations (plus one upcoming row that must not count).
            listOf("A" to false, "B" to false, "C" to true).forEach { (loc, upcoming) ->
                ScrapedMoviesTable.insert {
                    it[title] = "Movie 2"; it[cinemaName] = "X"; it[cinemaLocation] = loc; it[bookingUrl] = "https://x"
                    it[isUpcoming] = upcoming; it[tmdbId] = 2
                }
            }
        }
    }

    @Test
    fun `friends feed is newest first, without strangers, and pages by time`() = runBlocking {
        val feed = FeedRepository().friendsFeed(listOf("ana", "marko"), before = null, limit = 10)
        assertEquals(listOf("marko", "ana", "ana"), feed.map { it.friend.id })
        assertEquals(listOf(FeedType.WATCHED, FeedType.COMMENTED, FeedType.RATED), feed.map { it.type })
        assertEquals("Odličan!", feed[1].text)

        val olderPage = FeedRepository().friendsFeed(listOf("ana", "marko"), before = feed[1].createdAt, limit = 10)
        assertEquals(listOf(FeedType.RATED), olderPage.map { it.type })
    }

    @Test
    fun `shared wishes only show movies on my own list`() = runBlocking {
        val wishes = FeedRepository().sharedWishes("me", listOf("ana", "marko"))
        assertEquals(listOf(2L), wishes.map { it.tmdbId })
        assertEquals(setOf("ana", "marko"), wishes.single().friends.map { it.id }.toSet())
        assertEquals(2, wishes.single().cinemaCount)
        // Movie 3 is only on ana's list: never revealed.
        assertFalse(wishes.any { it.tmdbId == 3L })
    }

    @Test
    fun `an invite code is created once, can be reset, and makes friends`() = runBlocking {
        val users = UserDao()
        val code = assertNotNull(users.getOrCreateInviteCode("ana"))
        assertEquals(code, users.getOrCreateInviteCode("ana"))
        assertEquals("ana", users.findByInviteCode(code)?.id)

        val newCode = assertNotNull(users.resetInviteCode("ana"))
        assertNotEquals(code, newCode)
        assertNull(users.findByInviteCode(code))

        val friendships = FriendshipsRepository()
        assertTrue(friendships.acceptInvite("ana", "me"))
        assertFalse(friendships.acceptInvite("ana", "me"))
        assertEquals(listOf("ana"), friendships.getFriendIds("me"))
    }
}
