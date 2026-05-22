# ⚡ BRZI PREGLED - Deployment MovieLensBackendKtor

## 📋 Šta je urađeno

✅ **Dockerfile** - Multi-stage build za optimizaciju  
✅ **.dockerignore** - Makne nepotrebne fajlove iz image-a  
✅ **docker-compose.yml** - Local razvoj sa PostgreSQL  
✅ **CVE Sigurnost** - PostgreSQL ažuriran (42.7.2 → 42.7.11)  
✅ **.env.example** - Template za environment varijable  
✅ **RENDER_DEPLOYMENT.md** - Detaljno uputstvo za Render  
✅ **SECURITY_AUDIT.md** - Sigurnosna provera  
✅ **setup-local-docker.sh/.ps1** - Automatski setup skriptovi  

---

## 🚀 QUICK START - Render deployment

### KORAK 1: Lokalno testiranje (opciono)
```powershell
# Windows
.\setup-local-docker.ps1

# Linux/Mac
chmod +x setup-local-docker.sh
./setup-local-docker.sh
```

### KORAK 2: Pushuj na GitHub
```powershell
git add .
git commit -m "Add Docker, Deployment, Security audit"
git push origin main
```

### KORAK 3: Kreiraj servic na Render.com
1. Idi na https://dashboard.render.com
2. **New Web Service** → Poveži GitHub repozitorijum
3. Konfiguracija:
   - Branch: `main`
   - Build Command: `./gradlew build -x test --no-daemon`
   - Start Command: `java -jar build/libs/GeminiWrapperBackendKtor-0.0.1.jar`
   - Runtime: Docker

### KORAK 4: Postavi Environment varijable
U Render dashboardu → **Environment** dodaj:
```
GEMINI_API_KEY=<tvoj-ključ>
TMDB_API_KEY=<tvoj-ključ>
DB_HOST=<supabase-host>
DB_PORT=6543
DB_NAME=postgres
DB_USER=postgres
DB_PASSWORD=<tvoj-password>
```

### KORAK 5: Deploy!
Render će automatski:
- Buildovati Dockerfile
- Pokrenuti application.yaml
- Dostupan na `https://tvoj-servis.onrender.com`

---

## 🔐 SIGURNOST - Pre nego što pushneš!

### ✅ Provere:
1. **Nema .env sa tajnama**
   ```powershell
   rm .env  # ILI delete .env (Windows)
   ```

2. **Nema hard-coded ključeva**
   ```powershell
   git grep -i "apikey\|password\|secret"
   ```

3. **Sve je u Environment varijablama**
   - Gemini API ključ ✓
   - TMDB API ključ ✓
   - DB password ✓

4. **.env.example ne Contains tajne**
   - Template sa placeholder-ima ✓

---

## 📊 File struktura nakon setup-a

```
.
├── Dockerfile                      ← Build instrukcije
├── .dockerignore                  ← Šta se ne kopira
├── docker-compose.yml             ← Local dev setup
├── .env.example                   ← Template (bez tajni)
├── RENDER_DEPLOYMENT.md           ← Detaljno uputstvo
├── SECURITY_AUDIT.md              ← Sigurnosna provera
├── setup-local-docker.sh          ← Linux/Mac setup
├── setup-local-docker.ps1         ← Windows setup
├── build.gradle.kts               ← AŽURIRAN: PostgreSQL 42.7.11
└── src/
    └── ... (ostali fajlovi)
```

---

## 🧪 Test nakon deployment-a

```powershell
# Test endpoint
curl.exe "https://tvoj-servis.onrender.com/"

# Trebalo bi da vrati 200 OK
```

---

## ⚡ Komande

### Lokalni Docker
```powershell
docker-compose build          # Build image
docker-compose up -d          # Pokreni servise
docker-compose down           # Zaustavi servise
docker-compose logs -f app    # Prati log-ove
```

### Render
```
Dashboard → Logs              # Real-time logovanje
Dashboard → Events            # Deploy history
Dashboard → Metrics           # CPU, Memory, itd.
```

---

## 🆘 Troubleshooting

| Problem | Rešenje |
|---------|--------|
| Build fails | Proveris da build.gradle.kts ima PostgreSQL 42.7.11 |
| Connection error | Proveris environment varijable |
| 502 error | Proveris logs za grešku |
| App spava (Free tier) | Primo zahtev, čekaj 50+ sec |

---

## 📚 Detaljne Dokumente

- **RENDER_DEPLOYMENT.md** - Kompletan guide sa svim detaljima
- **SECURITY_AUDIT.md** - Sigurnosna analiza projekta

---

## 🎯 Sledeće korake

1. ✅ Localno testiraj sa `docker-compose` (opciono)
2. ✅ Pushuj na GitHub
3. ✅ Kreiraj Render servic
4. ✅ Postavi environment varijable
5. ✅ Monitoruj logs
6. ✅ Testiraj endpoint-e

---

## 💡 Pro tipovi

- **Free tier** spava nakon 15 min - nije idealno za produkciju
- **Starter plan** ($7/mes) je uvek aktivan - preporučeno
- **Dodaj health check** endpoint za monitoring
- **Koristi PostgreSQL connection pooling** ako je potrebno

---

## 📞 Česta pitanja

**P: Gde će biti server dostupan?**  
O: `https://tvoj-servis-render.onrender.com`

**P: Kako se automatski updeira?**  
O: Svaki push na `main` branch automatski se deployuje

**P: Kako resetovati šifru?**  
O: Render → Servis → Settings → Force rebuild & deploy

**P: Jesu li tajne sigurne?**  
O: DA - postavljene su u Render secure environment, nikad nisu dostupne u kodu

---

**Status:** ✅ READY FOR DEPLOYMENT  
**Last Update:** May 2026  
**For issues:** Vidi RENDER_DEPLOYMENT.md i SECURITY_AUDIT.md

