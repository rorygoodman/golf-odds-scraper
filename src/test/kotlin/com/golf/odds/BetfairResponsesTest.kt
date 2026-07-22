package com.golf.odds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BetfairResponsesTest {
    // --- parseSsoid ---
    @Test
    fun `parseSsoid extracts token on SUCCESS`() {
        val json = """{ "token": "abc123", "status": "SUCCESS", "error": "" }"""
        assertEquals("abc123", parseSsoid(json))
    }

    @Test
    fun `parseSsoid throws on non-SUCCESS with status in message`() {
        val json = """{ "token": "", "status": "INVALID_USERNAME_OR_PASSWORD", "error": "" }"""
        val e = assertFailsWith<IllegalStateException> { parseSsoid(json) }
        assertTrue("INVALID_USERNAME_OR_PASSWORD" in (e.message ?: ""), e.message)
    }

    @Test
    fun `parseSsoid mentions 2FA hint on LOGIN_RESTRICTED`() {
        val json = """{ "token": "", "status": "LOGIN_RESTRICTED", "error": "" }"""
        val e = assertFailsWith<IllegalStateException> { parseSsoid(json) }
        assertTrue("2FA" in (e.message ?: ""), "expected 2FA hint: ${e.message}")
    }

    @Test
    fun `parseSsoid throws on malformed JSON`() {
        assertFailsWith<IllegalStateException> { parseSsoid("not json") }
    }

    @Test
    fun `parseSsoid throws when token absent on SUCCESS`() {
        assertFailsWith<IllegalStateException> { parseSsoid("""{ "status": "SUCCESS" }""") }
    }

    // --- buildLoginBody ---
    @Test
    fun `buildLoginBody url-encodes both fields`() {
        assertEquals("username=a%40b.com&password=p%26q", buildLoginBody("a@b.com", "p&q"))
    }

    // --- marketIdFromUrl ---
    @Test
    fun `marketIdFromUrl extracts id from exchange link`() {
        assertEquals(
            "1.260239900",
            marketIdFromUrl("https://www.betfair.com/exchange/plus/golf/market/1.260239900")
        )
    }

    @Test
    fun `marketIdFromUrl tolerates trailing path or query`() {
        assertEquals(
            "1.260239901",
            marketIdFromUrl("https://www.betfair.com/exchange/plus/golf/market/1.260239901?priceHistory=1")
        )
    }

    @Test
    fun `marketIdFromUrl throws with url in message when unparseable`() {
        val e = assertFailsWith<IllegalArgumentException> {
            marketIdFromUrl("https://www.betfair.com/exchange/plus/en/golf-betting-3")
        }
        assertTrue("golf-betting-3" in (e.message ?: ""), e.message)
    }

    // --- body builders ---
    @Test
    fun `buildMarketIdsCatalogueBody filters by marketIds with runner projection`() {
        val body = buildMarketIdsCatalogueBody(listOf("1.1", "1.2"))
        assertTrue(""""marketIds":["1.1","1.2"]""" in body, body)
        assertTrue(""""EVENT"""" in body, body)
        assertTrue(""""RUNNER_DESCRIPTION"""" in body, body)
        assertTrue(""""maxResults":"2"""" in body, body)
    }

    @Test
    fun `buildMarketIdsCatalogueBody rejects empty list`() {
        assertFailsWith<IllegalArgumentException> { buildMarketIdsCatalogueBody(emptyList()) }
    }

    @Test
    fun `buildBookBody requests EX_BEST_OFFERS`() {
        val body = buildBookBody(listOf("1.1"))
        assertTrue(""""marketIds":["1.1"]""" in body, body)
        assertTrue(""""EX_BEST_OFFERS"""" in body, body)
    }

    @Test
    fun `buildBookBody rejects empty and oversize lists`() {
        assertFailsWith<IllegalArgumentException> { buildBookBody(emptyList()) }
        assertFailsWith<IllegalArgumentException> { buildBookBody(List(41) { "1.$it" }) }
    }

    // --- parseCatalogueMarkets ---
    @Test
    fun `parseCatalogueMarkets maps marketId to event name and runners`() {
        val json = """
        [
          {
            "marketId": "1.260239900",
            "marketName": "Winner",
            "event": { "id": "34141234", "name": "3M Open 2026" },
            "runners": [
              { "selectionId": 47999, "runnerName": "Rory McIlroy", "sortPriority": 1 },
              { "selectionId": 48000, "runnerName": "Scottie Scheffler", "sortPriority": 2 }
            ]
          }
        ]
        """.trimIndent()
        val markets = parseCatalogueMarkets(json)
        val m = markets.getValue("1.260239900")
        assertEquals("3M Open 2026", m.eventName)
        assertEquals(mapOf(47999L to "Rory McIlroy", 48000L to "Scottie Scheffler"), m.runners)
    }

    @Test
    fun `parseCatalogueMarkets defaults eventName when event missing`() {
        val json = """[ { "marketId": "1.1", "runners": [] } ]"""
        assertEquals("Golf Betting", parseCatalogueMarkets(json).getValue("1.1").eventName)
    }

    @Test
    fun `parseCatalogueMarkets skips entries without marketId and runners without names`() {
        val json = """
        [
          { "runners": [] },
          { "marketId": "1.2", "runners": [ { "selectionId": 5 }, { "runnerName": "X" } ] }
        ]
        """.trimIndent()
        val markets = parseCatalogueMarkets(json)
        assertEquals(setOf("1.2"), markets.keys)
        assertTrue(markets.getValue("1.2").runners.isEmpty())
    }

    @Test
    fun `parseCatalogueMarkets throws on non-array response`() {
        assertFailsWith<IllegalStateException> { parseCatalogueMarkets("""{"error":"x"}""") }
    }

    // --- parseBookMarkets ---
    @Test
    fun `parseBookMarkets extracts best lay per selection`() {
        val json = """
        [
          {
            "marketId": "1.260239900",
            "status": "OPEN",
            "runners": [
              {
                "selectionId": 47999,
                "ex": { "availableToLay": [ { "price": 8.4, "size": 120.0 }, { "price": 8.6, "size": 50.0 } ] }
              },
              { "selectionId": 48000, "ex": { "availableToLay": [] } }
            ]
          }
        ]
        """.trimIndent()
        val books = parseBookMarkets(json)
        val snap = books.getValue("1.260239900")
        assertEquals(MarketBookStatus.OPEN, snap.status)
        assertEquals(8.4, snap.layBySelectionId.getValue(47999L))
        assertNull(snap.layBySelectionId.getValue(48000L))
    }

    @Test
    fun `parseBookMarkets collapses non-OPEN status to OTHER with no prices`() {
        val json = """[ { "marketId": "1.1", "status": "SUSPENDED", "runners": [ { "selectionId": 1 } ] } ]"""
        val snap = parseBookMarkets(json).getValue("1.1")
        assertEquals(MarketBookStatus.OTHER, snap.status)
        assertTrue(snap.layBySelectionId.isEmpty())
    }

    @Test
    fun `parseBookMarkets treats non-primitive status as OTHER`() {
        val json = """[ { "marketId": "1.1", "status": {}, "runners": [ { "selectionId": 1 } ] } ]"""
        val snap = parseBookMarkets(json).getValue("1.1")
        assertEquals(MarketBookStatus.OTHER, snap.status)
        assertTrue(snap.layBySelectionId.isEmpty())
    }

    @Test
    fun `parseBookMarkets throws on non-array response`() {
        assertFailsWith<IllegalStateException> { parseBookMarkets("""{"error":"x"}""") }
    }
}
