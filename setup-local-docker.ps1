# ===========================================================
# Skript za lokalnu konfiguraciju i testiranje sa Docker-om
# (verzija za Windows PowerShell)
# ===========================================================

Write-Host "🚀 MovieLensBackendKtor - Docker Setup Script (Windows)" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host ""

# Provera da li je Docker instaliran
try {
    $dockerVersion = docker --version
    Write-Host "✅ Docker je instaliran:" -ForegroundColor Green
    Write-Host $dockerVersion
} catch {
    Write-Host "❌ Docker nije instaliran!" -ForegroundColor Red
    Write-Host "Preuzmi sa: https://www.docker.com/products/docker-desktop" -ForegroundColor Yellow
    exit 1
}

Write-Host ""

# Kreiraj .env datoteku ako ne postoji
if (-Not (Test-Path ".env")) {
    Write-Host "📝 Kreiramo .env datoteku iz template-a..." -ForegroundColor Cyan
    if (Test-Path ".env.example") {
        Copy-Item ".env.example" ".env"
        Write-Host "✅ .env kreiran! Popuni ga sa API ključevima:" -ForegroundColor Green
        Write-Host "   - GEMINI_API_KEY=..." -ForegroundColor Yellow
        Write-Host "   - TMDB_API_KEY=..." -ForegroundColor Yellow
    } else {
        Write-Host "❌ .env.example ne postoji!" -ForegroundColor Red
        exit 1
    }
}

Write-Host ""
Write-Host "🔐 Provera .env datoteke..." -ForegroundColor Cyan

$envContent = Get-Content ".env"
if ($envContent | Select-String -Pattern "GEMINI_API_KEY=$" -Quiet) {
    Write-Host "⚠️  GEMINI_API_KEY nije postavljen!" -ForegroundColor Yellow
}
if ($envContent | Select-String -Pattern "TMDB_API_KEY=$" -Quiet) {
    Write-Host "⚠️  TMDB_API_KEY nije postavljen!" -ForegroundColor Yellow
}
if ($envContent | Select-String -Pattern "DB_PASSWORD=$" -Quiet) {
    Write-Host "⚠️  DB_PASSWORD nije postavljen!" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "🔨 Build Dockerfile..." -ForegroundColor Cyan
docker-compose build

Write-Host ""
Write-Host "📦 Pokretanje Docker Compose..." -ForegroundColor Cyan
docker-compose up -d

Write-Host ""
Write-Host "⏳ Čekam da se servisi poddignu (30 sekundi)..." -ForegroundColor Yellow
Start-Sleep -Seconds 30

Write-Host ""
Write-Host "🧪 Testiranje connection..." -ForegroundColor Cyan
$logs = docker-compose logs app
if ($logs -match "DATABASE.*Uspešno|Connected") {
    Write-Host "✅ Database konekcija OK" -ForegroundColor Green
} else {
    Write-Host "⚠️  Proveris database konekciju u logs-ima" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "🌐 Server je dostupan na:" -ForegroundColor Green
Write-Host "   http://localhost:8080" -ForegroundColor Cyan

Write-Host ""
Write-Host "Korisne komande:" -ForegroundColor Green
Write-Host "  Logs (real-time):  docker-compose logs -f app" -ForegroundColor Yellow
Write-Host "  Stop servise:      docker-compose down" -ForegroundColor Yellow
Write-Host "  Restart app:       docker-compose restart app" -ForegroundColor Yellow
Write-Host "  Shell u app:       docker exec -it movielenswrapper_app /bin/bash" -ForegroundColor Yellow
Write-Host "  DB shell:          docker exec -it movielenswrapper_db psql -U postgres" -ForegroundColor Yellow
Write-Host "  Test health:       curl.exe http://localhost:8080/" -ForegroundColor Yellow

Write-Host ""
Write-Host "✨ Setup završen!" -ForegroundColor Green

