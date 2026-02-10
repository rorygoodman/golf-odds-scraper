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
 * @property targetPlaces Number of places to target for E/W section (default 10)
 */
class BoylesportsScraper(
    private val url: String,
    private val targetPlaces: Int = 10
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
            val (sectionElement, eachWayTerms) = findTargetSection()

            if (sectionElement != null) {
                clickShowMoreInSection(sectionElement)
                val players = extractPlayerOddsFromSection(sectionElement, eachWayTerms)
                return EventOdds(
                    eventName = eventName,
                    url = url,
                    players = players,
                    eachWayTerms = eachWayTerms
                )
            } else {
                expandAllSections()
                clickShowMore()
                Thread.sleep(2000)

                val fallbackEwTerms = extractEachWayTerms() ?: EachWayTerms("1/5", 10)
                val players = extractAllPlayerOdds(fallbackEwTerms)
                return EventOdds(
                    eventName = eventName,
                    url = url,
                    players = players,
                    eachWayTerms = fallbackEwTerms
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
                    if (t.match(/\d+\s*places?\s*ew\s*\d+\/\d+/i) || t.match(/ew.*\d+\/\d+.*\d+\s*places/i)) {
                        return t;
                    }
                }
                return null;
            """) as? String ?: return null

            val fractionRegex = Regex("""(\d+/\d+)""")
            val placesRegex = Regex("""(\d+)\s*Places?""", RegexOption.IGNORE_CASE)

            val fraction = fractionRegex.find(text)?.value ?: return null
            val places = placesRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null

            EachWayTerms(fraction, places)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Finds the section matching the target number of places.
     *
     * @return Pair of (section element, each-way terms), both null if not found
     */
    private fun findTargetSection(): Pair<WebElement?, EachWayTerms?> {
        try {
            val js = driver as JavascriptExecutor

            @Suppress("UNCHECKED_CAST")
            val result = js.executeScript("""
                var targetPlaces = arguments[0];
                var pattern = /(\d+)\s*Places?\s*EW\s*(\d+\/\d+)/i;
                var allElements = document.querySelectorAll('*');
                var matches = [];

                for (var i = 0; i < allElements.length; i++) {
                    var el = allElements[i];
                    var text = el.textContent || '';
                    if (text.length < 100) {
                        var match = text.match(pattern);
                        if (match) {
                            matches.push({
                                element: el,
                                places: parseInt(match[1]),
                                fraction: match[2]
                            });
                        }
                    }
                }

                for (var k = 0; k < matches.length; k++) {
                    if (matches[k].places === targetPlaces) {
                        var el = matches[k].element;
                        var container = null;
                        while (el && el !== document.body) {
                            var hasOdds = el.querySelectorAll('button[class*="odds"], [class*="price"], [class*="outcome"]').length;
                            if (hasOdds > 5) {
                                container = el;
                                break;
                            }
                            el = el.parentElement;
                        }
                        if (container) {
                            return {
                                container: container,
                                places: matches[k].places.toString(),
                                fraction: matches[k].fraction
                            };
                        }
                    }
                }
                return null;
            """, targetPlaces)

            if (result != null && result is Map<*, *>) {
                val container = result["container"] as? WebElement
                val places = (result["places"] as? String)?.toIntOrNull()
                val fraction = result["fraction"] as? String

                if (container != null && places != null && fraction != null) {
                    return Pair(container, EachWayTerms(fraction, places))
                }
            }
        } catch (e: Exception) {
            // Error finding target section
        }
        return Pair(null, null)
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
     * @param eachWayTerms Each-way terms for calculating place odds
     * @return List of PlayerOdds, deduplicated by player name
     */
    private fun extractPlayerOddsFromSection(section: WebElement, eachWayTerms: EachWayTerms?): List<PlayerOdds> {
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
                var rows = section.querySelectorAll('[class*="outcome"], [class*="runner"], [class*="selection"], tr, li');

                for (var i = 0; i < rows.length; i++) {
                    var row = rows[i];
                    var nameEl = row.querySelector('[class*="name"], [class*="runner"], [class*="participant"], span:first-child, td:first-child');
                    var oddsEl = row.querySelector('button[class*="odds"], [class*="price"], [class*="odds"]');

                    if (!nameEl || !oddsEl) continue;

                    var name = nameEl.textContent.trim();
                    var odds = oddsEl.textContent.trim();

                    if (name && odds && odds.match(/^\d+\/\d+$/)) {
                        results.push(name + '|||' + odds);
                    }
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
                    val (placeOdds, placeDecimal) = calculatePlaceOdds(odds, eachWayTerms)

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
     *
     * @param eachWayTerms Each-way terms for calculating place odds
     * @return List of PlayerOdds, deduplicated by player name
     */
    private fun extractAllPlayerOdds(eachWayTerms: EachWayTerms?): List<PlayerOdds> {
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
                    var oddsText = oddsEl.innerText.trim();

                    if (!oddsText.match(/^\d+\/\d+$/)) continue;

                    var parent = oddsEl.parentElement;
                    var attempts = 0;
                    var found = false;

                    while (parent && attempts < 10 && !found) {
                        var children = parent.querySelectorAll('*');
                        for (var j = 0; j < children.length; j++) {
                            var child = children[j];
                            if (child.contains(oddsEl) || oddsEl.contains(child)) continue;

                            var childText = (child.innerText || '').trim();

                            if (childText.match(/^[A-Z][a-z]+\s+[A-Z][a-z\-']+(\s+[A-Z][a-z\-']+)?$/) &&
                                childText.length > 5 && childText.length < 35 &&
                                !childText.match(/\d/) &&
                                !childText.match(/show|more|view|all|odds|market/i)) {
                                results.push(childText + '|||' + oddsText);
                                found = true;
                                break;
                            }
                        }
                        parent = parent.parentElement;
                        attempts++;
                    }
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
                    val (placeOdds, placeDecimal) = calculatePlaceOdds(odds, eachWayTerms)

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
