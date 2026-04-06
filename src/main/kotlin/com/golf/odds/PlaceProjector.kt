package com.golf.odds

/**
 * Projects implied Betfair lay prices for arbitrary place counts (5-15)
 * using linear interpolation/extrapolation from Top 5 and Top 10 markets.
 *
 * Given two data points — Top 5 and Top 10 lay prices — we derive a
 * marginal probability per additional place and project cumulative
 * probabilities for any N in [5, 15].
 */
class PlaceProjector(
    top5Market: BetfairEventOdds,
    top10Market: BetfairEventOdds
) {
    private val top5Prices: Map<String, PlayerLayPrice> =
        top5Market.players.associateBy { normalizePlayerName(it.playerName) }

    private val top10Prices: Map<String, PlayerLayPrice> =
        top10Market.players.associateBy { normalizePlayerName(it.playerName) }

    /**
     * Returns projected lay price for [places] places (5-15) for a given player.
     *
     * @param playerName Player name (will be normalized for matching)
     * @param places Number of E/W places (5-15)
     * @return Projected lay price, or null if player not found in both markets
     */
    fun projectLayPrice(playerName: String, places: Int): Double? {
        require(places in 5..15) { "Places must be between 5 and 15, got $places" }

        val normalized = normalizePlayerName(playerName)

        // Exact anchors — only need the single relevant market
        if (places == 5) return top5Prices[normalized]?.price
        if (places == 10) return top10Prices[normalized]?.price

        // Interpolation/extrapolation — both anchors required
        val t5 = top5Prices[normalized]?.price ?: return null
        val t10 = top10Prices[normalized]?.price ?: return null

        val pTop5 = 1.0 / t5
        val pTop10 = 1.0 / t10
        val marginalPerPlace = (pTop10 - pTop5) / 5.0

        val pTopN = if (places < 10) {
            pTop5 + (places - 5) * marginalPerPlace
        } else {
            pTop10 + (places - 10) * marginalPerPlace
        }

        if (pTopN <= 0) return null
        return 1.0 / pTopN
    }
}
