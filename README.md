# Movienee Backend (Ktor)

Kotlin/Ktor backend service powering the Movienee Android application. It aggregates movie
metadata from third-party APIs, runs concurrent web scrapers against regional cinema chains,
provides AI-driven visual and semantic search over the current cinema repertoire, and hosts the
social/personalization layer (accounts, watchlist/history, friends, comments, taste-based
recommendations) behind a stateless JWT session.

Repository: `mmileticc/MovieLensBackendKtor`

---

## 1. Overview & Core Purpose

Movienee's client is a movie-discovery and social-tracking Android app. This backend exists to:

- Proxy and cache third-party movie data (TMDB, OMDb) so the client never talks to those APIs
  directly, and so the app is not rate-limited by end-user traffic.
- Convert unstructured input (a photo of a poster/screen, a free-text mood/plot description) into
  a concrete movie result, using Google Gemini's multimodal and embedding models.
- Maintain a locally-scraped, near-real-time view of what is actually showing in Serbian cinemas
  (7 chains, national coverage), since no public API exposes this data.
- Own the social graph and personalization logic (friends, watch history, genre-affinity
  recommendations, "Movie Night" suggestions, per-movie comments) against a Postgres database.
- Issue and validate a backend-controlled session (JWT) derived from Firebase/Google
  authentication, decoupling the app's authorization model from Firebase's own token lifecycle.

Core engineering goals:

- **Stateless horizontal scalability** — authentication is a signed JWT (no server-side session
  store); the only shared state is Postgres.
- **Resilience to slow/unreliable upstreams** — every external call (TMDB, Gemini, OMDb, cinema
  websites) is isolated with timeouts, retries, and graceful degradation so that one failing
  dependency never takes down an unrelated feature.
- **Low latency on hot paths** — layered TTL caching (in-memory) and a DB-backed embedding cache
  avoid redundant upstream calls (TMDB, Gemini) on every request/restart.
- **Concurrency-first I/O** — cinema scraping and multi-candidate TMDB resolution both run
  parallel coroutines rather than sequential loops.
- **Schema-as-code** — the Postgres schema is derived directly from Exposed table objects
  (`SchemaUtils.createMissingTablesAndColumns`), keeping code and database in sync without a
  separate migration DSL.

---

## 2. Architecture & Data Flow

### Startup sequence (`Application.module()`, `Application.kt`)

1. Initialize Firebase Admin SDK (non-fatal if it fails — Firebase-dependent routes will error,
   the rest of the app still boots).
2. Install Ktor plugins: `ContentNegotiation` (kotlinx.serialization JSON), `StatusPages` (maps
   uncaught exceptions to a uniform `{"error": "..."}` JSON body), `CORS` (open by default,
   restrictable via `ALLOWED_ORIGINS`), `Authentication` (HMAC256 JWT verifier).
3. `DatabaseFactory.init()` — opens the Postgres connection (direct or via Supabase's pgBouncer
   pooler, detected by hostname) and reconciles the schema against all Exposed `Table` objects.
4. Construct service singletons: `GeminiService`, `TmdbService`, `OmdbService`, two
   `CinemaSemanticIndex` instances (one per embedding provider — Gemini and local MiniLM).
5. Fire a background TMDB "warm-up" call so the first real user request doesn't pay the cold-start
   TLS/DNS latency (`warmUpTmdbClient`).
6. Conditionally start the scraping lifecycle (`SCRAPING_BOOLEAN=true`) or, if scraping is
   disabled on this host, just build the semantic search index from whatever is already in the
   `scraped_movies` table (`configureSemanticIndexRefresh`) — this lets scraping run on one host
   (e.g. a local machine or worker) while the public web service (e.g. Render free tier) only
   serves reads.
7. Register all route modules: `configureRouting`, `configureMovieRouting`,
   `configureUserActionsRouting`, `configureFriendsRouting`, `configureCommentsRouting`.

### Request-time data flow examples

