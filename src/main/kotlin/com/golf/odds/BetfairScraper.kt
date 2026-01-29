package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

data class PlayerLayPrice(
    val playerName: String,
    val price: Double,
    val isBackPrice: Boolean = false
)

data class BetfairEventOdds(
    val eventName: String,
    val url: String,
    val players: List<PlayerLayPrice>,
    val scrapedAt: String = java.time.LocalDateTime.now().toString()
)

class BetfairScraper(private val url: String) {
    private var driver: WebDriver? = null

    fun scrape(): BetfairEventOdds {
        try {
            initDriver()
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
        val wait = WebDriverWait(driver!!, Duration.ofSeconds(20))
        wait.until(ExpectedConditions.presenceOfElementLocated(By.className("mv-runner-list")))
        println("mv-runner-list found, waiting for content to load...")

        Thread.sleep(3000)

        clickShowAll()

        println("Page loaded, ready to scroll and extract")
    }

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
                    println("Clicked 'Show All/More' button")
                    return
                } catch (e: Exception) {
                    continue
                }
            }
            println("No 'Show All/More' button found")
        } catch (e: Exception) {
            println("Show All button not found - content may already be expanded")
        }
    }

    /**
     * Scrolls through the runner list and extracts player data at each scroll position.
     * This handles Betfair's virtual scrolling where only visible runners are in the DOM.
     */
    private fun scrollAndExtractPlayers(): List<PlayerLayPrice> {
        val js = driver as JavascriptExecutor
        // Map of player name -> Pair(price, isBackPrice)
        val playersMap = linkedMapOf<String, Pair<Double, Boolean>>()

        val scrollContainer = findScrollableContainer(js)
        if (scrollContainer == null) {
            println("WARNING: Could not find scrollable container, extracting visible players only")
            collectVisiblePlayers(js, playersMap)
            return playersMap.map { (name, d) -> PlayerLayPrice(name, d.first, d.second) }
        }

        val clientHeight = (js.executeScript("return arguments[0].clientHeight;", scrollContainer) as Long)
        val scrollHeight = (js.executeScript("return arguments[0].scrollHeight;", scrollContainer) as Long)
        println("Scrollable container found - clientHeight: $clientHeight, scrollHeight: $scrollHeight")

        // Reset to top
        js.executeScript("arguments[0].scrollTop = 0;", scrollContainer)
        Thread.sleep(500)

        // Scroll incrementally, collecting players at each position
        var noNewPlayersCount = 0
        val maxStaleScrolls = 8
        var scrollIteration = 0

        while (noNewPlayersCount < maxStaleScrolls) {
            scrollIteration++
            val previousSize = playersMap.size
            collectVisiblePlayers(js, playersMap)

            val newCount = playersMap.size - previousSize
            if (newCount > 0) {
                println("  Scroll #$scrollIteration: found $newCount new players (total: ${playersMap.size})")
                noNewPlayersCount = 0
            } else {
                noNewPlayersCount++
            }

            // Scroll down by ~60% of viewport for more overlap
            js.executeScript(
                "arguments[0].scrollTop += arguments[1];",
                scrollContainer,
                (clientHeight * 0.6).toLong()
            )
            Thread.sleep(700)

            // Check if we've reached the bottom
            val atBottom = js.executeScript(
                "var el = arguments[0]; return el.scrollTop + el.clientHeight >= el.scrollHeight - 5;",
                scrollContainer
            ) as Boolean

            if (atBottom) {
                collectVisiblePlayers(js, playersMap)
                println("  Reached bottom of scroll container")
                break
            }
        }

        println("Extracted ${playersMap.size} total players from Betfair")
        return playersMap.map { (name, data) -> PlayerLayPrice(name, data.first, data.second) }
    }

    /**
     * Finds the scrollable container that holds the runner list.
     * Uses multiple strategies: walking up from a runner element, known class names, and brute-force search.
     */
    private fun findScrollableContainer(js: JavascriptExecutor): WebElement? {
        return try {
            js.executeScript("""
                // Strategy 1: Find a runner element and walk up to its scrollable ancestor
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

                // Strategy 2: Known Betfair container classes
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

                // Strategy 3: Find any scrollable div that contains runners
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
            println("Error finding scrollable container: ${e.message}")
            null
        }
    }

    /**
     * Extracts player names and lay prices from the currently visible runners in the DOM.
     * Results are merged into the provided map, deduplicating by player name.
     */
    private fun collectVisiblePlayers(js: JavascriptExecutor, playersMap: MutableMap<String, Pair<Double, Boolean>>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val results = js.executeScript("""
                var results = [];
                var rows = document.querySelectorAll('tr.runner-line');
                for (var i = 0; i < rows.length; i++) {
                    var row = rows[i];

                    // Get player name
                    var nameEl = row.querySelector('h3.runner-name');
                    if (!nameEl) continue;
                    var name = nameEl.textContent.trim();
                    if (!name) continue;

                    var price = '';
                    var isBack = 'false';

                    // Best lay price: td.first-lay-cell
                    var layCell = row.querySelector('td.first-lay-cell');
                    if (layCell) {
                        var layLabel = layCell.querySelector('label.Zs3u5');
                        if (layLabel) {
                            var t = layLabel.textContent.trim();
                            var n = parseFloat(t);
                            if (!isNaN(n) && n > 1) {
                                price = t;
                            }
                        }
                    }

                    // Fallback: best back price: td.last-back-cell
                    if (!price) {
                        var backCell = row.querySelector('td.last-back-cell');
                        if (backCell) {
                            var backLabel = backCell.querySelector('label.Zs3u5');
                            if (backLabel) {
                                var t2 = backLabel.textContent.trim();
                                var n2 = parseFloat(t2);
                                if (!isNaN(n2) && n2 > 1) {
                                    price = t2;
                                    isBack = 'true';
                                }
                            }
                        }
                    }

                    results.push(name + '|||' + price + '|||' + isBack);
                }
                return results;
            """) as? List<String> ?: return

            for (item in results) {
                val parts = item.split("|||")
                if (parts.size == 3) {
                    val name = parts[0]
                    val price = parts[1].toDoubleOrNull()
                    val isBack = parts[2] == "true"
                    if (name.isNotBlank() && price != null) {
                        // Prefer lay price over back price if we already have one
                        val existing = playersMap[name]
                        if (existing == null || (existing.second && !isBack)) {
                            playersMap[name] = Pair(price, isBack)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("Error collecting visible players: ${e.message}")
        }
    }

    private fun extractEventName(): String {
        return try {
            val element = driver!!.findElement(By.cssSelector("h1"))
            element.text.trim()
        } catch (e: Exception) {
            "Golf Betting"
        }
    }
}

fun printBetfairEventOdds(eventOdds: BetfairEventOdds) {
    println("\nBetfair Event: ${eventOdds.eventName}")
    println("URL: ${eventOdds.url}")
    println("Scraped at: ${eventOdds.scrapedAt}")

    val backCount = eventOdds.players.count { it.isBackPrice }
    println("\nPlayer Prices (${eventOdds.players.size} players):")
    if (backCount > 0) {
        println("  * = best back price (lay price unavailable)")
    }
    println("-".repeat(80))

    eventOdds.players
        .sortedBy { it.price }
        .forEach { player ->
            val marker = if (player.isBackPrice) "*" else " "
            println("${player.playerName.padEnd(30)} ${player.price}$marker")
        }

    if (eventOdds.players.isEmpty()) {
        println("\nNo players found. The page structure might be different than expected.")
    }
}
