val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("io.ktor.plugin") version "3.4.2"
}

group = "com.milinko"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

// Samostalni dnevni scraping job (bez Ktor servera) - videti scraping/ScraperJob.kt i
// .github/workflows/daily-scrape.yml. Lokalno: ./gradlew runScraperJob (uz DB_* i TMDB_API_KEY).
tasks.register<JavaExec>("runScraperJob") {
    group = "application"
    description = "Skrejpuje sve bioskope, dodaje TMDB ID-jeve i upisuje u scraped_movies."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.milinko.scraping.ScraperJobKt")
}

// Playwright Java CLI (ista verzija kao biblioteka) - u CI-ju instalira Chromium + sistemske
// zavisnosti pre job-a: ./gradlew playwrightCli --args="install --with-deps chromium"
tasks.register<JavaExec>("playwrightCli") {
    group = "application"
    description = "Pokreće Playwright CLI (npr. instalacija browsera)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-status-pages-jvm")
    implementation("io.ktor:ktor-server-cors-jvm")
    implementation("io.ktor:ktor-server-rate-limit-jvm")
    // OVO JE TAČNA BIBLIOTEKA ZA JAVA SDK

    val jacksonVersion = "2.17.2"
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.core:jackson-core:$jacksonVersion")
    implementation("com.fasterxml.jackson.core:jackson-annotations:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

    implementation("com.squareup.okio:okio:3.9.0")

    // Google AI SDK
    implementation("com.google.genai:google-genai:0.1.0")

    // Faza D dorada (17. avgust 2026, korisničko pitanje "je l postoji biblioteka koja bi to radila
    // lokalno") - MiniLM embedding model, ONNX runtime + težine modela upakovani direktno u jar,
    // radi u istom JVM procesu bez mrežnog poziva/API ključa. Namerno POSTOJI USPOREDO sa Gemini
    // embedding-om (ne zamena) - korisnik želi da uporedi ponašanje, ne da trajno pređe (videti
    // TextEmbedder.kt i napomenu u MOVIELENS_DESIGN_PLAN.md o kompromisu kvaliteta na srpskom).
    implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:1.19.0-beta29")


    // Potrebno za stabilan rad sa stream-ovima u Java SDK-u
    implementation("io.projectreactor:reactor-core:3.6.0")

    implementation("io.ktor:ktor-client-core-jvm")
    implementation("io.ktor:ktor-client-cio-jvm")
    implementation("io.ktor:ktor-client-content-negotiation-jvm")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("io.ktor:ktor-server-config-yaml-jvm")
    implementation("com.charleskorn.kaml:kaml:0.55.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")


    //scraping biblioteke
    implementation("com.microsoft.playwright:playwright:1.42.0")
    implementation("org.jsoup:jsoup:1.17.2")

    implementation("org.jetbrains.exposed:exposed-core:0.41.1")
    implementation("org.jetbrains.exposed:exposed-dao:0.41.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.41.1")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.41.1")

    // PostgreSQL drajver
    implementation("org.postgresql:postgresql:42.7.11")

    // Firebase Admin SDK
    implementation("com.google.firebase:firebase-admin:9.4.2")

    // JWT za backend tokene
    implementation("com.auth0:java-jwt:4.4.0")

    // Ktor JWT auth plugin
    implementation("io.ktor:ktor-server-auth-jvm")
    implementation("io.ktor:ktor-server-auth-jwt-jvm")
}
