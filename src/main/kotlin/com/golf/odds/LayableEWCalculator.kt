package com.golf.odds

/**
 * Represents a player's layable E/W price calculated from Betfair markets.
 *
 * @property playerName The golfer's name
 * @property winLayPrice Lay price from Winner market
 * @property placeLayPrice Lay price from Top 10 market
 * @property combinedEWLayPrice Combined E/W equivalent lay price
 */
data class LayableEWPrice(
    val playerName: String,
    val winLayPrice: Double,
    val placeLayPrice: Double,
    val combinedEWLayPrice: Double
)

/**
 * Represents an E/W arbitrage opportunity between a bookmaker and Betfair.
 *
 * @property playerName The golfer's name
 * @property bookmaker The bookmaker offering the odds
 * @property bookmakerWinOdds Fractional win odds from bookmaker
 * @property bookmakerWinDecimal Decimal win odds from bookmaker
 * @property bookmakerPlaceDecimal Decimal place odds from bookmaker
 * @property betfairWinLay Betfair Winner market lay price
 * @property betfairPlaceLay Betfair Top 10 market lay price
 * @property winEdge Edge percentage on win part
 * @property placeEdge Edge percentage on place part
 * @property edgePercent Combined edge percentage
 */
data class EWArbitrageOpportunity(
    val playerName: String,
    val bookmaker: Bookmaker,
    val bookmakerWinOdds: String,
    val bookmakerWinDecimal: Double,
    val bookmakerPlaceDecimal: Double,
    val betfairWinLay: Double,
    val betfairPlaceLay: Double,
    val winEdge: Double,
    val placeEdge: Double,
    val edgePercent: Double
)

/**
 * Calculator for E/W arbitrage opportunities using Betfair lay prices.
 *
 * Combines Winner and Top 10 market lay prices to calculate equivalent
 * E/W lay prices, then compares against bookmaker odds to find arbitrage.
 *
 * @property winnerMarket Betfair Winner market prices
 * @property top10Market Betfair Top 10 Finish market prices
 */
class LayableEWCalculator(
    private val winnerMarket: BetfairEventOdds,
    private val top10Market: BetfairEventOdds
) {
    /**
     * Calculates layable E/W prices for all matched players.
     *
     * Matches players across Winner and Top 10 markets and calculates
     * a combined E/W equivalent lay price.
     *
     * @return List of LayableEWPrice sorted by combined price
     */
    fun calculateLayableEWPrices(): List<LayableEWPrice> {
        val winnerPrices = winnerMarket.players.associateBy { normalizePlayerName(it.playerName) }
        val top10Prices = top10Market.players.associateBy { normalizePlayerName(it.playerName) }

        val matchedPlayers = winnerPrices.keys.intersect(top10Prices.keys)

        return matchedPlayers.mapNotNull { normalizedName ->
            val winnerPlayer = winnerPrices[normalizedName] ?: return@mapNotNull null
            val top10Player = top10Prices[normalizedName] ?: return@mapNotNull null

            // Convert Top 10 lay price to equivalent win price for 1/5 E/W terms
            val impliedWinFromPlace = (top10Player.price - 1) * 5 + 1
            val combinedEW = (winnerPlayer.price + impliedWinFromPlace) / 2.0

            LayableEWPrice(
                playerName = winnerPlayer.playerName,
                winLayPrice = winnerPlayer.price,
                placeLayPrice = top10Player.price,
                combinedEWLayPrice = combinedEW
            )
        }.sortedBy { it.combinedEWLayPrice }
    }

    /**
     * Finds arbitrage opportunities by comparing bookmaker odds to Betfair lay prices.
     *
     * Calculates edge for both win and place parts of an E/W bet.
     *
     * @param bookmakerOdds List of EventOdds from bookmakers
     * @return List of EWArbitrageOpportunity sorted by edge (descending)
     */
    fun findArbitrageOpportunities(bookmakerOdds: List<EventOdds>): List<EWArbitrageOpportunity> {
        val layableEWPrices = calculateLayableEWPrices()
        val layablePriceMap = layableEWPrices.associateBy { normalizePlayerName(it.playerName) }

        val opportunities = mutableListOf<EWArbitrageOpportunity>()

        bookmakerOdds.forEach eventLoop@{ event ->
            val bookmaker = when {
                event.url.contains("ladbrokes.com") -> Bookmaker.LADBROKES
                event.url.contains("10bet.co.uk") -> Bookmaker.TEN_BET
                event.url.contains("paddypower.com") -> Bookmaker.PADDY_POWER
                event.url.contains("boylesports.com") -> Bookmaker.BOYLESPORTS
                else -> return@eventLoop
            }

            event.players.forEach playerLoop@{ player ->
                val normalizedName = normalizePlayerName(player.playerName)
                val layablePrice = layablePriceMap[normalizedName] ?: return@playerLoop

                val bmWin = player.decimalOdds
                val bmPlace = ((bmWin - 1) / 5) + 1

                val bfWinLay = layablePrice.winLayPrice
                val bfPlaceLay = layablePrice.placeLayPrice

                val winEdge = (bmWin / bfWinLay) - 1
                val placeEdge = (bmPlace / bfPlaceLay) - 1
                val combinedEdge = ((winEdge + placeEdge) / 2) * 100

                opportunities.add(
                    EWArbitrageOpportunity(
                        playerName = player.playerName,
                        bookmaker = bookmaker,
                        bookmakerWinOdds = player.odds,
                        bookmakerWinDecimal = bmWin,
                        bookmakerPlaceDecimal = bmPlace,
                        betfairWinLay = bfWinLay,
                        betfairPlaceLay = bfPlaceLay,
                        winEdge = winEdge * 100,
                        placeEdge = placeEdge * 100,
                        edgePercent = combinedEdge
                    )
                )
            }
        }

        return opportunities.sortedByDescending { it.edgePercent }
    }
}

