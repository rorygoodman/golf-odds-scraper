package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

/**
 * Represents a player's lay price from Betfair exchange.
 *
 * @property playerName The golfer's name
 * @property price The lay price (decimal odds)
 */
data class PlayerLayPrice(
    val playerName: String,
    val price: Double
)

/**
 * Represents scraped lay prices for a golf event from Betfair exchange.
 *
 * @property eventName Name of the golf event
 * @property url Source URL that was scraped
 * @property players List of player lay prices
 * @property scrapedAt Timestamp when data was scraped
 */
data class BetfairEventOdds(
    val eventName: String,
    val url: String,
    val players: List<PlayerLayPrice>,
    val scrapedAt: String = java.time.LocalDateTime.now().toString()
)

/**
 * Scraper for extracting golf betting lay prices from Betfair exchange.
 *
 * Handles Betfair's virtual scrolling by incrementally scrolling through
 * the runner list and collecting player data at each position.
 *
 * @property url The Betfair market page URL to scrape
 */
class BetfairScraper(private val url: String) {
    private var driver: WebDriver? = null

    /**
     * Scrapes player lay prices from the Betfair market page.
     *
     * Only includes players with available lay prices (skips those without).
     *
     * @return BetfairEventOdds containing all scraped player data
     */
    fun scrape(): BetfairEventOdds {
        try {
            driver = createChromeDriver()
            driver!!.get(url)
            waitForPageLoad()

            val eventName = extractEventName()
            val players = scrollAndExtractPlayers()

            return BetfairEventOdds(
                eventName = eventName,
                url = url,
                players = players
            )
        } finally {
            driver?.quit()
        }
    }

    /**
     * Waits for the page to load and clicks "Show All" if available.
     */
    private fun waitForPageLoad() {
        val wait = WebDriverWait(driver!!, Duration.ofSeconds(20))
        wait.until(ExpectedConditions.presenceOfElementLocated(By.className("mv-runner-list")))
        Thread.sleep(3000)
        clickShowAll()
    }

