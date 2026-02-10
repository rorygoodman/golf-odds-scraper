package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

/**
 * Scraper for extracting golf betting odds from 10Bet website.
 *
 * @property url The 10Bet event page URL to scrape
 */
class TenBetScraper(private val url: String) {
    private var driver: WebDriver? = null

    /**
     * Scrapes player odds from the 10Bet event page.
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
        Thread.sleep(5000)
    }

    /**
     * Clicks all "Show All" buttons to expand the full player list.
     */
    private fun clickShowAll() {
        try {
            (driver as JavascriptExecutor).executeScript("window.scrollTo(0, document.body.scrollHeight);")
            Thread.sleep(1000)

            var clickCount = 0

            while (clickCount < 20) {
                try {
                    (driver as JavascriptExecutor).executeScript("window.scrollTo(0, document.body.scrollHeight);")
                    Thread.sleep(500)

                    val possibleSelectors = listOf(
                        "//div[contains(@class, 'ta-Button') and contains(., 'Show All')]",
                        "//div[contains(@class, 'ta-Button')][.//div[contains(@class, 'ta-imageButtonLabel') and contains(text(), 'Show All')]]",
                        "//div[contains(@class, 'ta-FlexPane') and contains(., 'Show All')]",
                        "//div[contains(@class, 'ta-imageButtonLabel') and contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'show all')]/.."
                    )

                    var buttonClicked = false
                    for (selector in possibleSelectors) {
                        try {
                            val buttons = driver!!.findElements(By.xpath(selector))
                            if (buttons.isNotEmpty()) {
                                val button = buttons.first()
                                (driver as JavascriptExecutor).executeScript("arguments[0].scrollIntoView(true);", button)
                                Thread.sleep(500)
                                (driver as JavascriptExecutor).executeScript("arguments[0].click();", button)
                                Thread.sleep(1500)
                                clickCount++
                                buttonClicked = true
                                break
                            }
                        } catch (e: Exception) {
                            continue
                        }
                    }

                    if (!buttonClicked) break
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            // Content may already be expanded
        }
    }

    /**
     * Extracts the event name from the page header.
     *
     * @return Event name or "Unknown Event" if not found
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
     * @return EachWayTerms if found, defaults to 1/4 odds and 5 places
     */
    private fun extractEachWayTerms(): EachWayTerms? {
        return try {
            val selectors = listOf(
                ".ta-EachWayTerms",
                "[class*='EachWay']",
                "[class*='eachway']"
            )

            var eachWayElement: org.openqa.selenium.WebElement? = null
            for (selector in selectors) {
                try {
                    eachWayElement = driver!!.findElement(By.cssSelector(selector))
                    break
                } catch (e: Exception) {
                    continue
                }
            }

            if (eachWayElement == null) {
                val allElements = driver!!.findElements(By.xpath("//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'each way') or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'e/w')]"))
                if (allElements.isNotEmpty()) {
                    eachWayElement = allElements.first()
                } else {
                    return EachWayTerms("1/4", 5)
                }
            }

            val text = eachWayElement?.text?.trim() ?: ""

            val fractionRegex = Regex("""(\d+/\d+)""")
            val fractionMatch = fractionRegex.find(text)
            val placeOdds = fractionMatch?.value ?: "1/4"

            val placesRegex = Regex("""Places?\s+([\d-]+)""", RegexOption.IGNORE_CASE)
            val placesMatch = placesRegex.find(text)
            val numberOfPlaces = if (placesMatch != null) {
                placesMatch.groupValues[1].split("-").size
            } else {
                val altPlacesRegex = Regex("""(\d+)\s+places?""", RegexOption.IGNORE_CASE)
                val altMatch = altPlacesRegex.find(text)
                altMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5
            }

            EachWayTerms(placeOdds, numberOfPlaces)
        } catch (e: Exception) {
            EachWayTerms("1/4", 5)
        }
    }

    /**
     * Extracts all player odds from the page.
     *
     * @param eachWayTerms Each-way terms for calculating place odds
     * @return List of PlayerOdds
     */
    private fun extractPlayerOdds(eachWayTerms: EachWayTerms?): List<PlayerOdds> {
        val players = mutableListOf<PlayerOdds>()

        try {
            val infoTexts = driver!!.findElements(By.cssSelector(".ta-infoText"))
            val priceTexts = driver!!.findElements(By.cssSelector(".ta-price_text"))

            val pairCount = minOf(infoTexts.size, priceTexts.size)

            for (i in 0 until pairCount) {
                try {
                    val playerName = infoTexts[i].text.trim()
                    val odds = priceTexts[i].text.trim()

                    if (playerName.isNotBlank() && odds.isNotBlank()) {
                        val decimalOdds = parseOdds(odds) ?: continue
                        val (placeOdds, placeDecimal) = calculatePlaceOdds(odds, eachWayTerms)

                        if (placeOdds != null && placeDecimal != null) {
                            players.add(PlayerOdds(playerName, odds, decimalOdds, placeOdds, placeDecimal))
                        }
                    }
                } catch (e: Exception) {
                    // Skip entries that can't be extracted
                }
            }
        } catch (e: Exception) {
            // Error extracting player odds
        }

        return players
    }
}
