package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

/**
 * Represents a player's betting odds including win and place components.
 *
 * @property playerName The golfer's name
 * @property odds Fractional win odds string (e.g., "10/1")
 * @property decimalOdds Decimal win odds (e.g., 11.0)
 * @property placeOdds Fractional place odds string (e.g., "2/1")
 * @property placeDecimalOdds Decimal place odds (e.g., 3.0)
 */
data class PlayerOdds(
    val playerName: String,
    val odds: String,
    val decimalOdds: Double,
    val placeOdds: String,
    val placeDecimalOdds: Double
)

/**
 * Represents each-way betting terms offered by a bookmaker.
 *
 * @property placeOdds Fraction of win odds paid for places (e.g., "1/4", "1/5")
 * @property numberOfPlaces Number of finishing positions that count as places
 */
data class EachWayTerms(
    val placeOdds: String,
    val numberOfPlaces: Int
)

/**
 * Represents scraped odds data for a golf event from a bookmaker.
 *
 * @property eventName Name of the golf event
 * @property url Source URL that was scraped
 * @property players List of player odds
 * @property eachWayTerms Each-way terms if available
 * @property scrapedAt Timestamp when data was scraped
 */
data class EventOdds(
    val eventName: String,
    val url: String,
    val players: List<PlayerOdds>,
    val eachWayTerms: EachWayTerms?,
    val scrapedAt: String = java.time.LocalDateTime.now().toString()
)

/**
 * Scraper for extracting golf betting odds from Ladbrokes website.
 *
 * @property url The Ladbrokes event page URL to scrape
 */
class LadbrokesScraper(private val url: String) {
    private var driver: WebDriver? = null

    /**
     * Scrapes player odds from the Ladbrokes event page.
     *
     * @return EventOdds containing all scraped player data
     */
    fun scrape(): EventOdds {
        try {
            driver = createChromeDriver()
            driver!!.get(url)
            waitForPageLoad()
            clickShowAll()

            val eventName = extractEventName()
            val eachWayTerms = extractEachWayTerms()
            val players = extractPlayerOdds(eachWayTerms)

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

    /**
     * Waits for the page to fully load including dynamic content.
     */
    private fun waitForPageLoad() {
        val wait = WebDriverWait(driver!!, Duration.ofSeconds(15))
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")))
        Thread.sleep(3000)
    }

    /**
     * Clicks "Show All" button to expand the full player list.
     */
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
                    Thread.sleep(2000)
                    return
                } catch (e: Exception) {
                    continue
                }
            }
        } catch (e: Exception) {
            // Content may already be expanded
        }
    }

    /**
     * Extracts the event name from the page header.
     *
     * @return Event name or default value if not found
     */
    private fun extractEventName(): String {
        return try {
            val element = driver!!.findElement(By.cssSelector("h1"))
            element.text.trim()
        } catch (e: Exception) {
            "Unknown Event"
        }
    }

    /**
     * Extracts each-way terms from the page.
     *
     * @return EachWayTerms if found, null otherwise
     */
    private fun extractEachWayTerms(): EachWayTerms? {
        return try {
            val eachWayContainer = driver!!.findElement(By.cssSelector("[data-crlat='eachWayContainer']"))
            val text = eachWayContainer.text.trim()

            val fractionRegex = Regex("""(\d+/\d+)""")
            val fractionMatch = fractionRegex.find(text)
            val placeOdds = fractionMatch?.value ?: return null

            val placesRegex = Regex("""Places?\s+([\d-]+)""", RegexOption.IGNORE_CASE)
            val placesMatch = placesRegex.find(text)
            val numberOfPlaces = if (placesMatch != null) {
                placesMatch.groupValues[1].split("-").size
            } else {
                val altPlacesRegex = Regex("""(\d+)\s+places?""", RegexOption.IGNORE_CASE)
                val altMatch = altPlacesRegex.find(text)
                altMatch?.groupValues?.get(1)?.toIntOrNull() ?: return null
            }

            EachWayTerms(placeOdds, numberOfPlaces)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts all player odds from the page.
     *
     * @param eachWayTerms Each-way terms for calculating place odds
     * @return List of PlayerOdds, deduplicated by player name
     */
    private fun extractPlayerOdds(eachWayTerms: EachWayTerms?): List<PlayerOdds> {
        val players = mutableListOf<PlayerOdds>()

        try {
            val oddsContents = driver!!.findElements(By.cssSelector("[data-crlat='oddsContent']"))

            oddsContents.forEach { oddsContent ->
                try {
                    val playerNameElement = oddsContent.findElement(By.cssSelector("[data-crlat='oddsNames']"))
                    val playerName = playerNameElement.text.trim()

                    val oddsButton = oddsContent.findElement(By.cssSelector("button[data-crlat='betButton']"))
                    val odds = oddsButton.text.trim()

                    if (playerName.isNotBlank() && odds.isNotBlank()) {
                        val decimalOdds = parseOdds(odds) ?: return@forEach
                        val (placeOdds, placeDecimal) = calculatePlaceOdds(odds, eachWayTerms)

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
                    // Skip entries where name or odds can't be extracted
                }
            }
        } catch (e: Exception) {
            // Error extracting player odds
        }

        return players.distinctBy { it.playerName }
    }
}
