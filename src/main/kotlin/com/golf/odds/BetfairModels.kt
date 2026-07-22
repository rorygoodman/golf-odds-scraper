package com.golf.odds

/**
 * Represents a player's lay price from Betfair exchange.
 *
 * @property playerName The golfer's name
 * @property price The lay price (decimal odds)
 */
data class PlayerLayPrice(
    val playerName: String,
    val price: Double
)

/**
 * Represents lay prices for one Betfair golf market.
 *
 * @property eventName Name of the golf event
 * @property url Source URL from the config
 * @property players List of player lay prices
 * @property scrapedAt Timestamp when data was fetched
 */
data class BetfairEventOdds(
    val eventName: String,
    val url: String,
    val players: List<PlayerLayPrice>,
    val scrapedAt: String = java.time.LocalDateTime.now().toString()
)
