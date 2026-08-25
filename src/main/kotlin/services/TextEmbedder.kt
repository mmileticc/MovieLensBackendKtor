package com.milinko.services

import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Faza D dorada (17. avgust 2026, korisničko pitanje "je l postoji biblioteka koja bi to radila
 * lokalno da se ne ide Gemini-ju uopšte") - apstrakcija nad "kako se tekst pretvara u vektor",
 * da [CinemaSemanticIndex] ne zavisi direktno od Gemini-ja. Dve implementacije ispod NAMERNO
 * postoje USPOREDO (ne zamena jedne drugom) - korisnik želi da uporedi ponašanje/kvalitet, ne da
 * trajno pređe sa jedne na drugu. `embedDocument`/`embedQuery` su odvojeni jer Gemini ima
 * `taskType` razdvajanje (bolji kvalitet), dok lokalni MiniLM model tu razliku nema (obe metode
 * rade identično) - ovim se ta asimetrija sakriva iza istog interfejsa.
 */
interface TextEmbedder {
    /** Kratak identifikator za logove ("Gemini", "Lokalno (MiniLM)") - ne utiče na sam vektor. */
    val label: String

    // Faza D dorada (23. avgust 2026) - stabilan mašinski ključ ("gemini"/"local"), NAMERNO isti
    // string kao `?engine=` query parametar rute (MovieRouting.kt) i kao vrednost u koloni
    // MovieEmbeddingsTable.engine (DB keš embedding-a) - jedno mesto istine za "koji je ovo
    // provajder", bez potrebe za mapiranjem između tri različita mesta.
    val key: String
    suspend fun embedDocument(text: String): FloatArray
    suspend fun embedQuery(text: String): FloatArray
}

/** Tanak omotač oko postojećeg [GeminiService.embedText] - REST poziv, videti obrazloženje tamo. */
class GeminiTextEmbedder(private val geminiService: GeminiService) : TextEmbedder {
    override val label = "Gemini"
    override val key = "gemini"
    override suspend fun embedDocument(text: String): FloatArray = geminiService.embedText(text, "RETRIEVAL_DOCUMENT")
    override suspend fun embedQuery(text: String): FloatArray = geminiService.embedText(text, "RETRIEVAL_QUERY")
}

/**
 * MiniLM (all-MiniLM-L6-v2, 384 dimenzije) preko langchain4j - ONNX runtime i težine modela su
 * upakovani direktno u jar (`langchain4j-embeddings-all-minilm-l6-v2` zavisnost), NEMA download-a
 * pri startu, NEMA mrežnog poziva pri embedovanju - potpuno offline, bez API ključa. Model se
 * učitava JEDNOM (konstruktor) i ponovo koristi za svaki poziv - konstruisanje je relativno skupo
 * (učitavanje ONNX grafa), sam `embed()` poziv je brz.
 *
 * Kompromis (videti MOVIELENS_DESIGN_PLAN.md) - MiniLM je mnogo manji model od Gemini-jevog i
 * primarno treniran na engleskom, za razliku od Gemini-ja koji je EKSPLICITNO građen za unakrsnu
 * višejezičnu pretragu. Pretraga na srpskom (upit) protiv engleskih opisa filmova (dokumenti) će
 * ovde raditi primetno lošije nego sa Gemini-jem - to je i poenta ovog poređenja.
 */
class LocalTextEmbedder : TextEmbedder {
    override val label = "Lokalno (MiniLM)"
    override val key = "local"

    // Bugfix (25. avgust 2026, korisnička žalba "Render probao da hostuje i pukao na
    // OutOfMemoryError") - RANIJE eager (`= AllMiniLmL6V2EmbeddingModel()`, učitava ceo ONNX model
    // u memoriju ODMAH pri konstrukciji ovog objekta). `LocalTextEmbedder()` se konstruiše u
    // Application.kt PRE nego što server stigne da osluškuje port - na hostu sa malo RAM-a (npr.
    // Render free plan) ovo je bacalo OutOfMemoryError i rušilo ceo deploy, ne samo lokalnu
    // pretragu. `by lazy` odlaže stvarno učitavanje modela do PRVOG poziva embed() - u kombinaciji
    // sa ENABLE_LOCAL_EMBEDDING flag-om u Application.kt (koji sprečava da se refresh(), jedini
    // automatski poziv koji bi ovo trigerovao, uopšte pozove na ograničenim hostovima), model se
    // sad NIKAD ne učitava osim ako je neko eksplicitno zatražio `engine=local` pretragu.
    private val model by lazy { AllMiniLmL6V2EmbeddingModel() }

    // ONNX inferenca je blokirajuća CPU operacija (ne suspendabilna sama po sebi) - Dispatchers.Default
    // (CPU-bound thread pool) je ispravan izbor, isti princip kao namenski sdkDispatcher u
    // GeminiService za blokirajuće Gemini SDK pozive.
    override suspend fun embedDocument(text: String): FloatArray = embed(text)
    override suspend fun embedQuery(text: String): FloatArray = embed(text)

    private suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext FloatArray(0)
        model.embed(trimmed).content().vector()
    }
}
