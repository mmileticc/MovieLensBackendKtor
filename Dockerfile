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
FROM mcr.microsoft.com/playwright:latest

WORKDIR /app

# Playwright base image already includes browsers and most native deps.
# Install minimal tooling and then install a Temurin JRE so the Kotlin/Java jar
# can run inside the same container.
RUN apt-get update && apt-get install -y curl ca-certificates --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

# Download and install Eclipse Temurin JRE 21 (adjust URL/version if needed)
ENV TEMURIN_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.2+9/OpenJDK21U-jre_x64_linux_hotspot_21.0.2_9.tar.gz"
RUN curl -fsSL "$TEMURIN_URL" -o /tmp/temurin.tar.gz \
    && mkdir -p /opt/java \
    && tar -xzf /tmp/temurin.tar.gz -C /opt/java --strip-components=1 \
    && ln -s /opt/java/bin/java /usr/local/bin/java \
    && rm /tmp/temurin.tar.gz

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

