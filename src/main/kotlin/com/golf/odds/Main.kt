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
    val allEventOdds = mutableListOf<EventOdds>()

    config.events.forEach { event ->
        println("\nEvent: ${event.name}")
        println("-".repeat(40))

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

        var winnerMarketOdds: BetfairEventOdds? = null
        if (!event.betfairLink.isNullOrBlank()) {
            print("Scraping Betfair Winner... ")
            try {
                val scraper = BetfairScraper(event.betfairLink)
                winnerMarketOdds = scraper.scrape()
                println("${winnerMarketOdds.players.size} players")
            } catch (e: Exception) {
                println("FAILED: ${e.message}")
            }
        }

        var top5MarketOdds: BetfairEventOdds? = null
        print("Scraping Betfair Top 5... ")
        try {
            val scraper = BetfairScraper(event.betfairTop5Link)
            top5MarketOdds = scraper.scrape()
            println("${top5MarketOdds.players.size} players")
        } catch (e: Exception) {
            println("FAILED: ${e.message}")
        }

        var top10MarketOdds: BetfairEventOdds? = null
        print("Scraping Betfair Top 10... ")
        try {
            val scraper = BetfairScraper(event.betfairTop10Link)
            top10MarketOdds = scraper.scrape()
            println("${top10MarketOdds.players.size} players")
        } catch (e: Exception) {
            println("FAILED: ${e.message}")
        }

        if (winnerMarketOdds != null && top5MarketOdds != null && top10MarketOdds != null && allEventOdds.isNotEmpty()) {
            val calculator = LayableEWCalculator(winnerMarketOdds, top10MarketOdds, top5MarketOdds)
            val opportunities = calculator.findArbitrageOpportunities(allEventOdds, event.pages)
            printArbitrageOpportunities(opportunities)

            // Write JSON for web frontend
            val timestamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
            val json = opportunitiesToJson(opportunities, timestamp, event.name)
            File("data.json").writeText(json)
            println("\nJSON written to data.json")
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
            Bookmaker.LADBROKES -> LadbrokesScraper(page.url).scrape()
            Bookmaker.TEN_BET -> TenBetScraper(page.url).scrape()
            Bookmaker.BETFAIR -> null  // Betfair handled separately via betfairLink
            Bookmaker.PADDY_POWER -> PaddyPowerScraper(page.url).scrape()
            Bookmaker.BOYLESPORTS -> BoylesportsScraper(page.url).scrape()
            Bookmaker.SKYBET -> SkybetScraper(page.url).scrape()
        }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        null
    }
}
