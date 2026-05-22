#!/bin/bash
# ===========================================================
# Skript za lokalnu konfiguraciju i testiranje sa Docker-om
# ===========================================================

set -e

echo "🚀 MovieLensBackendKtor - Docker Setup Script"
echo "=============================================="
echo ""

# Provera da li je Docker instaliran
if ! command -v docker &> /dev/null; then
    echo "❌ Docker nije instaliran! Preuzmi sa https://www.docker.com/products/docker-desktop"
    exit 1
fi

echo "✅ Docker verzija:"
docker --version
echo ""

# Provera da li je docker-compose instaliran
if ! command -v docker-compose &> /dev/null; then
    echo "⚠️  docker-compose nije pronađen, ali Docker Desktop ima built-in verziju"
    DOCKER_COMPOSE="docker compose"
else
    DOCKER_COMPOSE="docker-compose"
fi

# Kreiraj .env datoteku ako ne postoji
if [ ! -f ".env" ]; then
    echo "📝 Kreiramo .env datoteku iz template-a..."
    if [ -f ".env.example" ]; then
        cp .env.example .env
        echo "✅ .env kreiran! Popuni ga sa API ključevima:"
        echo "   - GEMINI_API_KEY=..."
        echo "   - TMDB_API_KEY=..."
    else
        echo "❌ .env.example ne postoji!"
        exit 1
    fi
fi

# Provera .env
echo ""
echo "🔐 Provera .env datoteke..."
if grep -q "GEMINI_API_KEY=$" .env; then
    echo "⚠️  GEMINI_API_KEY nije postavljen!"
fi
if grep -q "TMDB_API_KEY=$" .env; then
    echo "⚠️  TMDB_API_KEY nije postavljen!"
fi

echo ""
echo "🔨 Build Dockerfile..."
$DOCKER_COMPOSE build

echo ""
echo "📦 Pokretanje Docker Compose..."
$DOCKER_COMPOSE up -d

echo ""
echo "⏳ Čekam da se servisi poddignu (30 sekundi)..."
sleep 30

echo ""
echo "🧪 Testiranje connection..."
if $DOCKER_COMPOSE logs app | grep -q "DATABASE.*Uspešno"; then
    echo "✅ Database konekcija OK"
else
    echo "⚠️  Proveris database konekciju u logs-ima"
fi

echo ""
echo "🌐 Server je dostupan na http://localhost:8080"
echo ""
echo "Korisne komande:"
echo "  Logs:          $DOCKER_COMPOSE logs -f app"
echo "  Stop:          $DOCKER_COMPOSE down"
echo "  Restart:       $DOCKER_COMPOSE restart app"
echo "  Shell u app:   docker exec -it movielenswrapper_app /bin/bash"
echo "  DB shell:      docker exec -it movielenswrapper_db psql -U postgres"
echo ""
echo "🧪 Test zahteva:"
echo "  curl http://localhost:8080/"
echo ""