**Visual search (`POST /chat/vision`, JWT-protected):** client uploads a multipart JPEG/PNG →
image is validated (size/MIME) → sent to `GeminiService.detectMoviesFromImage` (multimodal model,
strict-JSON prompt, 25s timeout) → the top 3 returned candidate titles are resolved **in parallel**
(`coroutineScope { ... async { } .awaitAll() }`) against TMDB search → each match is cross-checked
against the in-memory `scraped_movies` snapshot to attach current showtimes → a combined
`VisionSearchResponse` (candidates × TMDB matches × cinema availability) is returned.

**Semantic search (`GET /api/movies/now-in-cinemas/semantic-search`):** query text is embedded
with the same provider (`gemini` or `local`) that built the in-memory `CinemaSemanticIndex`, then
compared via cosine similarity against every embedded movie currently on the repertoire (small
working set — dozens of films — so no vector DB is needed). The index itself is rebuilt on
startup, and after every scraping cycle, from the current `scraped_movies` rows; embeddings are
reused from the `movie_embeddings` table when the underlying title+overview text is unchanged
(SHA-256 content hash), avoiding repeat Gemini billing on every restart.

**Scraping cycle (`configureScrapingLifecycle`):** on boot, checks whether `scraped_movies` was
already refreshed *today* (Belgrade calendar day, not UTC). If stale or empty, all 7
`CinemaScraper` implementations run concurrently (`ScrapingEngineConcurrent`, one coroutine per
cinema chain on `Dispatchers.IO`), results are enriched with TMDB IDs (title-matched, rate-limited
with a manual delay), and persisted **per-cinema** — only chains that returned results this cycle
have their old rows replaced, so a single chain's website hiccup doesn't wipe its previously-good
data. The semantic index is rebuilt from the fresh data afterward.

**Personalized `/api/movies/featured`:** if a `userId` is supplied and that user has rated at
least 3 movies, their top-3 genre affinities (`TasteProfileRepository`) are used to issue 3
separate TMDB "discover by genre" queries, with slot counts proportional to affinity strength
(stronger-liked genres get more of the 20-item page) — rather than a single OR query weighted
purely by TMDB's own popularity ranking.

### Module interaction

```
Android client
     │  HTTPS (JSON / multipart)
     ▼
Ktor routing layer  (Routing.kt, MovieRouting.kt, UserActionsRouting.kt,
                      FriendsRouting.kt, CommentsRouting.kt)
     │
     ├── services/ (GeminiService, TmdbService, OmdbService, TextEmbedder,
     │              CinemaSemanticIndex)  ──► external HTTP APIs
     │
     ├── scraping/ (ScrapingEngineConcurrent + 7 CinemaScraper impls)
     │              ──► Playwright-driven headless browser sessions
     │
     └── db/ (Exposed repositories)  ──► PostgreSQL (Supabase)
```

---

## 3. Key Features & Technical Implementation

