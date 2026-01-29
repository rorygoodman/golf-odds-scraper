package com.golf.odds

data class BookmakerOdds(
    val bookmaker: Bookmaker,
    val winOdds: String,
    val winDecimal: Double,
    val placeOdds: String,
    val placeDecimal: Double
)

data class PlayerComparison(
    val playerName: String,
    val bookmakerOdds: List<BookmakerOdds>
) {
    fun getBestWinOdds(): BookmakerOdds? = bookmakerOdds.maxByOrNull { it.winDecimal }
    fun getBestPlaceOdds(): BookmakerOdds? = bookmakerOdds.maxByOrNull { it.placeDecimal }
}

fun aggregateOdds(events: List<EventOdds>): List<PlayerComparison> {
    // Group all players across bookmakers
    val playerMap = mutableMapOf<String, MutableList<BookmakerOdds>>()

    events.forEach { event ->
        // Determine bookmaker from URL
        val bookmaker = when {
            event.url.contains("ladbrokes.com") -> Bookmaker.LADBROKES
            event.url.contains("10bet.co.uk") -> Bookmaker.TEN_BET
            event.url.contains("paddypower.com") -> Bookmaker.PADDY_POWER
            else -> return@forEach
        }

        event.players.forEach { player ->
            val normalizedName = normalizePlayerName(player.playerName)

            if (!playerMap.containsKey(normalizedName)) {
                playerMap[normalizedName] = mutableListOf()
            }

            playerMap[normalizedName]!!.add(
                BookmakerOdds(
                    bookmaker = bookmaker,
                    winOdds = player.odds,
                    winDecimal = player.decimalOdds,
                    placeOdds = player.placeOdds,
                    placeDecimal = player.placeDecimalOdds
                )
            )
        }
    }

    // Convert to PlayerComparison objects and sort by best win odds
    return playerMap.map { (name, odds) ->
        PlayerComparison(name, odds)
    }.sortedBy { it.getBestWinOdds()?.winDecimal ?: Double.MAX_VALUE }
}

fun normalizePlayerName(name: String): String {
    // Remove extra whitespace and normalize case for matching
    return name.trim().replace(Regex("\\s+"), " ")
}

fun printOddsComparison(comparisons: List<PlayerComparison>) {
    println("\n" + "=".repeat(120))
    println("ODDS COMPARISON ACROSS BOOKMAKERS")
    println("=".repeat(120))
    println("\nPlayers with odds from multiple bookmakers:")
    println("Bold = Best available odds")
    println("-".repeat(120))

    // Print header
    println("%-30s | %-35s | %-35s".format(
        "Player",
        "Win Odds",
        "Place Odds"
    ))
    println("-".repeat(120))

    comparisons.forEach { comparison ->
        if (comparison.bookmakerOdds.size > 1) {
            printPlayerComparison(comparison)
        }
    }

    println("\n" + "=".repeat(120))
    println("ALL PLAYERS (including those on single bookmaker)")
    println("=".repeat(120))

    comparisons.forEach { comparison ->
        printPlayerComparison(comparison)
    }
}

private fun printPlayerComparison(comparison: PlayerComparison) {
    val bestWin = comparison.getBestWinOdds()
    val bestPlace = comparison.getBestPlaceOdds()

    println("%-30s".format(comparison.playerName))

    comparison.bookmakerOdds.sortedBy { it.bookmaker.name }.forEach { odds ->
        val isBestWin = odds.bookmaker == bestWin?.bookmaker &&
                        odds.winDecimal == bestWin.winDecimal
        val isBestPlace = odds.bookmaker == bestPlace?.bookmaker &&
                          odds.placeDecimal == bestPlace.placeDecimal

        val winOddsStr = formatOdds(odds.winOdds, odds.winDecimal, isBestWin)
        val placeOddsStr = formatOdds(odds.placeOdds, odds.placeDecimal, isBestPlace)

        println("  %-28s | %-35s | %-35s".format(
            odds.bookmaker.name,
            winOddsStr,
            placeOddsStr
        ))
    }
    println()
}

private fun formatOdds(fractional: String, decimal: Double, isBest: Boolean): String {
    val oddsStr = "$fractional (%.2f)".format(decimal)
    return if (isBest && fractional.isNotEmpty()) {
        "\u001B[1m$oddsStr\u001B[0m"  // Bold ANSI escape code
    } else {
        oddsStr
    }
}
