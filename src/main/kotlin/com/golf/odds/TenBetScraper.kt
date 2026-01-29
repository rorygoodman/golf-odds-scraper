package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

class TenBetScraper(private val url: String) {
    private var driver: WebDriver? = null

    fun scrape(): EventOdds {
        try {
            initDriver()
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
        Thread.sleep(5000) // Wait longer for dynamic content to load
    }

    private fun clickShowAll() {
        try {
            // Scroll down to make sure the button is visible
            (driver as JavascriptExecutor).executeScript("window.scrollTo(0, document.body.scrollHeight);")
            Thread.sleep(1000)

            val wait = WebDriverWait(driver!!, Duration.ofSeconds(5))

            // Keep clicking "Show More" until there are no more to click
            var clickCount = 0
            val maxClicks = 20 // Safety limit

            while (clickCount < maxClicks) {
                try {
                    // Scroll to bottom again before each attempt
                    (driver as JavascriptExecutor).executeScript("window.scrollTo(0, document.body.scrollHeight);")
                    Thread.sleep(500)

                    // Try different possible selectors for "Show All" button
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
                                // Scroll to the button
                                (driver as JavascriptExecutor).executeScript("arguments[0].scrollIntoView(true);", button)
                                Thread.sleep(500)

                                // Use JavaScript click to avoid "click intercepted" errors
                                (driver as JavascriptExecutor).executeScript("arguments[0].click();", button)
                                Thread.sleep(1500) // Wait for new content to load
                                clickCount++
                                buttonClicked = true
                                println("Clicked 'Show All' button")
                                break
                            }
                        } catch (e: Exception) {
                            continue
                        }
                    }

                    if (!buttonClicked) {
                        // No more "Show More" buttons found
                        break
                    }
                } catch (e: Exception) {
                    // No more buttons to click
                    break
                }
            }

            if (clickCount > 0) {
                println("Expanded content")
            } else {
                println("No 'Show All' button found - all ${driver!!.findElements(By.cssSelector(".ta-infoText")).size} players may already be visible")
            }
        } catch (e: Exception) {
            println("Error clicking Show All button: ${e.message}")
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
            // Try different selectors for each-way terms
            val selectors = listOf(
                ".ta-EachWayTerms",
                "[class*='EachWay']",
                "[class*='eachway']"
            )

            var eachWayElement: org.openqa.selenium.WebElement? = null
            for (selector in selectors) {
                try {
                    eachWayElement = driver!!.findElement(By.cssSelector(selector))
                    println("Found each-way element with selector: $selector")
                    break
                } catch (e: Exception) {
                    continue
                }
            }

            if (eachWayElement == null) {
                // Try finding any element containing "each way" text
                val allElements = driver!!.findElements(By.xpath("//*[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'each way') or contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'e/w')]"))
                if (allElements.isNotEmpty()) {
                    eachWayElement = allElements.first()
                    println("Found each-way element via text search")
                } else {
                    println("No each-way terms found on page, using default: 1/4 odds, 5 places")
                    return EachWayTerms("1/4", 5)
                }
            }

            val text = eachWayElement?.text?.trim() ?: ""
            println("Each-way text: $text")

            // Extract the fraction (e.g., "1/4")
            val fractionRegex = Regex("""(\d+/\d+)""")
            val fractionMatch = fractionRegex.find(text)
            val placeOdds = fractionMatch?.value ?: "1/4"

            // Extract the number of places
            val placesRegex = Regex("""Places?\s+([\d-]+)""", RegexOption.IGNORE_CASE)
            val placesMatch = placesRegex.find(text)
            val numberOfPlaces = if (placesMatch != null) {
                placesMatch.groupValues[1].split("-").size
            } else {
                // Try alternate format like "5 places"
                val altPlacesRegex = Regex("""(\d+)\s+places?""", RegexOption.IGNORE_CASE)
                val altMatch = altPlacesRegex.find(text)
                altMatch?.groupValues?.get(1)?.toIntOrNull() ?: 5
            }

            println("Each-way terms: $placeOdds odds, $numberOfPlaces places")
            EachWayTerms(placeOdds, numberOfPlaces)
        } catch (e: Exception) {
            println("Could not extract each-way terms, using default: ${e.message}")
            // Default to common each-way terms for golf
            return EachWayTerms("1/4", 5)
        }
    }

    private fun extractPlayerOdds(eachWayTerms: EachWayTerms?): List<PlayerOdds> {
        val players = mutableListOf<PlayerOdds>()

        try {
            // The player names and odds are in separate parallel lists
            val infoTexts = driver!!.findElements(By.cssSelector(".ta-infoText"))
            val priceTexts = driver!!.findElements(By.cssSelector(".ta-price_text"))

            println("Found ${infoTexts.size} players and ${priceTexts.size} odds")

            // Match them up by index
            val pairCount = minOf(infoTexts.size, priceTexts.size)

            for (i in 0 until pairCount) {
                try {
                    val playerName = infoTexts[i].text.trim()
                    val odds = priceTexts[i].text.trim()

                    if (playerName.isNotBlank() && odds.isNotBlank()) {
                        val decimalOdds = parseOdds(odds) ?: continue
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
                    println("Error extracting player $i: ${e.message}")
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
