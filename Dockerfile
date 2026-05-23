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

# Kopiramo JAR fajl iz build stage-a
COPY --from=builder /app/build/libs/*.jar /app/app.jar


# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/ || exit 1

# Expose port
EXPOSE 8080

# Environment varijable (trebalo bi postaviti na Render-u)
# By default we disable scraping in the web service; scraping runs in a separate worker.
ENV GEMINI_API_KEY="" \
    TMDB_API_KEY="" \
    DB_HOST="" \
    DB_PORT="6543" \
    DB_NAME="postgres" \
    DB_USER="postgres" \
    DB_PASSWORD="" \
    SKIP_SCRAPING="true"

# Run the application
CMD ["java", "-jar", "/app/app.jar"]
