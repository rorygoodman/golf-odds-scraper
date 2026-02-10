package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

/**
 * Scraper for extracting golf betting odds from Sky Bet website.
 *
 * @property url The Sky Bet event page URL to scrape
 */
class SkybetScraper(private val url: String) {
    private var driver: WebDriver? = null

    /**
     * Scrapes player odds from the Sky Bet event page.
     *
     * @return EventOdds containing all scraped player data
     */
    fun scrape(): EventOdds {
        try {
            driver = createChromeDriver()
            driver!!.get(url)
            waitForPageLoad()
            expandAllPlayers()

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

        val js = driver as JavascriptExecutor

        // Dismiss OneTrust cookie consent if present
        try {
            js.executeScript("""
                // Try OneTrust accept button
                var otAccept = document.getElementById('onetrust-accept-btn-handler');
                if (otAccept) { otAccept.click(); return; }

                // Try other common cookie buttons
                var acceptButtons = document.querySelectorAll('button');
                for (var i = 0; i < acceptButtons.length; i++) {
                    var text = acceptButtons[i].textContent.toLowerCase();
                    if (text.includes('accept all') || text.includes('accept cookies') || text.includes('agree')) {
                        acceptButtons[i].click();
                        return;
                    }
                }

                // Remove the overlay directly if it exists
                var overlay = document.querySelector('.onetrust-pc-dark-filter');
                if (overlay) overlay.remove();
                var banner = document.getElementById('onetrust-banner-sdk');
                if (banner) banner.remove();
            """)
            Thread.sleep(2000)
        } catch (e: Exception) {
            // No cookie popup
        }

        // Scroll incrementally to trigger lazy loading
        for (i in 1..5) {
            js.executeScript("window.scrollTo(0, document.body.scrollHeight * $i / 5);")
            Thread.sleep(1000)
        }
        js.executeScript("window.scrollTo(0, 0);")
        Thread.sleep(2000)
    }

    /**
     * Expands all player sections by clicking "Show More" buttons.
     */
    private fun expandAllPlayers() {
        val js = driver as JavascriptExecutor


        // Click "Show More" button once to expand the Outright section
        try {
            // Scroll down to make button visible
            js.executeScript("window.scrollTo(0, document.body.scrollHeight);")
            Thread.sleep(1500)

            // Click the Show More button in the Outright section
            val result = js.executeScript("""
                var cards = document.querySelectorAll('[class*="card"]');
                for (var i = 0; i < cards.length; i++) {
                    var h3 = cards[i].querySelector('h3');
                    if (h3 && h3.textContent.trim() === 'Outright') {
                        var btn = cards[i].querySelector('button[class*="showMoreButton"]');
                        if (btn) {
                            btn.scrollIntoView({block: 'center'});
                            btn.click();
                            return 'expanded';
                        }
                        return 'no_button';
                    }
                }
                return 'no_card';
            """) as String
            Thread.sleep(3000)  // Wait for content to load
        } catch (e: Exception) {
            println("  Expand failed: ${e.message}")
        }

        // Final scroll through all content
        try {
            js.executeScript("window.scrollTo(0, 0);")
            Thread.sleep(1000)
            for (i in 1..5) {
                js.executeScript("window.scrollTo(0, document.body.scrollHeight * $i / 5);")
                Thread.sleep(800)
            }
        } catch (e: Exception) {
            // Ignore scroll errors
        }

    }

    /**
     * Extracts the event name from the page header.
     *
     * @return Event name or default value if not found
     */
    private fun extractEventName(): String {
        return try {
            val js = driver as JavascriptExecutor
            js.executeScript("""
                var h1 = document.querySelector('h1');
                if (h1) return h1.textContent.trim();
                var title = document.querySelector('[class*="event-title"], [class*="EventHeader"]');
                if (title) return title.textContent.trim();
                return 'Unknown Event';
            """) as? String ?: "Unknown Event"
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
            val js = driver as JavascriptExecutor
            val text = js.executeScript("""
                var elements = document.querySelectorAll('*');
                for (var i = 0; i < elements.length; i++) {
                    var t = elements[i].textContent || '';
                    // Match patterns like "Each Way: 1/5 odds, 8 places" or "EW 1/5 - 10 places"
                    if (t.match(/each\s*way.*\d+\/\d+.*\d+\s*place/i) ||
                        t.match(/\d+\/\d+.*\d+\s*place/i) ||
                        t.match(/\d+\s*places?\s*at\s*\d+\/\d+/i)) {
                        if (t.length < 100) return t;
                    }
                }
                return null;
            """) as? String ?: return null

            val fractionRegex = Regex("""(\d+/\d+)""")
            val placesRegex = Regex("""(\d+)\s*places?""", RegexOption.IGNORE_CASE)

            val fraction = fractionRegex.find(text)?.value ?: return null
            val places = placesRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null

            EachWayTerms(fraction, places)
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
            val js = driver as JavascriptExecutor

            @Suppress("UNCHECKED_CAST")
            val results = js.executeScript("""
                var results = [];

                // Find the Outright section first
                var cards = document.querySelectorAll('[class*="card"]');
                var outrightCard = null;

                for (var i = 0; i < cards.length; i++) {
                    var h3 = cards[i].querySelector('h3');
                    if (h3 && h3.textContent.trim() === 'Outright') {
                        outrightCard = cards[i];
                        break;
                    }
                }

                if (!outrightCard) return results;

                // Get runners within the Outright section - target the inner runnerLine with runnerName
                var runnerRows = outrightCard.querySelectorAll('div[class*="runnerLine"]:not([class*="grid"])');

                for (var i = 0; i < runnerRows.length; i++) {
                    var row = runnerRows[i];

                    // Player name is in p tag with class containing 'runnerName'
                    var nameEl = row.querySelector('p[class*="runnerName"]');
                    if (!nameEl) continue;

                    var name = nameEl.textContent.trim();
                    if (!name) continue;

                    // Get all bet button wrappers - we want the 3rd one (10 places E/W)
                    var betWrappers = row.querySelectorAll('[class*="betButtonWrapper"]');
                    if (betWrappers.length < 3) continue;

                    // Get the odds from the 3rd wrapper (index 2)
                    var thirdWrapper = betWrappers[2];
                    var oddsEl = thirdWrapper.querySelector('span[class*="label"]');
                    if (!oddsEl) continue;

                    var odds = oddsEl.textContent.trim();

                    // Validate it looks like fractional odds
                    if (odds && odds.match(/^\d+\/\d+$/)) {
                        results.push(name + '|||' + odds);
                    }
                }

                return results;
            """) as? List<String> ?: emptyList()

            for (item in results) {
                val parts = item.split("|||")
                if (parts.size == 2) {
                    val playerName = parts[0]
                    val odds = parts[1]

                    val decimalOdds = parseOdds(odds) ?: continue
                    val (placeOdds, placeDecimal) = calculatePlaceOdds(odds, eachWayTerms ?: EachWayTerms("1/5", 10))

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
