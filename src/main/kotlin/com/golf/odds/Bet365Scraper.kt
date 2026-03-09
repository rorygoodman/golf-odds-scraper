package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

/**
 * Scraper for extracting golf betting odds from Bet365.
 *
 * Scrapes a specific column from a multi-column market group by matching
 * the column header text against the configured [header] string.
 *
 * @property url The Bet365 event page URL to scrape
 * @property places Number of E/W places (used for place odds calculation)
 * @property header Column header text to target (e.g. "E/W 1/5 1-10")
 */
class Bet365Scraper(
    private val url: String,
    private val places: Int? = null,
    private val header: String? = null
) {
    private var driver: WebDriver? = null

    private fun createBet365Driver(): WebDriver {
        val options = ChromeOptions().apply {
            // Non-headless: bet365 blocks headless browsers entirely
            addArguments("--no-sandbox")
            addArguments("--disable-dev-shm-usage")
            addArguments("--window-size=1920,1080")
            addArguments("--disable-blink-features=AutomationControlled")
            addArguments("--disable-extensions")
            addArguments("--disable-infobars")
            addArguments("--remote-allow-origins=*")
            setExperimentalOption("excludeSwitches", listOf("enable-automation"))
            setExperimentalOption("useAutomationExtension", false)
        }
        return ChromeDriver(options)
    }

    fun scrape(): EventOdds {
        try {
            driver = createBet365Driver()
            driver!!.get(url)
            try {
                waitForPageLoad()
            } catch (e: Exception) {
                val title = driver!!.title
                val bodySnippet = driver!!.findElement(By.tagName("body")).text.take(200)
                System.err.println("Bet365 wait failed. Title: '$title'. Body: '$bodySnippet'")
                throw e
            }

            val eventName = extractEventName()
            val players = extractPlayerOdds()

            return EventOdds(
                eventName = eventName,
                url = url,
                players = players,
                places = places
            )
        } finally {
            driver?.quit()
        }
    }

    private fun waitForPageLoad() {
        // Wait for body first, then give the SPA time to boot and navigate to the hash route
        val wait = WebDriverWait(driver!!, Duration.ofSeconds(15))
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")))
        Thread.sleep(5000)

        // Scroll to trigger any lazy-loading
        val js = driver as JavascriptExecutor
        try {
            for (i in 1..3) {
                js.executeScript("window.scrollBy(0, 500)")
                Thread.sleep(500)
            }
            js.executeScript("window.scrollTo(0, 0)")
            Thread.sleep(1000)
        } catch (e: Exception) { /* ignore */ }

        // Now wait for the market content (up to 30s)
        val marketWait = WebDriverWait(driver!!, Duration.ofSeconds(30))
        marketWait.until(ExpectedConditions.presenceOfElementLocated(
            By.cssSelector(".srb-ParticipantLabelSmallBorder_Name")
        ))
        Thread.sleep(2000)
    }

    private fun extractEventName(): String {
        return try {
            driver!!.findElement(By.cssSelector("h1")).text.trim()
        } catch (e: Exception) {
            "Unknown Event"
        }
    }

    private fun extractPlayerOdds(): List<PlayerOdds> {
        val players = mutableListOf<PlayerOdds>()

        try {
            val js = driver as JavascriptExecutor

            @Suppress("UNCHECKED_CAST")
            val results = js.executeScript("""
                var targetHeader = arguments[0];

                // Each column is a srb-HScrollPlaceColumnMarket containing a header + odds per player
                var columns = document.querySelectorAll('.srb-HScrollPlaceColumnMarket');
                if (columns.length === 0) return [];

                // Find the column whose header matches
                var targetColumn = null;
                for (var i = 0; i < columns.length; i++) {
                    var h = columns[i].querySelector('.srb-HScrollPlaceHeader');
                    if (!h) continue;
                    var text = h.textContent.trim().toLowerCase();
                    if (targetHeader === '' || text.indexOf(targetHeader.toLowerCase()) !== -1) {
                        targetColumn = columns[i];
                        break;
                    }
                }
                if (!targetColumn) return [];

                // Odds within the target column (one per player, in same order as names)
                var oddsEls = targetColumn.querySelectorAll('.gl-ParticipantOddsOnly_Odds');
                // Player names (positionally aligned with odds)
                var nameEls = document.querySelectorAll('.srb-ParticipantLabelSmallBorder_Name');

                var results = [];
                var count = Math.min(nameEls.length, oddsEls.length);
                for (var i = 0; i < count; i++) {
                    var name = nameEls[i].textContent.trim();
                    var odds = oddsEls[i].textContent.trim();
                    if (name && odds && odds.indexOf('/') !== -1) {
                        results.push(name + '|||' + odds);
                    }
                }

                return results;
            """, header ?: "") as? List<String> ?: emptyList()

            for (item in results) {
                val parts = item.split("|||")
                if (parts.size == 2) {
                    val playerName = parts[0]
                    val odds = parts[1]

                    val decimalOdds = parseOdds(odds) ?: continue
                    val (placeOdds, placeDecimal) = calculatePlaceOdds(odds, EachWayTerms("1/5", places ?: 10))

                    if (placeOdds != null && placeDecimal != null) {
                        players.add(PlayerOdds(playerName, odds, decimalOdds, placeOdds, placeDecimal))
                    }
                }
            }
        } catch (e: Exception) {
            // Error extracting players
        }

        return players.distinctBy { it.playerName }
    }
}