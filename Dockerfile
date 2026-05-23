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

# Playwright native dependencies for Chromium/WebKit/Firefox on Ubuntu (single-service setup).
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    libasound2 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libgbm1 \
    libglib2.0-0 \
    libgtk-3-0 \
    libnspr4 \
    libnss3 \
    libx11-xcb1 \
    libxcomposite1 \
    libxdamage1 \
    libxfixes3 \
    libxrandr2 \
    libxss1 \
    libxtst6 \
    libxext6 \
    libxshmfence1 \
    libxkbcommon0 \
    libpango-1.0-0 \
    libcairo2 \
    libgstreamer1.0-0 \
    libgstreamer-plugins-base1.0-0 \
    libgstreamer-gl1.0-0 \
    libgstreamer-plugins-bad1.0-0 \
    libatomic1 \
    libxslt1.1 \
    libwoff1 \
    libvpx7 \
    libevent-2.1-7 \
    libopus0 \
    libharfbuzz-icu0 \
    libwebpdemux2 \
    libenchant-2-2 \
    libsecret-1-0 \
    libhyphen0 \
    libmanette-0.2-0 \
    libflite1 \
    libgles2 \
    libx264-163 \
    && rm -rf /var/lib/apt/lists/*

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
    SKIP_SCRAPING="false"

# Run the application
CMD ["java", "-jar", "/app/app.jar"]