    /**
     * Clicks "Show All" button to expand the full player list.
     */
    private fun clickShowAll() {
        try {
            val wait = WebDriverWait(driver!!, Duration.ofSeconds(5))
            val possibleSelectors = listOf(
                "//button[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'show all')]",
                "//button[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'show more')]",
                "//a[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'show all')]",
                "//a[contains(translate(text(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), 'show more')]",
                "//button[contains(@class, 'show-all')]",
                "//button[contains(@class, 'show-more')]"
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
     * Scrolls through the runner list and extracts player data at each scroll position.
     *
     * This handles Betfair's virtual scrolling where only visible runners are in the DOM.
     * Only includes players with available lay prices.
     *
     * @return List of PlayerLayPrice for all players with lay prices
     */
    private fun scrollAndExtractPlayers(): List<PlayerLayPrice> {
        val js = driver as JavascriptExecutor
        val playersMap = linkedMapOf<String, Double>()

        val scrollContainer = findScrollableContainer(js)
        if (scrollContainer == null) {
            collectVisiblePlayers(js, playersMap)
            return playersMap.map { (name, price) -> PlayerLayPrice(name, price) }
        }

        val clientHeight = (js.executeScript("return arguments[0].clientHeight;", scrollContainer) as Long)

        js.executeScript("arguments[0].scrollTop = 0;", scrollContainer)
        Thread.sleep(500)

        var noNewPlayersCount = 0

        while (noNewPlayersCount < 8) {
            val previousSize = playersMap.size
            collectVisiblePlayers(js, playersMap)

            if (playersMap.size > previousSize) {
                noNewPlayersCount = 0
            } else {
                noNewPlayersCount++
            }

            js.executeScript(
                "arguments[0].scrollTop += arguments[1];",
                scrollContainer,
                (clientHeight * 0.6).toLong()
            )
            Thread.sleep(700)

            val atBottom = js.executeScript(
                "var el = arguments[0]; return el.scrollTop + el.clientHeight >= el.scrollHeight - 5;",
                scrollContainer
            ) as Boolean

            if (atBottom) {
                collectVisiblePlayers(js, playersMap)
                break
            }
        }
        return playersMap.map { (name, price) -> PlayerLayPrice(name, price) }
    }

    /**
     * Finds the scrollable container that holds the runner list.
     *
     * Uses multiple strategies: walking up from a runner element,
     * known class names, and brute-force search.
     *
     * @param js JavaScript executor
     * @return The scrollable container element, or null if not found
     */
    private fun findScrollableContainer(js: JavascriptExecutor): WebElement? {
        return try {
            js.executeScript("""
                var runner = document.querySelector('h3.runner-name');
                if (runner) {
                    var el = runner.parentElement;
                    while (el && el !== document.body && el !== document.documentElement) {
                        var style = window.getComputedStyle(el);
                        if ((style.overflowY === 'auto' || style.overflowY === 'scroll') &&
                            el.scrollHeight > el.clientHeight + 20) {
                            return el;
                        }
                        el = el.parentElement;
                    }
                }

                var selectors = [
                    '.marketview-header-wrapper-bottom-container',
                    '.marketview-list-runners-component',
                    '.mv-runner-list'
                ];
                for (var i = 0; i < selectors.length; i++) {
                    var container = document.querySelector(selectors[i]);
                    if (container && container.scrollHeight > container.clientHeight + 20) {
                        return container;
                    }
                }

                var divs = document.querySelectorAll('div');
                for (var j = 0; j < divs.length; j++) {
                    var div = divs[j];
                    var st = window.getComputedStyle(div);
                    if ((st.overflowY === 'auto' || st.overflowY === 'scroll') &&
                        div.scrollHeight > div.clientHeight + 100 &&
                        div.querySelector('h3.runner-name')) {
                        return div;
                    }
                }
                return null;
            """) as? WebElement
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts player names and lay prices from the currently visible runners.
     *
     * Only includes players with available lay prices (skips those without).
     * Results are merged into the provided map, deduplicating by player name.
     *
     * @param js JavaScript executor
     * @param playersMap Map to store player name -> lay price
     */
    private fun collectVisiblePlayers(js: JavascriptExecutor, playersMap: MutableMap<String, Double>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val results = js.executeScript("""
                var results = [];
                var rows = document.querySelectorAll('tr.runner-line');
                for (var i = 0; i < rows.length; i++) {
                    var row = rows[i];
                    var nameEl = row.querySelector('h3.runner-name');
                    if (!nameEl) continue;
                    var name = nameEl.textContent.trim();
                    if (!name) continue;

                    var layCell = row.querySelector('td.first-lay-cell');
                    if (layCell) {
                        var layLabel = layCell.querySelector('label.Zs3u5');
                        if (layLabel) {
                            var t = layLabel.textContent.trim();
                            var n = parseFloat(t);
                            if (!isNaN(n) && n > 1) {
                                results.push(name + '|||' + t);
                            }
                        }
                    }
                }
                return results;
            """) as? List<String> ?: return

            for (item in results) {
                val parts = item.split("|||")
                if (parts.size == 2) {
                    val name = parts[0]
                    val price = parts[1].toDoubleOrNull()
                    if (name.isNotBlank() && price != null && !playersMap.containsKey(name)) {
                        playersMap[name] = price
                    }
                }
            }
        } catch (e: Exception) {
            // Error collecting visible players
        }
    }

    /**
     * Extracts the event name from the page header.
     *
     * @return Event name or "Golf Betting" if not found
     */
    private fun extractEventName(): String {
        return try {
            val element = driver!!.findElement(By.cssSelector("h1"))
            element.text.trim()
        } catch (e: Exception) {
            "Golf Betting"
        }
    }
}
