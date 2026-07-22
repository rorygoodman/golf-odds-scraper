package com.golf.odds

import com.google.gson.Gson
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Entry point for the Golf Odds Scraper.
 *
 * Loads configuration, scrapes odds from bookmakers and Betfair,
 * and calculates E/W arbitrage opportunities.
 *
 * @param args Optional path to config file (defaults to "config.json")
 */
fun main(args: Array<String>) {
    val configPath = args.getOrNull(0) ?: "config.json"

    println("Golf Odds Scraper")
    println("=".repeat(80))

    val config = loadConfig(configPath)
    print("Logging in to Betfair API... ")
    val fetcher = createBetfairFetcher()
    println(if (fetcher != null) "OK" else "FAILED")
    val timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
    val eventJsons = mutableListOf<String>()

    config.events.forEach { event ->
        println("\nEvent: ${event.name}")
        println("-".repeat(40))

        val allEventOdds = mutableListOf<EventOdds>()

        event.pages.forEach { page ->
            print("Scraping ${page.bookmaker}... ")
            val eventOdds = scrapeEvent(page)
            if (eventOdds != null) {
                println("${eventOdds.players.size} players")
                allEventOdds.add(eventOdds)
            } else {
                println("FAILED")
            }
        }

        val betfairUrls = listOfNotNull(
            event.betfairLink.takeUnless { it.isNullOrBlank() },
            event.betfairTop5Link.takeUnless { it.isNullOrBlank() },
            event.betfairTop10Link.takeUnless { it.isNullOrBlank() },
        )
        var betfairOdds: Map<String, BetfairEventOdds> = emptyMap()
        if (fetcher != null && betfairUrls.isNotEmpty()) {
            betfairOdds = try {
                fetcher.fetchMarkets(betfairUrls)
            } catch (e: Exception) {
                System.err.println("Betfair fetch failed: ${e.message}")
                emptyMap()
            }
        }

        fun betfairMarket(label: String, link: String?): BetfairEventOdds? {
            if (link.isNullOrBlank()) return null
            val odds = betfairOdds[link]
            println("Betfair $label... " + (odds?.let { "${it.players.size} players" } ?: "FAILED"))
            return odds
        }

        val winnerMarketOdds = betfairMarket("Winner", event.betfairLink)

        if (!event.ew) {
            // Win-only mode: just compare bookmaker win odds vs Betfair lay
            if (winnerMarketOdds != null && allEventOdds.isNotEmpty()) {
                val opportunities = findWinOpportunities(allEventOdds, winnerMarketOdds)
                printWinOpportunities(opportunities)
                eventJsons.add(winOpportunitiesToEventJson(opportunities, event.name))
            } else {
                if (winnerMarketOdds == null) println("\nCannot calculate: need Betfair Winner market")
            }
        } else {
            // E/W mode: Top 5 and Top 10 markets, run LayableEWCalculator
            val top5MarketOdds = betfairMarket("Top 5", event.betfairTop5Link)
            val top10MarketOdds = betfairMarket("Top 10", event.betfairTop10Link)

            if (winnerMarketOdds != null && top5MarketOdds != null && top10MarketOdds != null && allEventOdds.isNotEmpty()) {
                val calculator = LayableEWCalculator(winnerMarketOdds, top10MarketOdds, top5MarketOdds)
                val opportunities = calculator.findArbitrageOpportunities(allEventOdds)
                printArbitrageOpportunities(opportunities)
                val scrapedBookmakers = event.pages.map { it.bookmaker.name }.distinct().sorted()
                eventJsons.add(opportunitiesToEventJson(opportunities, event.name, scrapedBookmakers))
            } else {
                val missing = listOfNotNull(
                    if (winnerMarketOdds == null) "Winner" else null,
                    if (top5MarketOdds == null) "Top 5" else null,
                    if (top10MarketOdds == null) "Top 10" else null
                )
                if (missing.isNotEmpty()) {
                    println("\nCannot calculate: need Betfair ${missing.joinToString(", ")} market(s)")
                }
            }
        }
    }

    if (eventJsons.isNotEmpty()) {
        val json = """{
  "timestamp": "$timestamp",
  "events": [
${eventJsons.joinToString(",\n")}
  ]
}"""
        File("data.json").writeText(json)
        println("\nJSON written to data.json")
    }
}

/**
 * Loads the scraper configuration from a JSON file.
 *
 * @param configPath Path to the configuration file
 * @return Parsed ScraperConfig
 * @throws IllegalArgumentException if config file not found
 */
fun loadConfig(configPath: String): ScraperConfig {
    val configFile = File(configPath)

    if (!configFile.exists()) {
        throw IllegalArgumentException("Config file not found: $configPath")
    }

    val gson = Gson()
    val jsonContent = configFile.readText()

    return gson.fromJson(jsonContent, ScraperConfig::class.java)
}

/**
 * Scrapes odds from a bookmaker page using the appropriate scraper.
 *
 * Catches exceptions to allow scraping to continue if one bookmaker fails.
 *
 * @param page The page configuration containing URL and bookmaker type
 * @return EventOdds if successful, null otherwise
 */
fun scrapeEvent(page: Page): EventOdds? {
    return try {
        when (page.bookmaker) {
            Bookmaker.LADBROKES -> LadbrokesScraper(page.url, page.places).scrape()
            Bookmaker.TEN_BET -> TenBetScraper(page.url, page.places).scrape()
            Bookmaker.BETFAIR -> null  // Betfair handled separately via betfairLink
            Bookmaker.PADDY_POWER -> PaddyPowerScraper(page.url, places = page.places, header = page.header).scrape()
            Bookmaker.BOYLESPORTS -> BoylesportsScraper(page.url, page.places).scrape()
            Bookmaker.SKYBET -> SkybetScraper(page.url, page.places).scrape()
            Bookmaker.BET365 -> Bet365Scraper(page.url, places = page.places, header = page.header).scrape()
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        null
    }
}

/**
 * Loads credentials from ~/.golf-scraper/credentials.json and logs in to
 * the Betfair API. Returns null (with a stderr message) if credentials are
 * missing or login fails — bookmaker scraping still runs; Betfair-dependent
 * calculations are skipped per market as before.
 */
fun createBetfairFetcher(): BetfairApiFetcher? {
    return try {
        val credentials = loadCredentials(defaultCredentialsPath())
        val client = BetfairClient(credentials.appKey)
        client.login(credentials.username, credentials.password)
        BetfairApiFetcher(client)
    } catch (e: Exception) {
        System.err.println("Betfair API unavailable: ${e.message}")
        null
    }
}
