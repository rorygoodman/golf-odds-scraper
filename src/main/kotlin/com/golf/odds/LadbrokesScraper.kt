package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

data class PlayerOdds(
    val playerName: String,
    val odds: String,
    val decimalOdds: Double,
    val placeOdds: String,          // Fractional place odds (e.g., "7/8")
    val placeDecimalOdds: Double    // Decimal place odds (e.g., 1.875)
)

data class EachWayTerms(
    val placeOdds: String,      // e.g., "1/4"
    val numberOfPlaces: Int     // e.g., 5
)

data class EventOdds(
    val eventName: String,
    val url: String,
    val players: List<PlayerOdds>,
    val eachWayTerms: EachWayTerms?,
    val scrapedAt: String = java.time.LocalDateTime.now().toString()
)

class LadbrokesScraper(private val url: String) {
    private var driver: WebDriver? = null

    fun scrape(): EventOdds {
        try {
            initDriver()
            driver!!.get(url)
            waitForPageLoad()
            clickShowAll()

            val eventName = extractEventName()
            val eachWayTerms = extractEachWayTerms()
            val players = extractPlayerOdds()

            return EventOdds(
                eventName = eventName,
                url = url,
                players = players,
                eachWayTerms = eachWayTerms
            )
        } finally {
            driver?.quit()
        }
    }