/**
 * Prints arbitrage opportunities in a formatted table.
 *
 * Groups opportunities by player and highlights profitable bets in green.
 *
 * @param opportunities List of arbitrage opportunities to print
 */
fun printArbitrageOpportunities(opportunities: List<EWArbitrageOpportunity>) {
    println("\n" + "=".repeat(115))
    println("E/W COMPARISON: Bookmaker vs Betfair Lay")
    println("Edge = ((BM_Win/BF_Win) + (BM_Place/BF_Place)) / 2 - 1  |  BM_Place = ((BM_Win-1)/5)+1")
    println("=".repeat(115))

    if (opportunities.isEmpty()) {
        println("No players matched across bookmakers and Betfair.")
        println("=".repeat(115))
        return
    }

    val groupedByPlayer = opportunities
        .groupBy { normalizePlayerName(it.playerName) }
        .mapValues { (_, opps) -> opps.sortedByDescending { it.edgePercent } }
        .toList()
        .sortedByDescending { (_, opps) -> opps.first().edgePercent }

    val allProfitable = opportunities.filter { it.edgePercent > 0 }
    val playersWithProfitable = groupedByPlayer.filter { (_, opps) -> opps.any { it.edgePercent > 0 } }
    val playersWithMultipleProfitable = groupedByPlayer.filter { (_, opps) -> opps.count { it.edgePercent > 0 } > 1 }

    println("%-22s | %-11s | %7s | %7s | %7s | %7s | %7s | %7s | %7s".format(
        "Player", "Bookmaker", "BM Win", "BF Win", "Win%", "BM Plc", "BF Plc", "Plc%", "Edge%"
    ))
    println("-".repeat(115))

    groupedByPlayer.forEachIndexed { playerIndex, (_, opps) ->
        opps.forEachIndexed { index, opp ->
            val playerName = if (index == 0) opp.playerName.take(22) else ""
            val edgeStr = if (opp.edgePercent > 0) "\u001B[32m%+6.2f%%\u001B[0m" else "%+6.2f%%"
            println("%-22s | %-11s | %7.2f | %7.2f | %+6.1f%% | %7.2f | %7.2f | %+6.1f%% | $edgeStr".format(
                playerName, opp.bookmaker.name,
                opp.bookmakerWinDecimal, opp.betfairWinLay, opp.winEdge,
                opp.bookmakerPlaceDecimal, opp.betfairPlaceLay, opp.placeEdge,
                opp.edgePercent
            ))
        }
        if (playerIndex < groupedByPlayer.size - 1) {
            println("-".repeat(115))
        }
    }

    println("=".repeat(115))
    println("Total: ${groupedByPlayer.size} players | ${opportunities.size} combinations")
    println("Players with profitable bets: ${playersWithProfitable.size} | Total profitable bets: ${allProfitable.size}")

    if (playersWithMultipleProfitable.isNotEmpty()) {
        println("Players profitable at MULTIPLE bookmakers: ${playersWithMultipleProfitable.size}")
        playersWithMultipleProfitable.forEach { (_, opps) ->
            val profitableOpps = opps.filter { it.edgePercent > 0 }
            val bookmakers = profitableOpps.joinToString(", ") { "${it.bookmaker.name} (${String.format("%+.1f%%", it.edgePercent)})" }
            println("  ${profitableOpps.first().playerName}: $bookmakers")
        }
    }

    if (allProfitable.isNotEmpty()) {
        val avgEdge = allProfitable.map { it.edgePercent }.average()
        val maxEdge = allProfitable.maxOf { it.edgePercent }
        println("Profitable avg edge: %.2f%% | Max edge: %.2f%%".format(avgEdge, maxEdge))
    }
    println("=".repeat(115))
}
