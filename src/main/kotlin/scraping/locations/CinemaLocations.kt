package com.milinko.scraping.locations

data class GeoKoordinate(val lat: Double, val lon: Double)

object LokacijeBioskopa {
    private val mapaLokacija = mapOf(
        // Beograd
        "CINEPLEXX UŠĆE SHOPPING CENTER" to GeoKoordinate(44.815408626077144, 20.438233757045207),
        "CINEPLEXX 4D DELTA CITY" to GeoKoordinate(44.80579810936114, 20.404207705350725),
        "CINEPLEXX BEO SHOPPING CENTER" to GeoKoordinate(44.786846727556295, 20.502239250635675),
        "CINEPLEXX GALERIJA" to GeoKoordinate(44.80292957091388, 20.445541710162807),
        "CINEPLEXX BIG BEOGRAD" to GeoKoordinate(44.81729769358283, 20.509694481327838), // BIG Rakovica (bivši Capitol Park)
        "CINEPLEXX BIG FASHION" to GeoKoordinate(44.81729769358283, 20.509694481327838), // Karaburma (za svaki slučaj ako zatreba)

        // Van Beograda
        "CINEPLEXX NOVI SAD" to GeoKoordinate(45.24635819154759, 19.84407774885036),    // Promenada Shopping Center
        "CINEPLEXX NIŠ, STOP SHOP" to GeoKoordinate(43.31147230494104, 21.937512027273435), // Stop Shop Niš
        "CINEPLEXX BIG KRAGUJEVAC" to GeoKoordinate(44.00873780212743, 20.89577123895372),  // BIG Kragujevac (bivša Plaza)

        //Cine Grand-s
        "CINE GRAND BIG RAKOVICA" to GeoKoordinate(44.739390321835266, 20.437888242328093),      // Patrijarha Dimitrija 14, Beograd (Rakovica)
        "CINE GRAND DELTA PLANET" to GeoKoordinate(43.436770798684805, 21.943052760736126),      // Delta Planet Niš
        "CINE GRAND BIG ČAČAK" to GeoKoordinate(43.88949644029959, 20.37005068127539),         // BIG Čačak
        "CINE GRAND BIG ŠABAC" to GeoKoordinate(44.74893526332583, 19.706378034757783),         // BIG Šabac
        "BIOSKOP VILIN GRAD" to GeoKoordinate(43.31914466808712, 21.89501503891535)            // Vilin Grad
    )

    /**
     * Prima sirov tekst sa sajta, normalizuje ga i vraća koordinate.
     */
    fun nadjiKoordinate(nazivSaSajta: String): GeoKoordinate? {
        val kljuc = nazivSaSajta.uppercase().trim()

        // 1. Prvo probamo direktno poklapanje (Ovo je O(1) i najbrže je)
        val direktnoPoklapanje = mapaLokacija[kljuc]
        if (direktnoPoklapanje != null) return direktnoPoklapanje

        // 2. "Fuzzy" fallback (za svaki slučaj): Ako sajt sutra promeni sitnicu,
        // proveravamo da li naš ključ bar SADRŽI naziv bioskopa (npr. ako stigne samo "UŠĆE")
        return mapaLokacija.entries.firstOrNull { (kodiraniNaziv, _) ->
            kljuc.contains(kodiraniNaziv) || kodiraniNaziv.contains(kljuc)
        }?.value
    }
}