    private fun initDriver() {
        val options = ChromeOptions().apply {
            addArguments("--headless=new")
            addArguments("--no-sandbox")
            addArguments("--disable-dev-shm-usage")
            addArguments("--disable-gpu")
            addArguments("--window-size=1920,1080")
            addArguments("--user-agent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }
        driver = ChromeDriver(options)
    }

    private fun waitForPageLoad() {
        val wait = WebDriverWait(driver!!, Duration.ofSeconds(15))
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")))
        Thread.sleep(3000) // Wait for dynamic content to load
    }

    private fun clickShowAll() {
        try {
            val wait = WebDriverWait(driver!!, Duration.ofSeconds(10))
            val possibleSelectors = listOf(
                "//button[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'show all')]",
                "//button[contains(@class, 'show-all')]"
            )

            for (selector in possibleSelectors) {
                try {
                    val button = wait.until(ExpectedConditions.elementToBeClickable(By.xpath(selector)))
                    button.click()
                    Thread.sleep(2000) // Wait for expansion
                    println("Clicked 'Show All' button")
                    return
                } catch (e: Exception) {
                    continue
                }
            }
        } catch (e: Exception) {
            println("Show All button not found - content may already be expanded")
        }
    }

    private fun extractEventName(): String {
        return try {
            val element = driver!!.findElement(By.cssSelector("h1"))
            element.text.trim()
        } catch (e: Exception) {
            "2026 US Masters"
        }
    }

    private fun extractEachWayTerms(): EachWayTerms? {
        return try {
            val eachWayContainer = driver!!.findElement(By.cssSelector("[data-crlat='eachWayContainer']"))
            val text = eachWayContainer.text.trim()

            // Expected format: "EW: 1/4 Odds - Places 1-2-3-4-5" or similar
            // Extract the fraction (e.g., "1/4")
            val fractionRegex = Regex("""(\d+/\d+)""")
            val fractionMatch = fractionRegex.find(text)
            val placeOdds = fractionMatch?.value ?: return null

            // Extract the number of places
            // Look for pattern like "Places 1-2-3-4-5" and count the places
            val placesRegex = Regex("""Places?\s+([\d-]+)""", RegexOption.IGNORE_CASE)
            val placesMatch = placesRegex.find(text)
            val numberOfPlaces = if (placesMatch != null) {
                // Count the numbers in the range (e.g., "1-2-3-4-5" = 5 places)
                placesMatch.groupValues[1].split("-").size
            } else {
                // Try alternate format like "5 places"
                val altPlacesRegex = Regex("""(\d+)\s+places?""", RegexOption.IGNORE_CASE)
                val altMatch = altPlacesRegex.find(text)
                altMatch?.groupValues?.get(1)?.toIntOrNull() ?: return null
            }

            println("Each-way terms: $placeOdds odds, $numberOfPlaces places")
            EachWayTerms(placeOdds, numberOfPlaces)
        } catch (e: Exception) {
            println("Could not extract each-way terms: ${e.message}")
            null
        }
    }

    private fun extractPlayerOdds(): List<PlayerOdds> {
        val players = mutableListOf<PlayerOdds>()

        try {
            // Find all outcome containers using the data-crlat attribute
            val oddsContents = driver!!.findElements(By.cssSelector("[data-crlat='oddsContent']"))
            println("Found ${oddsContents.size} odds content sections")

            // Get each-way terms for place odds calculation
            val eachWayTerms = extractEachWayTerms()

            oddsContents.forEach { oddsContent ->
                try {
                    // Find the player name element
                    val playerNameElement = oddsContent.findElement(By.cssSelector("[data-crlat='oddsNames']"))
                    val playerName = playerNameElement.text.trim()

                    // Find the odds button
                    val oddsButton = oddsContent.findElement(By.cssSelector("button[data-crlat='betButton']"))
                    val odds = oddsButton.text.trim()

                    if (playerName.isNotBlank() && odds.isNotBlank()) {
                        val decimalOdds = parseOdds(odds) ?: return@forEach
                        val (placeOdds, placeDecimal) = calculatePlaceOdds(odds, eachWayTerms)

                        // Only add if we have valid place odds
                        if (placeOdds != null && placeDecimal != null) {
                            players.add(
                                PlayerOdds(
                                    playerName = playerName,
                                    odds = odds,
                                    decimalOdds = decimalOdds,
                                    placeOdds = placeOdds,
                                    placeDecimalOdds = placeDecimal
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Skip this entry if we can't extract both name and odds
                }
            }

            println("Successfully extracted ${players.size} players")
        } catch (e: Exception) {
            println("Error extracting player odds: ${e.message}")
        }

        return players
    }

    private fun calculatePlaceOdds(winOdds: String, eachWayTerms: EachWayTerms?): Pair<String?, Double?> {
        if (eachWayTerms == null || !winOdds.contains("/")) {
            return Pair(null, null)
        }

        try {
            // Parse win odds (e.g., "7/2")
            val winParts = winOdds.split("/")
            val winNumerator = winParts[0].toInt()
            val winDenominator = winParts[1].toInt()

            // Parse place fraction (e.g., "1/4")
            val placeParts = eachWayTerms.placeOdds.split("/")
            val placeFractionNum = placeParts[0].toInt()
            val placeFractionDen = placeParts[1].toInt()

            // Calculate place odds: (winNumerator * placeFractionNum) / (winDenominator * placeFractionDen)
            val placeNumerator = winNumerator * placeFractionNum
            val placeDenominator = winDenominator * placeFractionDen

            // Simplify the fraction
            val gcd = gcd(placeNumerator, placeDenominator)
            val simplifiedNum = placeNumerator / gcd
            val simplifiedDen = placeDenominator / gcd

            val placeFractional = "$simplifiedNum/$simplifiedDen"
            val placeDecimal = (simplifiedNum.toDouble() / simplifiedDen.toDouble()) + 1.0

            return Pair(placeFractional, placeDecimal)
        } catch (e: Exception) {
            return Pair(null, null)
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        return if (b == 0) a else gcd(b, a % b)
    }

    private fun parseOdds(oddsString: String): Double? {
        return try {
            when {
                // Fractional odds (e.g., "5/1", "11/2")
                oddsString.contains("/") -> {
                    val parts = oddsString.split("/")
                    val numerator = parts[0].toDouble()
                    val denominator = parts[1].toDouble()
                    (numerator / denominator) + 1.0
                }
                // Decimal odds (e.g., "6.0", "5.5")
                else -> oddsString.toDouble()
            }
        } catch (e: Exception) {
            null
        }
    }
}

fun printEventOdds(eventOdds: EventOdds) {
    println("\nEvent: ${eventOdds.eventName}")
    println("URL: ${eventOdds.url}")
    println("Scraped at: ${eventOdds.scrapedAt}")

    if (eventOdds.eachWayTerms != null) {
        println("\nEach-Way Terms:")
        println("  Place Odds: ${eventOdds.eachWayTerms.placeOdds} of win odds")
        println("  Paying Places: ${eventOdds.eachWayTerms.numberOfPlaces}")
    }

    println("\nPlayer Odds (${eventOdds.players.size} players):")
    println("-".repeat(80))

    eventOdds.players
        .sortedBy { it.decimalOdds }
        .forEach { player ->
            val decimalOddsStr = "%.2f".format(player.decimalOdds)
            val placeInfo = " | Place: ${player.placeOdds.padStart(6)} (%.2f)".format(player.placeDecimalOdds)
            println("${player.playerName.padEnd(30)} ${player.odds.padStart(8)} ($decimalOddsStr)$placeInfo")
        }

    if (eventOdds.players.isEmpty()) {
        println("\n⚠️  No players found! The page structure might be different than expected.")
    }
}
