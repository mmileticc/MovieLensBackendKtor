# ============================================
# Stage 1: Build (kompajliranje Gradle projekta)
# ============================================
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# Kopiramo Gradle wrapper i konfiguracije
COPY gradlew gradlew.bat gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle/ ./gradle/

# Kopiramo source kod
COPY src/ ./src/

# Dajemo execute permission na gradlew
RUN chmod +x ./gradlew

# Pokretamo build (bez testova)
RUN ./gradlew build -x test --no-daemon

# ============================================
# Stage 2: Runtime (lightweight web service)
# ============================================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Samo curl za HEALTHCHECK. Playwright sistemske biblioteke namerno NISU ovde - dnevni scraping
# radi GitHub Actions (ScraperJob), a web servis radi sa SCRAPING_BOOLEAN=false.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Kopiramo JAR fajl iz build stage-a
COPY --from=builder /app/build/libs/*.jar /app/app.jar


# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/ || exit 1

# Expose port
EXPOSE 8080

# Tajne (GEMINI_API_KEY, TMDB_API_KEY, DB_PASSWORD, APP_JWT_SIGNING_KEY...) se postavljaju na
# Render-u, ne ovde. Scraping je podrazumevano isključen - radi ga GitHub Actions.
ENV DB_PORT="6543" \
    DB_NAME="postgres" \
    DB_USER="postgres" \
    SCRAPING_BOOLEAN="false"

# Run the application
CMD ["java", "-jar", "/app/app.jar"]
