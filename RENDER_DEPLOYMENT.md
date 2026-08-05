# 🚀 Uputstvo za objavljivanje na Render.com

## 📋 Preduslovi

1. **GitHub račun** - sa uploadovan projekat
2. **Render račun** - https://render.com (besplatna tier dostupna)
3. **Supabase ili PostgreSQL baza** - za podatke
4. **API ključevi** - Gemini i TMDB
5. **Git instaliran lokalno**

---

## 🔐 SIGURNOST: Provera tajnih ključeva

### ✅ DOBRO VESTI
Tvoj projekat je pravilno konfiguriran:
- **Nema hardkodovanih ključeva** u kodu
- Koristi **Environment varijable** (preporučeni pristup)
- `.gitignore` je pravilno postavljen

### ⚠️ VAŽNO: Što da proveriš pre nego što uploaduješ!

```bash
# Pretraga potencijalnih izloženih ključeva
git grep -i "api.key\|apikey\|password\|secret\|token" -- "*.kt" "*.yaml" "*.properties"
```

Ako nešto nađeš:
1. Odmah izbriši ključ
2. Regeneriši ga u svim servisima (Google, TMDB, Supabase)
3. Napravi novi commit

**Fajlove koji NIKAD ne smeju biti committed:**
- `.env` (koristi `.env.example` kao template)
- `application.yaml` sa tajnama
- Bilo koji `secrets.properties`

---

## 📦 KORAK 1: Pripremi GitHub repozitorijum

### 1.1 Lokalno proveri šta će biti uploadovano
```powershell
git status
git ls-files  # Lista svih fajlova koji će biti commitovani
```

### 1.2 Makni `.env` i tajne (ako postoje)
```powershell
# Uobičajeni ignored fajlovi:
# - .env, .env.local, .env.*.local
# - application.yaml (ako ima tajne)
# - Settings u .idea foldera

# Proveri .gitignore
cat .gitignore | Select-String -Pattern "\.env|secrets"
```

### 1.3 Commit i push
```powershell
git add .
git commit -m "Priprema za Render deployment - Dockerfile i konfiguracija"
git push origin main
```

---

## 🎯 KORAK 2: Kreiraj Web servis na Render.com

### 2.1 Kreni novi servis
1. Idi na https://dashboard.render.com
2. Klikni na **"New +"** → **"Web Service"**
3. Klikni **"Connect a repository"**
4. Izaberi GitHub račun kada te prosledi na autentifikaciju
5. Izaberi svoj **MovieLensBackendKtor** repozitorijum

### 2.2 Konfiguracija servisa

**Osnovni podaci:**
- **Name**: `movielenswrapper` (ili kako god želiš)
- **Branch**: `main`
- **Build Command**: `./gradlew build -x test --no-daemon`
- **Start Command**: `java -jar build/libs/*shadowJar.jar` 
  - ILI ako nemaš shadowJar: `java -jar build/libs/GeminiWrapperBackendKtor-0.0.1.jar`
- **Runtime**: Docker
- **Region**: Serbia Central (ili najbliža lakacija)

**Plan (Free je ok za početak, ali Starter $7/mesečno je stabilniji):**
- Free: spava nakon 15min inaktivnosti
- Starter: $7/mesechno, uvek aktivan

---

## 🔧 KORAK 3: Environment varijable

U Render dashboardu, u sekciji **"Environment"**, dodaj:

```
GEMINI_API_KEY=YOUR_GEMINI_KEY_HERE
TMDB_API_KEY=YOUR_TMDB_KEY_HERE
DB_HOST=your-database.supabase.co
DB_PORT=6543
DB_NAME=postgres
DB_USER=postgres
DB_PASSWORD=YOUR_SUPABASE_PASSWORD
```

### Gde da nađeš ove ključeve?

**GEMINI_API_KEY:**
- Idi na https://aistudio.google.com/apikey
- Generiši nov APi ključ ("Create new API key")
- Kopiraj direktno u Render

**TMDB_API_KEY:**
- https://www.themoviedb.org/settings/api
- Generiši approval i kopiraj API ključ (v3)

**DB_* (Supabase):**
- https://app.supabase.com
- Izaberi svoj projekat → Settings → Database
- Kopiraj connection string i napravi environment varijable

**Primer Supabase connection stringa:**
SQL format: `postgresql://user:password@db.supabase.co:6543/postgres`

### Mapiranje u Render:
```
DB_HOST = db.supabase.co
DB_PORT = 6543
DB_USER = postgres (ili username)
DB_PASSWORD = (password iz connection stringa)
DB_NAME = postgres
```

---

## 🏗️ KORAK 4: Database setup (PostgreSQL/Supabase)

Treba postaviti tabele ako već nisu kreirane.

### 4.1 Kreiraj Supabase projekat
1. https://app.supabase.com
2. **New project** → Izaberi region
3. Database password spremi negde bezbedno!