| Feature | Mechanism | Key files |
|---|---|---|
| Firebase → backend session exchange | Client authenticates with Firebase (Google Sign-In); server verifies the Firebase ID token via Firebase Admin SDK, upserts the user, and issues a self-signed HMAC256 JWT (24h expiry, `subject` = internal user id) that the client uses for every subsequent call. | `Routing.kt` (`POST /api/auth/firebase`), `db/UsersTable.kt` |
| Stateless JWT authorization | Ktor `Authentication`/`jwt()` plugin validates signature + non-empty subject only (no per-request Firebase re-check — a documented, deliberate trade-off). Ownership checks (`ownerUserIdOrNull()`) additionally verify the JWT subject matches the `{userId}` path segment on every personal-data route. | `Application.kt`, `UserActionsRouting.kt`, `FriendsRouting.kt` |
| Movie catalog / search / details | Thin proxy over TMDB's REST API with per-endpoint in-memory TTL caches (search 10m, details/person 1h, popular 2h, genre-discover 1h) and automatic retry-once on cold-start timeouts. | `services/TmdbService.kt` |
| Visual (photo) movie recognition | Gemini multimodal model returns up to 3 ranked candidate titles as strict JSON; candidates are resolved against TMDB **in parallel** and cross-referenced with the live cinema repertoire. | `services/GeminiService.kt`, `Routing.kt` |
| Semantic natural-language search | Movie title+overview text is embedded (Gemini `gemini-embedding-001` or a local ONNX MiniLM model) once per content-hash and cached in Postgres; queries are embedded with the same provider and ranked by cosine similarity over an in-memory index scoped to the current repertoire only. | `services/CinemaSemanticIndex.kt`, `services/TextEmbedder.kt`, `db/MovieEmbeddingsTable.kt` |
| Concurrent cinema scraping | 7 independent `CinemaScraper` implementations (Playwright + Jsoup) run as parallel coroutines; results replace only the cinemas that responded this cycle; freshness is checked against the Belgrade calendar day. | `scraping/ScrapingEngineConcurrent.kt`, `scraping/scraper/*.kt` |
| Personalized recommendations | Per-genre average rating is penalized by uncertainty (`rating − 1.5/√count`, a lower-confidence-bound-style formula) so genres with few ratings don't outrank well-established preferences; used both for a user's own "taste profile" and for blending TMDB discover results into `/featured`. | `db/TasteProfileRepository.kt` |
| Social graph | Friend requests with `PENDING/ACCEPTED/REJECTED` states, incoming/outgoing pending lists, friend-only activity feed (watched movies + ratings only — watchlists stay private), and friend-profile previews that reveal only genre overlap before a request is accepted. | `db/FriendshipsRepository.kt`, `FriendsRouting.kt` |
| "Movie Night" suggestions | Compares two users' taste profiles (`compareTasteProfiles`), takes the shared genres both users have a real (penalized) affinity for, and issues a TMDB discover query on that intersection, excluding anything either user has already watched. | `db/TasteProfileRepository.kt`, `FriendsRouting.kt` |
| Public comments | Threaded-free, flat comment list per movie with like/unlike toggling and friend/own-comment flags for client-side styling; movies are lazily cached (`ensureCached`) on first comment so a comment can exist before the movie has been viewed in detail. | `db/CommentsRepository.kt`, `CommentsRouting.kt` |
| Supplementary ratings | Optional OMDb integration adds a genuine IMDb rating and Rotten Tomatoes score (TMDB's own "IMDb rating" field is actually its own aggregate) — fully optional, degrades silently to `null` if `OMDB_API_KEY` is unset or the lookup fails. | `services/OmdbService.kt` |
| Location-aware showtimes | Haversine great-circle distance is computed server-side between the user's coordinates and each scraped cinema location, and showtimes are sorted nearest-first. | `MovieRouting.kt` |
| Resilient history updates | `PUT /history/{tmdbId}` is a self-healing upsert: if the row doesn't exist yet (client/server state drift), it transparently falls back to insert semantics instead of returning 404. | `UserActionsRouting.kt` |

---

## 4. Tech Stack Reference

| Category | Technology | Exact Usage in This Project |
|---|---|---|
| Language | Kotlin (JVM toolchain 21) | Entire codebase; `kotlin("jvm")` + `kotlin("plugin.serialization")` Gradle plugins |
| Web framework | Ktor 3.4.2 (Netty engine) | HTTP server, routing DSL, plugin pipeline (`ContentNegotiation`, `StatusPages`, `CORS`, `Authentication`) |
| Serialization | kotlinx.serialization-json | Request/response DTOs across all routing files |
| Database | PostgreSQL (Supabase-hosted) | Primary datastore; connected via pgBouncer pooler (port 6543) or direct (5432) depending on `DB_HOST` |
| ORM / DB access | JetBrains Exposed 0.41.1 (core, dao, jdbc, kotlin-datetime) | All `*Table.kt` schema objects + repository query/insert/update DSL |
| DB driver | PostgreSQL JDBC driver 42.7.11 | Underlying `Database.connect(...)` driver |
| Authentication (identity) | Firebase Admin SDK 9.4.2 | Verifies Google Sign-In ID tokens server-side |
| Authentication (session) | auth0 java-jwt 4.4.0 + Ktor `ktor-server-auth-jwt` | Issues/validates the app's own HMAC256-signed 24h session JWT |
| AI — vision & embeddings | Google Gemini (`google-genai` SDK 0.1.0 + raw REST for `embedContent`) | Movie recognition from photos (multimodal), text embeddings for semantic search |
| AI — local embeddings (offline alt.) | langchain4j `all-MiniLM-L6-v2` (ONNX runtime, weights bundled in-jar) | Drop-in alternative embedding provider, no network call, gated behind `ENABLE_LOCAL_EMBEDDING` |
| External API — catalog | TMDB (The Movie Database) REST API | Search, details, cast, genres, discover-by-genre, person/actor data |
| External API — supplementary ratings | OMDb API | IMDb rating + Rotten Tomatoes score enrichment (optional) |
| Web scraping | Microsoft Playwright 1.42.0 (headless Chromium/WebKit/Firefox) + Jsoup 1.17.2 | 7 cinema-chain-specific scrapers reading live showtimes |
| Concurrency | Kotlin Coroutines 1.8.1 | Parallel scraping (`async`/`awaitAll`), parallel TMDB candidate resolution, background startup tasks |
| Caching | Custom `TtlCache<K,V>` (ConcurrentHashMap-based) | Per-endpoint TMDB/OMDb response caching with configurable TTL |
| HTTP client | Ktor Client (CIO engine) | Outbound calls to TMDB, Gemini, OMDb |
| Logging | Logback | Structured console logging (`logback.xml`) |
| Config | YAML (`ktor-server-config-yaml` + kaml) | `application.yaml`, with environment-variable overrides for all secrets |
| Containerization | Docker (multi-stage: Temurin 21 JDK build → Temurin 21 JRE runtime) | Production image, includes Playwright's native browser dependencies |
| Local orchestration | Docker Compose | Local Postgres 16 + app container for development parity with production |
| Build tool | Gradle (Kotlin DSL), Gradle Wrapper | `build.gradle.kts`, `gradlew` |

---

## 5. Project & Directory Structure

```
MovieLensBackendKtor/
├── src/main/kotlin/
│   ├── Application.kt              # Entry point; plugin install, DI wiring, startup lifecycle
│   ├── Routing.kt                  # Health check, Firebase auth exchange, POST /chat/vision
│   ├── MovieRouting.kt             # Public movie/person browsing (search, trending, featured,
│   │                                # now-in-cinemas, semantic search, movie detail, friends-watched)
│   ├── UserActionsRouting.kt       # Own-profile, watchlist, history, taste-profile (JWT + ownership-checked)
│   ├── FriendsRouting.kt           # User search, friend requests, activity feed, Movie Night suggestions
│   ├── CommentsRouting.kt          # Public per-movie comments + like toggling
│   ├── auth/
│   │   └── AuthDtos.kt             # Firebase auth request/response DTOs
│   ├── db/                         # Exposed table objects + repositories (one file per concern)
│   │   ├── DatabaseFactory.kt      # Connection setup + schema reconciliation
│   │   ├── FirebaseAdminInitializer.kt
│   │   ├── UsersTable.kt           # + UserDao (profile CRUD, username sanitation/uniqueness)
│   │   ├── MoviesTable.kt          # + MoviesRepository (upsert-on-first-interaction TMDB cache)
│   │   ├── UserActionsTable.kt     # + UserActionsRepository (watchlist/watched, unified table)
│   │   ├── FriendshipsTable.kt     # + FriendshipsRepository (request lifecycle)
│   │   ├── TasteProfileRepository.kt  # Genre-affinity ranking + friend comparison logic
│   │   ├── CommentsTable.kt / CommentLikesTable.kt  # + CommentsRepository
│   │   ├── ScrapedMoviesTable.kt   # + ScrapedMoviesRepository (raw scraped showtimes)
│   │   ├── MovieEmbeddingsTable.kt # + MovieEmbeddingsRepository (persistent embedding cache)
│   │   └── NotificationsTable.kt
│   ├── services/
│   │   ├── GeminiService.kt        # Vision candidate detection + text embedding calls
│   │   ├── TmdbService.kt          # TMDB proxy with TTL caching + cold-start retry
│   │   ├── OmdbService.kt          # Optional IMDb/RT ratings enrichment
│   │   ├── TextEmbedder.kt         # Gemini vs. local MiniLM embedding provider abstraction
│   │   └── CinemaSemanticIndex.kt  # In-memory embedding index + cosine-similarity search
│   ├── scraping/
│   │   ├── ScrapingEngineConcurrent.kt   # Runs all scrapers in parallel coroutines
│   │   ├── model/ScrapedMovie.kt
│   │   ├── locations/CinemaLocations.kt  # Static cinema geo-coordinates
│   │   └── scraper/
│   │       ├── _CinemaScraper.kt   # Common interface implemented by every scraper
│   │       ├── MtsDvoranaScraper.kt
│   │       ├── CineplexxScraper.kt
│   │       ├── ArenaCineplexScraper.kt
│   │       ├── CineGrandScraper.kt
│   │       ├── CineStarScraper.kt
│   │       ├── RodaCineplexScraper.kt
│   │       └── TuckwoodCineplexScraper.kt
│   └── util/
│       ├── TtlCache.kt             # Generic in-memory TTL cache
│       └── AppZone.kt              # Shared Belgrade timezone constant
├── src/main/resources/
│   ├── application.yaml            # Ktor module wiring + env-var-backed config
│   └── logback.xml
├── Dockerfile                      # Multi-stage build (Gradle build → slim JRE runtime image)
├── docker-compose.yml              # Local Postgres 16 + app, for development parity
└── build.gradle.kts                # Dependencies and Kotlin/Ktor plugin configuration
```

---

## 6. Configuration, API & Execution

### Required environment variables

| Variable | Required | Purpose |
|---|---|---|
| `APP_JWT_SIGNING_KEY` | Yes | HMAC256 secret used to sign/verify the backend session JWT |
| `GEMINI_API_KEY` | Yes | Google Gemini API key (vision detection + embeddings) |
| `TMDB_API_KEY` | Yes | TMDB v3 API key or v4 Bearer access token (auto-detected) |
| `DB_HOST` | Yes | Postgres host (Supabase project host or local) |
| `DB_PORT` | No (default `6543`) | `6543` for Supabase pooler, `5432` for direct connection |
| `DB_NAME` | No (default `postgres`) | Database name |
| `DB_USER` | No (default `postgres`) | Database user |
| `DB_PASSWORD` | Yes | Database password |
| `SCRAPING_BOOLEAN` | Yes | `"true"` to run the cinema scrapers on this instance; `"false"` to only serve reads and refresh the semantic index from existing data |
| `OMDB_API_KEY` | No | Enables IMDb/Rotten Tomatoes enrichment; ratings silently omitted if unset |
| `ALLOWED_ORIGINS` | No | Comma-separated origin allowlist for CORS; defaults to `anyHost()` |
| `ENABLE_LOCAL_EMBEDDING` | No | `"true"` to also populate the local MiniLM semantic index alongside Gemini's |
| `SEMANTIC_INDEX_POLL_MINUTES` | No (default `60`) | With `SCRAPING_BOOLEAN=false`: how often to check `scraped_movies` for new data and rebuild the semantic index |

### Key API endpoints

**Public (no authentication):**

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Health check |
| POST | `/api/auth/firebase` | Exchange a Firebase ID token for a backend session JWT |
| GET | `/api/movies/search?query=` | Title search (TMDB proxy) |
| GET | `/api/movies/trending?page=` | Popular movies |
| GET | `/api/movies/featured?userId=&page=` | Personalized or generic recommendations |
| GET | `/api/movies/genres` | All TMDB movie genres (id → name), cached 24h |
| GET | `/api/movies/now-in-cinemas` | Full scraped repertoire with genres, overview, runtime and now/upcoming flag |
| GET | `/api/movies/now-in-cinemas/semantic-search?q=&engine=` | Natural-language repertoire search |
| GET | `/api/movies/{tmdbId}?lat=&lon=` | Movie detail, showtimes, IMDb/RT ratings, IMDb ID, YouTube trailers, backdrop images |
| GET | `/api/people/{id}` | Actor/person detail + filmography |

**Protected (`Authorization: Bearer <jwt>`):**

| Method | Path | Purpose |
|---|---|---|
| POST | `/chat/vision` | Multipart image upload → recognized movie candidates |
| GET | `/api/movies/{tmdbId}/friends-watched` | Friends who've watched this movie |
| GET / PUT | `/api/users/{userId}` / `/profile` | Own profile read/update |
| GET / POST | `/api/users/{userId}/watchlist` | Watchlist read/add |
| DELETE | `/api/users/{userId}/watchlist/{tmdbId}` | Remove from watchlist |
| GET / POST | `/api/users/{userId}/history` | Watch history read/add |
| PUT / DELETE | `/api/users/{userId}/history/{tmdbId}` | Update rating/comment, or undo |
| GET | `/api/users/{userId}/taste-profile` | Genre-affinity breakdown |
| GET | `/api/users/search?q=` | Find users to friend |
| GET | `/api/users/{userId}/friends` | Friends list with match score |
| GET | `/api/users/{userId}/friends/pending` / `/pending/sent` | Incoming/outgoing requests |
| GET | `/api/users/{userId}/friends/activity` | Friend activity feed |
| GET | `/api/users/{userId}/friends/{friendId}/profile` | Friend profile (full or limited preview) |
| GET | `/api/users/{userId}/friends/{friendId}/movienight-suggestions?page=` | Joint recommendations |
| POST / DELETE | `/api/users/{userId}/friends/{friendId}` | Send/accept/reject, or remove |
| GET / POST | `/api/movies/{tmdbId}/comments` | Read/post comments |
| DELETE | `/api/comments/{id}` | Delete own comment |
| POST | `/api/comments/{id}/like` | Toggle like |

### Local setup

```bash
# 1. Clone and enter the repo
git clone https://github.com/mmileticc/MovieLensBackendKtor.git
cd MovieLensBackendKtor

# 2. Set required environment variables (PowerShell example)
$env:APP_JWT_SIGNING_KEY = "some-long-random-secret"
$env:GEMINI_API_KEY = "..."
$env:TMDB_API_KEY = "..."
$env:DB_HOST = "..."; $env:DB_PASSWORD = "..."
$env:SCRAPING_BOOLEAN = "false"   # set "true" to also run scrapers locally

# 3. Run
./gradlew.bat run
```

The server starts on `http://localhost:8080`.

### Daily scraping (GitHub Actions)

The Render web service runs with `SCRAPING_BOOLEAN=false` (the free tier cannot run
Playwright). Scraping runs as a standalone job instead — `scraping/ScraperJob.kt`, started once
a day by `.github/workflows/daily-scrape.yml` (04:00 UTC, also runnable manually from the
Actions tab):

1. All 7 scrapers run in parallel, results are enriched with TMDB IDs and written to
   `scraped_movies` (per-cinema replacement, same as the in-process lifecycle).
2. The run fails (red ✗, GitHub e-mail) if any cinema returned 0 movies — the usual sign that a
   site changed its markup. The other cinemas are still written.
3. A per-cinema table is written to the run's Summary tab.

The web service picks up the new data by itself: it polls `MAX(created_at)` every
`SEMANTIC_INDEX_POLL_MINUTES` (default 60) and rebuilds the semantic index when it changes.
Repertoire/showtime endpoints read the table directly, so they see new data immediately.

After every run (success or failure) an e-mail with the status and the per-cinema table is sent
via Gmail SMTP.

Required repository secrets: `DB_HOST`, `DB_PASSWORD`, `TMDB_API_KEY`, `MAIL_USERNAME`,
`MAIL_PASSWORD` (Gmail App Password), `MAIL_TO` (optional: `DB_PORT`, `DB_NAME`, `DB_USER`).
Run locally with `./gradlew runScraperJob`.

### Docker / production

```bash
# Local full stack (Postgres + app) via Compose:
docker compose up --build

# Or build the production image directly:
docker build -t movienee-backend .
docker run -p 8080:8080 --env-file .env movienee-backend
```

The Docker image is a two-stage build: a JDK 21 stage compiles the Gradle project, and the
resulting JAR is copied into a slim JRE 21 runtime image that also carries Playwright's native
browser dependencies (required for the cinema scrapers to run inside the container).
