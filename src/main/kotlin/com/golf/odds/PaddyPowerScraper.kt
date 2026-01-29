package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

class PaddyPowerScraper(private val url: String) {
    private var driver: WebDriver? = null

    fun scrape(): EventOdds {
        try {
            initDriver()
            driver!!.get(url)
            waitForPageLoad()
            clickShowMore()

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
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("p.outright-item__runner-name")))
        Thread.sleep(3000)
    }

    private fun clickShowMore() {
        // Click all "Show more" links to expand the full player list
        try {
            val js = driver as JavascriptExecutor
            var clicked = true
            while (clicked) {
                clicked = js.executeScript("""
                    var links = document.querySelectorAll('a.link__container--with-divider');
                    var found = false;
                    for (var i = 0; i < links.length; i++) {
                        if (links[i].textContent.trim().toLowerCase().includes('show more')) {
                            links[i].click();
                            found = true;
                        }
                    }
                    return found;
                """) as Boolean
                if (clicked) Thread.sleep(1000)
            }
        } catch (e: Exception) {
            println("Show more button not found - content may already be expanded")
        }
    }

    private fun extractEventName(): String {
        return try {
            val element = driver!!.findElement(By.cssSelector("h1"))
            element.text.trim()
        } catch (e: Exception) {
            "Unknown Event"
        }
    }

    private fun extractEachWayTerms(): EachWayTerms? {
        return try {
            val ewElement = driver!!.findElement(By.cssSelector("span.label-value__value"))
            val text = ewElement.text.trim()
            // Expected: "Each Way: 1/4 Odds, 5 Places"
            val fractionRegex = Regex("""(\d+/\d+)\s*Odds""", RegexOption.IGNORE_CASE)
            val placesRegex = Regex("""(\d+)\s*Places?""", RegexOption.IGNORE_CASE)

            val fraction = fractionRegex.find(text)?.groupValues?.get(1) ?: return null
            val places = placesRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null

            println("Each-way terms: $fraction odds, $places places")
            EachWayTerms(fraction, places)
        } catch (e: Exception) {
            println("Could not extract each-way terms: ${e.message}")
            null
        }
    }

    private fun extractPlayerOdds(eachWayTerms: EachWayTerms?): List<PlayerOdds> {
        val players = mutableListOf<PlayerOdds>()

        try {
            val items = driver!!.findElements(By.cssSelector("div.outright-item"))
            println("Found ${items.size} outright items")

            for (item in items) {
                try {
                    val nameEl = item.findElement(By.cssSelector("p.outright-item__runner-name"))
                    val playerName = nameEl.text.trim()

                    val oddsEl = item.findElement(By.cssSelector("span.btn-odds__label"))
                    val odds = oddsEl.text.trim()

                    if (playerName.isNotBlank() && odds.isNotBlank()) {
                        val decimalOdds = parseOdds(odds) ?: continue
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
                    // Skip entries missing name or odds
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

        return try {
            val winParts = winOdds.split("/")
            val winNumerator = winParts[0].toInt()
            val winDenominator = winParts[1].toInt()

            val placeParts = eachWayTerms.placeOdds.split("/")
            val placeFractionNum = placeParts[0].toInt()
            val placeFractionDen = placeParts[1].toInt()

            val placeNumerator = winNumerator * placeFractionNum
            val placeDenominator = winDenominator * placeFractionDen

            val gcd = gcd(placeNumerator, placeDenominator)
            val simplifiedNum = placeNumerator / gcd
            val simplifiedDen = placeDenominator / gcd

            val placeFractional = "$simplifiedNum/$simplifiedDen"
            val placeDecimal = (simplifiedNum.toDouble() / simplifiedDen.toDouble()) + 1.0

            Pair(placeFractional, placeDecimal)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        return if (b == 0) a else gcd(b, a % b)
    }

    private fun parseOdds(oddsString: String): Double? {
        return try {
            when {
                oddsString.contains("/") -> {
                    val parts = oddsString.split("/")
                    (parts[0].toDouble() / parts[1].toDouble()) + 1.0
                }
                else -> oddsString.toDouble()
            }
        } catch (e: Exception) {
            null
        }
    }
}