### 4.2 Kreiraj tabele
Ručni SQL više NIJE potreban. `DatabaseFactory.init()` poziva `SchemaUtils.createMissingTablesAndColumns(...)`
pri svakom startu aplikacije i kreira/ažurira tabele (`users`, `scraped_movies`) direktno prema
Exposed definicijama iz koda (`UsersTable.kt`, `ScrapedMoviesTable.kt`). To je jedini izvor istine za
šemu — ne održavaj SQL ovde ručno jer će vremenom otići iz sinhronizacije sa kodom (što se i desilo
sa starijom verzijom ovog dokumenta).

Dovoljno je da napraviš prazan Supabase projekat i podesiš env promenljive (Korak 4.1 i 4.3) — tabele
se pojavljuju automatski nakon prvog uspešnog starta servisa.

---

## 📝 KORAK 5: Deploy!

### 5.1 Render Auto-deploy
Čim pushneš na `main` branch, Render će:
1. Automatic checkout koda
2. Build Dockerfile
3. Deploy novi servis

Monitor log u **"Logs"** sekciji.

### 5.2 Manuelni Re-deploy
Render dashboard → Servis → **"Manual Deploy"** → **"Deploy latest commit"**

---

## 🧪 KORAK 6: Test

### Tvoj server će biti dostupan na:
```
https://tvoje-ime-servisa-render.onrender.com
```

Primer test zahteva:
```powershell
# Test health check
curl -Uri "https://tvoje-ime-servisa-render.onrender.com/" 

# Test chat vision (sa slikom)
$body = @{
    message = "Test"
} | ConvertTo-Json

curl -Method POST `
     -Uri "https://tvoje-ime-servisa-render.onrender.com/chat/vision" `
     -ContentType "multipart/form-data" `
     # ... (kompleksniji zahtev)
```

---

## ⚠️ Važne napomene

### 1. **Cold start** (Free tier)
- Prvi zahtev može trajati 50-60 sekundi
- Severs "spava" nakon 15 minuta bez zahteva
- Starter plan nema ovog problema

### 2. **File upload** - "lepa sela.jpg"
- `COPY "lepa sela.jpg" ./` je u Dockerfile-u
- Fajl mora biti u root foldera projekta
- Ako ga premestiš, updat Dockerfile

### 3. **Playwright drajveri**
- Dockerfile instalirajumaju Playwright sistema zavisnosti
- Prvi build može trajati duže

### 4. **Logs**
```
Render → Servis → "Logs" tab
```
Proveraj za greške tokom deployment-a

### 5. **Automatic Redeploy**
- Svaki push na `main` automatski re-deployuje
- Spremi tajne u Render, ne u GitHub!

---

## 🛡️ Pre nego što pushneš ostatak - FINAL SIGURNOSNA PROVERA

### Izvrši ove komande:

```powershell
# 1. Pretraga po ključnim rečima
git log -p --all -S "GEMINI_API_KEY=" -- "*.kt" "*.yaml"
git log -p --all -S "password=" -- "*"

# 2. Pretraga istorije za slučajne ključeve
git log -p --all -S "sk-" -- "*"  # OpenAI formata
git log -p --all -S "AIza" -- "*"  # Google formata

# 3. Pretraga u trenutnom kodu
Select-String -Path . -Include "*.kt", "*.yaml", "*.properties" `
    -Pattern "password|apiKey|=.*[a-zA-Z0-9]{20,}" -Recurse
```

### Ako nešto nađeš:
```powershell
# Obriši istoriju (OPASNO - prvo backup!)
# Koristi: git-filter-repo ili BFG Repo-Cleaner
# Regeneriši sve ključeve na svim servisima
```

---

## 📊 Monitoring na Render-u

1. **Metrics** tab - CPU, Memory, egzecucije
2. **Logs** tab - Real-time logging
3. **Events** tab - Deploy history

---

## 🆘 Troubleshooting

| Problem | Rešenje |
|---------|--------|
| Build fails | Proveris `.gradlew` ima execute permission |
| Database konekcija pada | Proveris environment varijable, SSL mode |
| API ključ error | Regeneriši ključ, proveris ga u Render ENV |
| Out of memory | Upgrade na Starter plan ili optimizuj Scrapers |
| 502 error | Proveris Logs - obično DB konekcija |

---

## 🎉 Gotovo!

Sada imaš Kotlin/Ktor servis live na Render.com!

**Sledeće korake:**
1. Monitoruj logs redovno
2. Setuj notifications u Render account
3. Razmisli o auto-scaling ako postane popularniji
4. Upravljaj bazom direktno iz Supabase dashboarda

---

**Verzija:** 1.0  
**Poslednja ažuriranja:** May 2026
**Info:** Uputstvo za MovieLensBackendKtor na Render.com

