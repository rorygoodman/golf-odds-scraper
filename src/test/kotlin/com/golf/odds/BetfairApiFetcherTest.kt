package com.golf.odds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BetfairApiFetcherTest {
    private val url = "https://www.betfair.com/exchange/plus/golf/market/1.260239900"
    private val catalogue = CatalogueMarket(
        marketId = "1.260239900",
        eventName = "3M Open 2026",
        runners = linkedMapOf(1L to "Rory McIlroy", 2L to "Scottie Scheffler", 3L to "Shane Lowry"),
    )

    @Test
    fun `buildEventOdds joins names with lay prices and skips missing lays`() {
        val book = MarketBookSnapshot(
            MarketBookStatus.OPEN,
            mapOf(1L to 8.4, 2L to null, 3L to 25.0),
        )
        val odds = buildEventOdds(url, catalogue, book)!!
        assertEquals("3M Open 2026", odds.eventName)
        assertEquals(url, odds.url)
        assertEquals(
            listOf(PlayerLayPrice("Rory McIlroy", 8.4), PlayerLayPrice("Shane Lowry", 25.0)),
            odds.players,
        )
    }

    @Test
    fun `buildEventOdds skips lay prices at or below 1_0`() {
        val book = MarketBookSnapshot(MarketBookStatus.OPEN, mapOf(1L to 1.0, 2L to 1.01))
        val odds = buildEventOdds(url, catalogue, book)!!
        assertEquals(listOf(PlayerLayPrice("Scottie Scheffler", 1.01)), odds.players)
    }

    @Test
    fun `buildEventOdds skips selections absent from the book`() {
        val book = MarketBookSnapshot(MarketBookStatus.OPEN, mapOf(1L to 8.4))
        val odds = buildEventOdds(url, catalogue, book)!!
        assertEquals(listOf(PlayerLayPrice("Rory McIlroy", 8.4)), odds.players)
    }

    @Test
    fun `buildEventOdds returns null when market not OPEN`() {
        val book = MarketBookSnapshot(MarketBookStatus.OTHER, emptyMap())
        assertNull(buildEventOdds(url, catalogue, book))
    }

    @Test
    fun `buildEventOdds returns null when catalogue or book missing`() {
        val book = MarketBookSnapshot(MarketBookStatus.OPEN, mapOf(1L to 8.4))
        assertNull(buildEventOdds(url, null, book))
        assertNull(buildEventOdds(url, catalogue, null))
    }
}
