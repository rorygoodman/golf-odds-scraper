package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

data class PlayerLayPrice(
    val playerName: String,
    val layPrice: Double
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
            val players = extractPlayerLayPrices()

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
        // Wait for the runner list container
        wait.until(ExpectedConditions.presenceOfElementLocated(By.className("mv-runner-list")))
        println("mv-runner-list found, waiting for content to load...")

        // Wait for initial content to populate
        Thread.sleep(3000)

        // Try to click "Show All" or similar button
        clickShowAll()

        // Scroll down the page to load more players (lazy loading)
        scrollToLoadAllPlayers()

        println("Finished waiting for page load")
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

    private fun scrollToLoadAllPlayers() {
        println("Scrolling internal container to load all players...")
        var previousCount = 0
        var currentCount = 0
        var noChangeCount = 0
        val maxNoChange = 3

        try {
            // Try to find the scrollable container
            val scrollableContainer = try {
                val headerWrapper = driver!!.findElement(By.cssSelector("div.marketview-header-wrapper-bottom-container"))
                println("Found marketview-header-wrapper-bottom-container")
                headerWrapper
            } catch (e: Exception) {
                println("marketview-header-wrapper-bottom-container not found, trying marketview-list-runners-component")
                try {
                    driver!!.findElement(By.cssSelector("div.marketview-list-runners-component.bf-row"))
                } catch (e2: Exception) {
                    driver!!.findElement(By.cssSelector("div.marketview-list-runners-component"))
                }
            }

            val scrollHeight = (driver as org.openqa.selenium.JavascriptExecutor).executeScript(
                "return arguments[0].scrollHeight;", scrollableContainer
            ) as Long
            val clientHeight = (driver as org.openqa.selenium.JavascriptExecutor).executeScript(
                "return arguments[0].clientHeight;", scrollableContainer
            ) as Long
            println("Scrollable container - scrollHeight: $scrollHeight, clientHeight: $clientHeight")

            // Scroll the internal container multiple times
            for (i in 1..20) {
                // Get current count of runner names
                currentCount = driver!!.findElements(By.cssSelector("h3.runner-name")).size

                // If count hasn't changed for 3 consecutive attempts, we've loaded everything
                if (currentCount == previousCount && previousCount > 0) {
                    noChangeCount++
                    if (noChangeCount >= maxNoChange) {
                        println("Loaded $currentCount players from Betfair")
                        break
                    }
                } else {
                    noChangeCount = 0
                }

                previousCount = currentCount

                // Try to scroll the container
                (driver as org.openqa.selenium.JavascriptExecutor).executeScript(
                    "arguments[0].scrollTop = arguments[0].scrollTop + 500;", scrollableContainer
                )

                // Send Page Down key
                try {
                    scrollableContainer.sendKeys(org.openqa.selenium.Keys.PAGE_DOWN)
                } catch (e: Exception) {
                    // Ignore
                }

                Thread.sleep(1000)
            }
        } catch (e: Exception) {
            println("Error scrolling container: ${e.message}")
            println("Falling back to window scrolling...")
            // Fallback to window scrolling
            for (i in 1..10) {
                (driver as org.openqa.selenium.JavascriptExecutor).executeScript("window.scrollBy(0, 500);")
                Thread.sleep(1000)
            }
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

    private fun extractPlayerLayPrices(): List<PlayerLayPrice> {
        val players = mutableListOf<PlayerLayPrice>()

        try {
            // Search for all runner names on the page (not limited to mv-runner-list)
            val runnerNames = driver!!.findElements(By.cssSelector("h3.runner-name"))
            println("Found ${runnerNames.size} runner names (h3.runner-name) on the page")

            // Search for all lay price labels on the page
            val layPriceElements = driver!!.findElements(By.cssSelector("label.Zs3u5.AUP11.Qe-26"))
            println("Found ${layPriceElements.size} lay price elements (label.Zs3u5.AUP11.Qe-26)")

            // Match up names with lay prices
            // Assuming the order is consistent (first name matches first lay price, etc.)
            val minSize = minOf(runnerNames.size, layPriceElements.size)

            for (i in 0 until minSize) {
                try {
                    val playerName = runnerNames[i].text.trim()
                    val layPriceText = layPriceElements[i].text.trim()
                    val layPrice = layPriceText.toDoubleOrNull()

                    if (playerName.isNotBlank() && layPrice != null) {
                        players.add(
                            PlayerLayPrice(
                                playerName = playerName,
                                layPrice = layPrice
                            )
                        )
                    }
                } catch (e: Exception) {
                    println("Error extracting player at index $i: ${e.message}")
                }
            }

            println("Successfully extracted ${players.size} players with lay prices")
        } catch (e: Exception) {
            println("Error extracting player lay prices: ${e.message}")
            e.printStackTrace()
        }

        return players
    }
}

fun printBetfairEventOdds(eventOdds: BetfairEventOdds) {
    println("\nBetfair Event: ${eventOdds.eventName}")
    println("URL: ${eventOdds.url}")
    println("Scraped at: ${eventOdds.scrapedAt}")

    println("\nPlayer Lay Prices (${eventOdds.players.size} players):")
    println("-".repeat(80))

    eventOdds.players
        .sortedBy { it.layPrice }
        .forEach { player ->
            println("${player.playerName.padEnd(30)} Lay: ${player.layPrice}")
        }

    if (eventOdds.players.isEmpty()) {
        println("\n⚠️  No players found! The page structure might be different than expected.")
    }
}
