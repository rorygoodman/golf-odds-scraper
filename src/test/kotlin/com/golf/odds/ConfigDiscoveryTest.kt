package com.golf.odds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigDiscoveryTest {
    // --- event name matching ---
    @Test
    fun `matches the same event across bookmaker naming`() {
        val betfair = "PGA WW Technology Championship 2026"
        assertTrue(nameMatchScore(betfair, "World Wide Technology Championship 2026") > 0)
        assertTrue(nameMatchScore(betfair, "Golf - World Wide Technology Championship") > 0)
        assertEquals(0, nameMatchScore(betfair, "Abu Dhabi Championship 2026"))
        assertEquals(0, nameMatchScore(betfair, "PGA US Open 2027"))
    }

    @Test
    fun `bestMatch picks the closest name and ignores non-matches`() {
        val headers = listOf("Golf - Abu Dhabi Aldar Championship", "Golf - World Wide Technology Championship", "Golf - US Open")
        assertEquals("Golf - World Wide Technology Championship", bestMatch("PGA WW Technology Championship 2026", headers) { it })
        assertNull(bestMatch("PGA Bermuda Championship 2026", headers) { it })
    }

    @Test
    fun `isWinnerMarket excludes without-markets`() {
        assertTrue(isWinnerMarket("Winner - 10 Places Each Way"))
        assertTrue(isWinnerMarket("World Wide Technology Championship 2026 - Tournament Winner 10 Places"))
        assertFalse(isWinnerMarket("Winner w/o Mitchell / Griffin / Gerard / James / Koivun"))
        assertFalse(isWinnerMarket("Top 10 Finish (Incl. Ties)"))
    }

    // --- terms parsing ---
    @Test
    fun `parseWilliamHillTerms reads places from title`() {
        val m = parseWilliamHillTerms("WWT 2026 - Tournament Winner 10 Places", "1/5 Odds Place 1, 2, 3, 4, 5, 6, 7, 8, 9, 10")
        assertEquals(10, m?.places)
        assertEquals("1/5", m?.placeFraction)
    }

    @Test
    fun `parseWilliamHillTerms counts places from annotation`() {
        val m = parseWilliamHillTerms("WWT 2026 - Tournament Winner", "1/4 Odds Place 1, 2, 3, 4, 5")
        assertEquals(5, m?.places)
        assertEquals("1/4", m?.placeFraction)
    }

    @Test
    fun `parsePaddyPowerTerms reads fraction and places`() {
        val m = parsePaddyPowerTerms("Winner - 10 Places Each Way", "Each Way: 1/5 Odds, 10 Places")
        assertEquals(EachWayMarket("Winner - 10 Places Each Way", 10, "1/5"), m)
        assertNull(parsePaddyPowerTerms("Top 5 Finish (Incl. Ties)", ""))
    }

    @Test
    fun `parseLadbrokesTerms counts listed places`() {
        val m = parseLadbrokesTerms("EW: 1/5 Odds - Places 1-2-3-4-5-6-7-8")
        assertEquals(8, m?.places)
        assertEquals("1/5", m?.placeFraction)
        assertNull(parseLadbrokesTerms("Outright"))
    }

    // --- market choice and config ---
    @Test
    fun `pickPage chooses most places and lists the rest`() {
        val standard = EachWayMarket("Winner", 5, "1/4")
        val extra = EachWayMarket("Winner - 10 Places Each Way", 10, "1/5")
        val found = pickPage(listOf(standard, extra)) {
            Page(url = "u", bookmaker = Bookmaker.PADDY_POWER, places = it.places, header = it.title, placeFraction = nonDefaultFraction(it))
        }
        assertEquals(extra, found?.chosen)
        assertEquals(listOf(standard), found?.others)
        assertNull(found?.page?.placeFraction)
        assertNull(pickPage(emptyList()) { Page(url = "u", bookmaker = Bookmaker.LADBROKES) })
    }

    @Test
    fun `nonDefaultFraction drops the scraper default`() {
        assertNull(nonDefaultFraction(EachWayMarket("t", 10, "1/5")))
        assertEquals("1/4", nonDefaultFraction(EachWayMarket("t", 5, "1/4")))
    }

    @Test
    fun `shortEventName drops year and generic suffixes`() {
        assertEquals("PGA WW Technology", shortEventName("PGA WW Technology Championship 2026"))
        assertEquals("PGA Mexico", shortEventName("PGA Mexico Open 2026"))
        assertEquals("PGA US", shortEventName("PGA US Open 2027"))
    }

    @Test
    fun `renderConfig omits nulls and round-trips`() {
        val config = ScraperConfig(listOf(Event(
            name = "PGA X",
            betfairLink = "https://www.betfair.com/exchange/plus/golf/market/1.1",
            betfairTop10Link = null,
            pages = listOf(Page(url = "https://example.com/a=b", bookmaker = Bookmaker.LADBROKES, places = 8))
        )))
        val json = renderConfig(config)
        assertFalse("betfairTop10Link" in json, json)
        assertFalse("header" in json, json)
        assertTrue("\"url\": \"https://example.com/a=b\"" in json, json)
        assertEquals(config, com.google.gson.Gson().fromJson(json, ScraperConfig::class.java))
    }
}
