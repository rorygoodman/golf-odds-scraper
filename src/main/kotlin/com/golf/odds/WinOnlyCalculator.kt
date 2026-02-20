package com.golf.odds

data class WinArbitrageOpportunity(
    val playerName: String,
    val bookmaker: Bookmaker,
    val bookmakerWinOdds: String,
    val bookmakerWinDecimal: Double,
    val betfairWinLay: Double,
    val winEdge: Double
)

fun findWinOpportunities(
    bookmakerOdds: List<EventOdds>,
    betfairWin: BetfairEventOdds
): List<WinArbitrageOpportunity> {
    val winPrices = betfairWin.players.associateBy { normalizePlayerName(it.playerName) }
    val opportunities = mutableListOf<WinArbitrageOpportunity>()

    bookmakerOdds.forEach eventLoop@{ event ->
        val bookmaker = when {
            event.url.contains("ladbrokes.com") -> Bookmaker.LADBROKES
            event.url.contains("10bet.co.uk") -> Bookmaker.TEN_BET
            event.url.contains("paddypower.com") -> Bookmaker.PADDY_POWER
            event.url.contains("boylesports.com") -> Bookmaker.BOYLESPORTS
            event.url.contains("skybet.com") -> Bookmaker.SKYBET
            else -> return@eventLoop
        }

        event.players.forEach playerLoop@{ player ->
            val normalizedName = normalizePlayerName(player.playerName)
            val betfairPlayer = winPrices[normalizedName] ?: return@playerLoop

            val bmWin = player.decimalOdds
            val bfWinLay = betfairPlayer.price
            val edge = (bmWin / bfWinLay - 1) * 100

            opportunities.add(
                WinArbitrageOpportunity(
                    playerName = player.playerName,
                    bookmaker = bookmaker,
                    bookmakerWinOdds = player.odds,
                    bookmakerWinDecimal = bmWin,
                    betfairWinLay = bfWinLay,
                    winEdge = edge
                )
            )
        }
    }

    return opportunities.sortedByDescending { it.winEdge }
}

fun printWinOpportunities(opportunities: List<WinArbitrageOpportunity>) {
    val width = 80
    println("\n" + "=".repeat(width))
    println("WIN COMPARISON: Bookmaker vs Betfair Lay")
    println("Edge = (BM_Win / BF_Win - 1) * 100")
    println("=".repeat(width))

    if (opportunities.isEmpty()) {
        println("No players matched across bookmakers and Betfair.")
        println("=".repeat(width))
        return
    }

    val groupedByPlayer = opportunities
        .groupBy { normalizePlayerName(it.playerName) }
        .mapValues { (_, opps) -> opps.sortedByDescending { it.winEdge } }
        .toList()
        .sortedByDescending { (_, opps) -> opps.first().winEdge }

    val allProfitable = opportunities.filter { it.winEdge > 0 }

    println("%-22s | %-11s | %7s | %7s | %7s".format(
        "Player", "Bookmaker", "BM Win", "BF Win", "Edge%"
    ))
    println("-".repeat(width))

    groupedByPlayer.forEachIndexed { playerIndex, (_, opps) ->
        opps.forEachIndexed { index, opp ->
            val playerName = if (index == 0) opp.playerName.take(22) else ""
            val edgeStr = if (opp.winEdge > 0) "\u001B[32m%+6.2f%%\u001B[0m" else "%+6.2f%%"
            println("%-22s | %-11s | %7.2f | %7.2f | $edgeStr".format(
                playerName, opp.bookmaker.name,
                opp.bookmakerWinDecimal, opp.betfairWinLay,
                opp.winEdge
            ))
        }
        if (playerIndex < groupedByPlayer.size - 1) {
            println("-".repeat(width))
        }
    }

    println("=".repeat(width))
    println("Total: ${groupedByPlayer.size} players | ${opportunities.size} combinations")
    println("Profitable: ${allProfitable.size}")
    if (allProfitable.isNotEmpty()) {
        val avg = allProfitable.map { it.winEdge }.average()
        val max = allProfitable.maxOf { it.winEdge }
        println("Avg edge: %.2f%% | Max edge: %.2f%%".format(avg, max))
    }
    println("=".repeat(width))
}

fun winOpportunitiesToJson(
    opportunities: List<WinArbitrageOpportunity>,
    timestamp: String,
    eventName: String
): String {
    val bookmakers = opportunities.map { it.bookmaker.name }.distinct().sorted()

    val rows = opportunities.map { opp ->
        """    {
      "player": "${opp.playerName.replace("\"", "\\\"")}",
      "bookmaker": "${opp.bookmaker.name}",
      "bmWinOdds": "${opp.bookmakerWinOdds}",
      "bmWin": ${String.format("%.2f", opp.bookmakerWinDecimal)},
      "bfWin": ${String.format("%.2f", opp.betfairWinLay)},
      "edge": ${String.format("%.2f", opp.winEdge)}
    }"""
    }

    return """{
  "timestamp": "$timestamp",
  "eventName": "${eventName.replace("\"", "\\\"")}",
  "mode": "WIN",
  "bookmakers": [${bookmakers.joinToString(", ") { "\"$it\"" }}],
  "opportunities": [
${rows.joinToString(",\n")}
  ]
}"""
}
