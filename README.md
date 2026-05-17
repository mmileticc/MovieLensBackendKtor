# GeminiWrapperBackendKtor

Ktor backend koji služi kao proxy između Android aplikacije i Google Gemini AI API-ja.

## Šta je implementirano
- POST `/chat` koji prima JSON `{ "message": "..." }` i STREAM-uje plain-text odgovor čim stigne od modela.
- Integracija sa Google Gemini REST API-jem preko Ktor HTTP klijenta (SSE striming).
- ContentNegotiation sa Kotlinx Serialization (JSON).
- Osnovni error handling (StatusPages i validacije na ruti).
- Service sloj (`GeminiService`) koji enkapsulira pozive ka modelu.
- CORS omogućen (trenutno `anyHost()` – preporuka suziti za produkciju).

## Preuslovi
- Java 21 (JDK 21)
- Postavljen API ključ:
  - Preko okruženja: `GOOGLE_API_KEY` (preporučeno)
  - Ili u `src/main/resources/application.yaml` pod `ktor.gemini.apiKey` (ne preporučuje se za produkciju)

## Build i pokretanje
- Windows PowerShell:
  - Postavi ključ za trenutnu sesiju: `setx GOOGLE_API_KEY "VAŠ_KLJUČ"`
  - Pokreni server: `./gradlew.bat run`

Ako se server podigne uspešno, biće dostupan na `http://localhost:8080`.

## Korišćenje /chat endpoint‑a
Primer (PowerShell):
```
curl -Method POST \
     -Uri http://localhost:8080/chat \
     -ContentType 'application/json' \
     -Body '{"message":"Zdravo, objasni mi Ktor u par rečenica."}'
```
Odgovor će stizati strimingom (plain text) u delovima kako ih model generiše.

## Podešavanja
`src/main/resources/application.yaml`
```
ktor:
  deployment:
    port: 8080
  gemini:
    # apiKey: "YOUR_GOOGLE_API_KEY"   # ako ne koristite env var (nije preporučeno)
    model: "gemini-1.5-flash"
```

## Napomene za produkciju
- Ograničiti CORS na tačne origene.
- Ne držati API ključeve u repozitorijumu; koristiti secrets management.
- Dodati ratelimiting/auth po potrebi.

## Napomena o strimingu
- Endpoint koristi SSE odgovor Google Gemini REST API-ja i prosleđuje ga klijentu čim stigne.
- Ako koristite `curl` na nekim sistemima, možda je potrebno dodati opciju za nebufferisano ispisivanje (`-N`).

