package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

/**
 * Scraper for extracting golf betting odds from Paddy Power website.
 *
 * @property url The Paddy Power event page URL to scrape
 * @property targetPlaces Number of places to target for E/W section (default 10)
 */
class PaddyPowerScraper(
    private val url: String,
    private val targetPlaces: Int = 10
) {
    private var driver: WebDriver? = null

    /**
     * Scrapes player odds from the Paddy Power event page.
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
                clickShowMore()
                val fallbackEwTerms = extractEachWayTerms()
                val players = extractPlayerOdds(fallbackEwTerms)
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
     * Waits for the page to fully load including dynamic content.
     */
    private fun waitForPageLoad() {
        val wait = WebDriverWait(driver!!, Duration.ofSeconds(15))
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("p.outright-item__runner-name")))
        Thread.sleep(3000)
    }

    /**
     * Clicks all "Show More" links to expand the full player list.
     */
    private fun clickShowMore() {
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
            // Content may already be expanded
        }
    }

    /**
     * Finds the section matching the target number of places.
     *
     * Looks for patterns like "10 Places EW 1/5" or "Outright Winner - 10 Places".
     *
     * @return Pair of (section element, each-way terms), both null if not found
     */
    private fun findTargetSection(): Pair<WebElement?, EachWayTerms?> {
        try {
            val js = driver as JavascriptExecutor

            @Suppress("UNCHECKED_CAST")
            val result = js.executeScript("""
                var targetPlaces = arguments[0];
                var headerSelectors = [
                    'h1', 'h2', 'h3', 'h4', 'h5',
                    '[class*="title"]', '[class*="header"]', '[class*="label"]',
                    'button[class*="accordion"]', 'div[class*="accordion"]'
                ];

                var pattern1 = /(\d+)\s*Places?\s*EW\s*(\d+\/\d+)/i;
                var pattern2 = /Outright Winner\s*-\s*(\d+)\s*Places?/i;
                var matches = [];

                for (var i = 0; i < headerSelectors.length; i++) {
                    var elements = document.querySelectorAll(headerSelectors[i]);
                    for (var j = 0; j < elements.length; j++) {
                        var text = elements[j].textContent || '';

                        var match = text.match(pattern1);
                        if (match) {
                            matches.push({
                                element: elements[j],
                                places: parseInt(match[1]),
                                fraction: match[2]
                            });
                            continue;
                        }

                        match = text.match(pattern2);
                        if (match) {
                            var ewMatch = text.match(/(\d+\/\d+)\s*(?:Odds|EW)/i);
                            var fraction = ewMatch ? ewMatch[1] : '1/5';
                            matches.push({
                                element: elements[j],
                                places: parseInt(match[1]),
                                fraction: fraction
                            });
                        }
                    }
                }

                for (var k = 0; k < matches.length; k++) {
                    if (matches[k].places === targetPlaces) {
                        var el = matches[k].element;
                        var container = null;
                        while (el && el !== document.body) {
                            var items = el.querySelectorAll('div.outright-item, [class*="outright"]');
                            if (items.length > 5) {
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
            while (clicked) {
                clicked = js.executeScript("""
                    var section = arguments[0];
                    var links = section.querySelectorAll('a.link__container--with-divider, a[class*="show-more"], button[class*="show-more"]');
                    var found = false;
                    for (var i = 0; i < links.length; i++) {
                        if (links[i].textContent.trim().toLowerCase().includes('show more') ||
                            links[i].textContent.trim().toLowerCase().includes('show all')) {
                            links[i].click();
                            found = true;
                        }
                    }
                    return found;
                """, section) as Boolean
                if (clicked) Thread.sleep(1000)
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
            "over ", "under ", "yes", "no",
            "to win", "to finish", "incl. ties",
            "round ", "hole ", "shot"
        )

        try {
            val js = driver as JavascriptExecutor

            @Suppress("UNCHECKED_CAST")
            val results = js.executeScript("""
                var section = arguments[0];
                var results = [];
                var items = section.querySelectorAll('div.outright-item, [class*="outright-item"]');

                for (var i = 0; i < items.length; i++) {
                    var item = items[i];
                    var nameEl = item.querySelector('p.outright-item__runner-name') ||
                                 item.querySelector('[class*="runner-name"]') ||
                                 item.querySelector('p[class*="name"]') ||
                                 item.querySelector('span[class*="name"]');
                    var oddsEl = item.querySelector('span.btn-odds__label') ||
                                 item.querySelector('[class*="btn-odds"]') ||
                                 item.querySelector('[class*="odds-label"]') ||
                                 item.querySelector('button[class*="odds"] span');

                    if (nameEl && oddsEl) {
                        var name = nameEl.textContent.trim();
                        var odds = oddsEl.textContent.trim();
                        if (name && odds && odds.includes('/')) {
                            results.push(name + '|||' + odds);
                        }
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
     * @return EachWayTerms if found, null otherwise
     */
    private fun extractEachWayTerms(): EachWayTerms? {
        return try {
            val ewElement = driver!!.findElement(By.cssSelector("span.label-value__value"))
            val text = ewElement.text.trim()

            val fractionRegex = Regex("""(\d+/\d+)\s*Odds""", RegexOption.IGNORE_CASE)
            val placesRegex = Regex("""(\d+)\s*Places?""", RegexOption.IGNORE_CASE)

            val fraction = fractionRegex.find(text)?.groupValues?.get(1) ?: return null
            val places = placesRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null

            EachWayTerms(fraction, places)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts all player odds from the page (fallback method).
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
                var items = document.querySelectorAll('div.outright-item, [class*="outright-item"]');

                for (var i = 0; i < items.length; i++) {
                    var item = items[i];
                    var nameEl = item.querySelector('p.outright-item__runner-name') ||
                                 item.querySelector('[class*="runner-name"]') ||
                                 item.querySelector('p[class*="name"]') ||
                                 item.querySelector('span[class*="name"]');
                    var oddsEl = item.querySelector('span.btn-odds__label') ||
                                 item.querySelector('[class*="btn-odds"]') ||
                                 item.querySelector('[class*="odds-label"]') ||
                                 item.querySelector('button[class*="odds"] span');

                    if (nameEl && oddsEl) {
                        var name = nameEl.textContent.trim();
                        var odds = oddsEl.textContent.trim();
                        if (name && odds && odds.includes('/')) {
                            results.push(name + '|||' + odds);
                        }
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
