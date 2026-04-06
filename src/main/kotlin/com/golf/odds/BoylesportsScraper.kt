package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

/**
 * Scraper for extracting golf betting odds from Boylesports website.
 *
 * @property url The Boylesports event page URL to scrape
 * @property places Number of places from config (default 10 if not specified)
 */
class BoylesportsScraper(
    private val url: String,
    private val places: Int? = null
) {
    private var driver: WebDriver? = null

    /**
     * Scrapes player odds from the Boylesports event page.
     *
     * Attempts to find a specific E/W section matching targetPlaces,
     * falls back to scraping all visible odds if not found.
     *
     * @return EventOdds containing all scraped player data
     */
    fun scrape(): EventOdds {
        try {
            driver = createChromeDriver()
            driver!!.get(url)
            waitForPageLoad()

            val eventName = extractEventName()
            val sectionElement = findTargetSection()

            if (sectionElement != null) {
                clickShowMoreInSection(sectionElement)
                val players = extractPlayerOddsFromSection(sectionElement)
                return EventOdds(
                    eventName = eventName,
                    url = url,
                    players = players,
                    places = places
                )
            } else {
                expandAllSections()
                clickShowMore()
                Thread.sleep(2000)

                val players = extractAllPlayerOdds()
                return EventOdds(
                    eventName = eventName,
                    url = url,
                    players = players,
                    places = places
                )
            }
        } finally {
            driver?.quit()
        }
    }

    /**
     * Waits for page load and triggers lazy loading by scrolling.
     */
    private fun waitForPageLoad() {
        val wait = WebDriverWait(driver!!, Duration.ofSeconds(30))
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")))
        Thread.sleep(8000)

        val js = driver as JavascriptExecutor
        js.executeScript("window.scrollTo(0, document.body.scrollHeight / 2);")
        Thread.sleep(2000)
        js.executeScript("window.scrollTo(0, document.body.scrollHeight);")
        Thread.sleep(2000)
        js.executeScript("window.scrollTo(0, 0);")
        Thread.sleep(1000)
    }

    /**
     * Expands all collapsible sections on the page.
     */
    private fun expandAllSections() {
        try {
            val js = driver as JavascriptExecutor
            js.executeScript("""
                var toggles = document.querySelectorAll('[class*="expand"], [class*="collapse"], [class*="toggle"], [class*="accordion"]');
                toggles.forEach(function(t) {
                    if (t.tagName === 'BUTTON' || t.tagName === 'A' || t.getAttribute('role') === 'button') {
                        try { t.click(); } catch(e) {}
                    }
                });
                var headers = document.querySelectorAll('[class*="header"], [class*="title"]');
                headers.forEach(function(h) {
                    if (h.getAttribute('aria-expanded') === 'false') {
                        try { h.click(); } catch(e) {}
                    }
                });
            """)
            Thread.sleep(2000)
        } catch (e: Exception) {
            // Ignore expansion errors
        }
    }

    /**
     * Clicks all "Show More" buttons to expand player lists.
     */
    private fun clickShowMore() {
        try {
            val js = driver as JavascriptExecutor
            var clicked = true
            var iterations = 0

            while (clicked && iterations < 30) {
                iterations++
                clicked = js.executeScript("""
                    var buttons = document.querySelectorAll('button, a, span, div');
                    var found = false;
                    for (var i = 0; i < buttons.length; i++) {
                        var text = buttons[i].textContent.trim().toLowerCase();
                        if (text.match(/show\s*\d*\s*more/i) || text.match(/load\s*more/i) || text.match(/see\s*all/i) || text.match(/view\s*all/i)) {
                            buttons[i].click();
                            found = true;
                            break;
                        }
                    }
                    return found;
                """) as Boolean
                if (clicked) Thread.sleep(1000)
            }
        } catch (e: Exception) {
            // Content may already be expanded
        }
    }

    /**
     * Extracts the event name from the page.
     *
     * @return Event name or "Unknown Event" if not found
     */
    private fun extractEventName(): String {
        return try {
            val js = driver as JavascriptExecutor
            js.executeScript("""
                var h1 = document.querySelector('h1');
                if (h1) return h1.textContent.trim();
                var title = document.querySelector('[class*="event-name"], [class*="title"]');
                if (title) return title.textContent.trim();
                return 'Unknown Event';
            """) as? String ?: "Unknown Event"
        } catch (e: Exception) {
            "Unknown Event"
        }
    }

    /**
     * Finds the section matching the target number of places.
     * Handles both "X Places EW" and Boylesports' "EW 1/5 1,2,...,X" formats.
     *
     * @return Section WebElement if found, null otherwise
     */
    private fun findTargetSection(): WebElement? {
        try {
            val targetPlaces = places ?: 10
            val js = driver as JavascriptExecutor

            val result = js.executeScript("""
                var targetPlaces = arguments[0];

                // Pattern 1: "X Places EW" or "X Place EW"
                var pattern1 = /(\d+)\s*Places?\s*EW/i;

                // Pattern 2: Boylesports "EW 1/X 1,2,3,...,N" — count comma-separated numbers
                var pattern2 = /EW\s+\d+\/\d+\s+([\d,]+)/i;

                var allElements = document.querySelectorAll('*');

                for (var i = 0; i < allElements.length; i++) {
                    var el = allElements[i];
                    var text = (el.textContent || '').trim();
                    if (text.length < 150) {
                        var placesFound = null;

                        var m1 = text.match(pattern1);
                        if (m1) placesFound = parseInt(m1[1]);

                        if (!placesFound) {
                            var m2 = text.match(pattern2);
                            if (m2) placesFound = m2[1].split(',').length;
                        }

                        if (placesFound === targetPlaces) {
                            var container = el;
                            while (container && container !== document.body) {
                                var hasOdds = container.querySelectorAll('a.odds, a[class*="addSelection"], button[class*="odds"], [class*="price"], [class*="outcome"]').length;
                                if (hasOdds > 5) {
                                    return container;
                                }
                                container = container.parentElement;
                            }
                        }
                    }
                }
                return null;
            """, targetPlaces)

            return result as? WebElement
        } catch (e: Exception) {
            // Error finding target section
        }
        return null
    }

    /**
     * Clicks "Show More" buttons within a specific section.
     *
     * @param section The section element to expand
     */
    private fun clickShowMoreInSection(section: WebElement) {
        try {
            val js = driver as JavascriptExecutor
            var clicked = true
            var iterations = 0

            while (clicked && iterations < 20) {
                iterations++
                clicked = js.executeScript("""
                    var section = arguments[0];
                    var buttons = section.querySelectorAll('button, a, span');
                    var found = false;
                    for (var i = 0; i < buttons.length; i++) {
                        var text = buttons[i].textContent.trim().toLowerCase();
                        if (text.match(/show\s*\d*\s*more/i) || text.match(/load\s*more/i)) {
                            buttons[i].click();
                            found = true;
                            break;
                        }
                    }
                    return found;
                """, section) as Boolean
                if (clicked) Thread.sleep(1500)
            }
        } catch (e: Exception) {
            // Could not expand section
        }
    }

    /**
     * Extracts player odds from a specific section.
     *
     * @param section The section element containing player odds
     * @return List of PlayerOdds, deduplicated by player name
     */
    private fun extractPlayerOddsFromSection(section: WebElement): List<PlayerOdds> {
        val players = mutableListOf<PlayerOdds>()
        val excludePatterns = listOf(
            "any 2 of", "any 3 of", "both to", "all to",
            "top 5", "top 10", "top 20", "top 30",
            "over ", "under ", " yes", " no",
            "to win", "to finish", "incl. ties",
            "round ", "hole ", "shot"
        )

        try {
            val js = driver as JavascriptExecutor

            @Suppress("UNCHECKED_CAST")
            val results = js.executeScript("""
                var section = arguments[0];
                var results = [];
                var oddsEls = section.querySelectorAll('a.odds, a[class*="addSelection"], [class*="outcome"], [class*="runner"], [class*="selection"]');

                for (var i = 0; i < oddsEls.length; i++) {
                    var el = oddsEls[i];
                    var odds = (el.getAttribute('data-price') || el.textContent || '').trim();
                    if (!odds.match(/^\d+\/\d+$/)) continue;

                    var name = el.getAttribute('data-name') || '';

                    if (!name) {
                        // Walk up to find name element
                        var parent = el.parentElement;
                        for (var p = 0; p < 5 && parent && !name; p++) {
                            var nameEl = parent.querySelector('[class*="player-name"], [class*="runner-name"], [class*="participant"], [class*="name"]');
                            if (nameEl) name = nameEl.textContent.trim();
                            parent = parent.parentElement;
                        }
                    }

                    if (!name) continue;

                    // Convert "LastName, FirstName" → "FirstName LastName"
                    if (name.indexOf(',') !== -1) {
                        var parts = name.split(',');
                        name = parts[1].trim() + ' ' + parts[0].trim();
                    }

                    results.push(name + '|||' + odds);
                }
                return results;
            """, section) as? List<String> ?: emptyList()

            for (item in results) {
                val parts = item.split("|||")
                if (parts.size == 2) {
                    val playerName = parts[0]
                    val odds = parts[1]

                    val nameLower = playerName.lowercase()
                    if (excludePatterns.any { nameLower.contains(it) }) continue
                    if (playerName.contains(" & ") || playerName.contains(" and ")) continue

                    val decimalOdds = parseOdds(odds) ?: continue
                    val (placeOdds, placeDecimal) = calculatePlaceOdds(odds, EachWayTerms("1/5", 10))

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

    /**
     * Extracts all player odds from the entire page (fallback method).
     * Uses data-name attribute from odds links and handles "LastName, FirstName" format.
     *
     * @return List of PlayerOdds, deduplicated by player name
     */
    private fun extractAllPlayerOdds(): List<PlayerOdds> {
        val players = mutableListOf<PlayerOdds>()
        val excludePatterns = listOf(
            "any 2 of", "any 3 of", "both to", "all to",
            "top 5", "top 10", "top 20", "top 30",
            "over ", "under ",
            "to win", "to finish", "incl. ties",
            "round ", "hole ", "shot", "specials"
        )

        try {
            val js = driver as JavascriptExecutor

            @Suppress("UNCHECKED_CAST")
            val results = js.executeScript("""
                var results = [];
                var oddsElements = document.querySelectorAll('a.odds, a[class*="odds"], [class*="addSelection"]');

                for (var i = 0; i < oddsElements.length; i++) {
                    var oddsEl = oddsElements[i];

                    // Prefer data-price attribute, fall back to inner text
                    var oddsText = (oddsEl.getAttribute('data-price') || oddsEl.innerText || '').trim();
                    if (!oddsText.match(/^\d+\/\d+$/)) continue;

                    // Get player name from data-name attribute first
                    var playerName = oddsEl.getAttribute('data-name') || '';

                    if (!playerName) {
                        // Walk up to find a name element
                        var parent = oddsEl.parentElement;
                        for (var p = 0; p < 5 && parent && !playerName; p++) {
                            var nameEl = parent.querySelector('[class*="player-name"], [class*="runner-name"], [class*="participant"]');
                            if (nameEl) playerName = nameEl.textContent.trim();
                            parent = parent.parentElement;
                        }
                    }

                    if (!playerName) continue;

                    // Convert "LastName, FirstName" → "FirstName LastName"
                    if (playerName.indexOf(',') !== -1) {
                        var parts = playerName.split(',');
                        playerName = parts[1].trim() + ' ' + parts[0].trim();
                    }

                    results.push(playerName + '|||' + oddsText);
                }

                var seen = {};
                var unique = [];
                for (var m = 0; m < results.length; m++) {
                    if (!seen[results[m]]) {
                        seen[results[m]] = true;
                        unique.push(results[m]);
                    }
                }
                return unique;
            """) as? List<String> ?: emptyList()

            for (item in results) {
                val parts = item.split("|||")
                if (parts.size == 2) {
                    val playerName = parts[0]
                    val odds = parts[1]

                    val nameLower = playerName.lowercase()
                    if (excludePatterns.any { nameLower.contains(it) }) continue
                    if (playerName.contains(" & ") || playerName.contains(" and ")) continue

                    val decimalOdds = parseOdds(odds) ?: continue
                    val (placeOdds, placeDecimal) = calculatePlaceOdds(odds, EachWayTerms("1/5", 10))

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
