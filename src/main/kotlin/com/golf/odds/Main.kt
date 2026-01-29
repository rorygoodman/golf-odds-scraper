package com.golf.odds

import com.google.gson.Gson
import java.io.File

fun main(args: Array<String>) {
    val configPath = args.getOrNull(0) ?: "config.json"

    println("Golf Odds Scraper")
    println("=".repeat(80))
    println("Loading configuration from: $configPath\n")

    // Load configuration
    val config = loadConfig(configPath)

    println("Found ${config.events.size} event(s) to scrape\n")

    // Collect all odds for comparison
    val allEventOdds = mutableListOf<EventOdds>()

    // Process each event
    config.events.forEach { event ->
        println("\n" + "=".repeat(80))
        println("Event: ${event.name}")
        println("=".repeat(80))

        event.pages.forEach { page ->
            println("\nScraping from ${page.bookmaker}...")
            println("URL: ${page.url}")

            val eventOdds = scrapeEvent(page)

            if (eventOdds != null) {
                printEventOdds(eventOdds)
                allEventOdds.add(eventOdds)
            } else {
                println("⚠️  Failed to scrape odds from ${page.bookmaker}")
            }
        }

        // Scrape Betfair lay prices if available
        if (!event.betfairLink.isNullOrBlank()) {
            println("\nScraping Betfair lay prices...")
            println("URL: ${event.betfairLink}")

            try {
                val scraper = BetfairScraper(event.betfairLink)
                val betfairOdds = scraper.scrape()
                printBetfairEventOdds(betfairOdds)
            } catch (e: Exception) {
                println("⚠️  Failed to scrape Betfair lay prices: ${e.message}")
            }
        }
    }

    // Print comparison across all bookmakers
    if (allEventOdds.size > 1) {
        val comparisons = aggregateOdds(allEventOdds)
        printOddsComparison(comparisons)
    }
}

fun loadConfig(configPath: String): ScraperConfig {
    val configFile = File(configPath)

    if (!configFile.exists()) {
        throw IllegalArgumentException("Config file not found: $configPath")
    }

    val gson = Gson()
    val jsonContent = configFile.readText()

    return gson.fromJson(jsonContent, ScraperConfig::class.java)
}

fun scrapeEvent(page: Page): EventOdds? {
    return when (page.bookmaker) {
        Bookmaker.LADBROKES -> {
            val scraper = LadbrokesScraper(page.url)
            scraper.scrape()
        }
        Bookmaker.TEN_BET -> {
            val scraper = TenBetScraper(page.url)
            scraper.scrape()
        }
        Bookmaker.BETFAIR -> {
            // Betfair is handled separately via event.betfairLink
            null
        }
        Bookmaker.PADDY_POWER -> {
            val scraper = PaddyPowerScraper(page.url)
            scraper.scrape()
        }
    }
}
