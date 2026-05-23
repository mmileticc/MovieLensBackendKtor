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
# Stage 2: Runtime (samo ono što je potrebno)
# ============================================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Instalirajmo Playwright dependencies (za scraping)
RUN apt-get update && apt-get install -y \
    libasound2 \
    libgbm1 \
    libnss3 \
    libxss1 \
    libappindicator3-1 \
    libindicator7 \
    libxslt1.1 \
    libgconf-2-4 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libxcomposite1 \
    libxdamage1 \
    libxrandr2 \
    libgtk-3-0 \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Kopiramo JAR fajl iz build stage-a
COPY --from=builder /app/build/libs/*.jar /app/app.jar


# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/ || exit 1

# Expose port
EXPOSE 8080

# Environment varijable (trebalo bi postaviti na Render-u)
ENV GEMINI_API_KEY="" \
    TMDB_API_KEY="" \
    DB_HOST="" \
    DB_PORT="6543" \
    DB_NAME="postgres" \
    DB_USER="postgres" \
    DB_PASSWORD=""

# Pokretnji command
CMD ["java", "-jar", "/app/app.jar"]

