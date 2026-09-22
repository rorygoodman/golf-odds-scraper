package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

/**
 * Scraper for extracting golf betting odds from William Hill website.
 *
 * Targets a competition outrights page, e.g.
 * https://sports.williamhill.com/betting/en-gb/golf/competitions/open-de-france-2026/outrights
 *
 * William Hill lists each outright market as a collapsible section. Sections
 * start collapsed and render no content until expanded, so every section is
 * opened before the target market is picked out by header or place count.
 *
 * @property url The William Hill outrights page URL to scrape
 * @property places Number of places from config, used to pick the market
 * @property header Market title fragment from config, takes precedence over places
 */
class WilliamHillScraper(
    private val url: String,
    private val places: Int? = null,
    private val header: String? = null
) {
    private var driver: WebDriver? = null

    fun scrape(): EventOdds {
        try {
            driver = createChromeDriver()
            driver!!.get(url)
            waitForPageLoad()

            val js = driver as JavascriptExecutor
            expandAllSections(js)

            val eventName = extractEventName(js)
            val section = findTargetSection(js)

            if (section == null) {
                println("  William Hill: no matching market section found")
                return EventOdds(eventName = eventName, url = url, players = emptyList(), places = places)
            }

            clickShowMoreInSection(js, section)

            val terms = extractEachWayTerms(js, section)
            println("  William Hill terms: ${terms.placeOdds} odds, ${terms.numberOfPlaces} places")

            val players = extractPlayerOdds(js, section, terms)
            println("  William Hill results: ${players.size} runners")

            return EventOdds(
                eventName = eventName,
                url = url,
                players = players,
                places = places ?: terms.numberOfPlaces
            )
        } finally {
            driver?.quit()
        }
    }

    private fun waitForPageLoad() {
        val wait = WebDriverWait(driver!!, Duration.ofSeconds(30))
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")))
        Thread.sleep(6000)

        val js = driver as JavascriptExecutor

        // Dismiss cookie consent if present
        try {
            js.executeScript("""
                var otAccept = document.getElementById('onetrust-accept-btn-handler');
                if (otAccept) { otAccept.click(); return; }
                var buttons = document.querySelectorAll('button');
                for (var i = 0; i < buttons.length; i++) {
                    var text = buttons[i].textContent.toLowerCase();
                    if (text.includes('accept all') || text.includes('accept cookies') || text.includes('agree')) {
                        buttons[i].click(); return;
                    }
                }
                var banner = document.getElementById('onetrust-banner-sdk');
                if (banner) banner.remove();
            """)
            Thread.sleep(2000)
        } catch (e: Exception) {
            // No cookie popup
        }
    }

    /**
     * Opens every collapsed market section. Sections nest (a "Tournament Winner"
     * group holds the individual place-term markets), and a collapsed section
     * renders no children, so this runs repeatedly until nothing else opens.
     */
    private fun expandAllSections(js: JavascriptExecutor) {
        for (pass in 1..6) {
            val opened = try {
                js.executeScript("""
                    var triggers = document.querySelectorAll(
                        'section[data-testid="sb-ui-collapsible-section"] > button[aria-expanded="false"]');
                    for (var i = 0; i < triggers.length; i++) {
                        try { triggers[i].click(); } catch (e) {}
                    }
                    return triggers.length;
                """) as? Long ?: 0L
            } catch (e: Exception) {
                0L
            }

            if (opened == 0L) break
            Thread.sleep(1500)
        }
    }

    private fun extractEventName(js: JavascriptExecutor): String {
        return try {
            js.executeScript("""
                var header = document.querySelector('[data-testid="sb-ui-page-header"] h1, h1');
                if (header) return header.textContent.trim();
                return 'Unknown Event';
            """) as? String ?: "Unknown Event"
        } catch (e: Exception) {
            "Unknown Event"
        }
    }

    /**
     * Finds the outright market section to scrape.
     *
     * Only leaf sections (those holding runners rather than other sections) are
     * considered. A configured [header] wins if its text appears in the title;
     * otherwise the section whose place terms — from the "N Places" title suffix
     * or the "1/4 Odds Place 1, 2, 3" annotation — match [places] is used. With
     * neither, the first winner market on the page is taken.
     */
    private fun findTargetSection(js: JavascriptExecutor): WebElement? {
        return try {
            js.executeScript("""
                var wantedHeader = arguments[0];
                var wantedPlaces = arguments[1];

                var sections = document.querySelectorAll('section[data-testid="sb-ui-collapsible-section"]');
                var candidates = [];

                for (var i = 0; i < sections.length; i++) {
                    var s = sections[i];
                    // Leaf sections only - a group section wraps other sections
                    if (s.querySelector('section[data-testid="sb-ui-collapsible-section"]')) continue;
                    if (s.querySelectorAll('button[data-testid="bet-button"]').length < 3) continue;

                    var titleEl = s.querySelector('[data-testid="sb-ui-collapsible-section-title"]');
                    var title = titleEl ? titleEl.textContent.trim() : '';

                    var annotationEl = s.querySelector('[class*="marketAnnotation"]');
                    var annotation = annotationEl ? annotationEl.textContent.trim() : '';

                    // Places from "... 10 Places" in the title
                    var placeCount = null;
                    var titleMatch = title.match(/(\d+)\s*Places?/i);
                    if (titleMatch) placeCount = parseInt(titleMatch[1]);

                    // Otherwise count the positions in "1/4 Odds Place 1, 2, 3, 4, 5, 6"
                    if (!placeCount) {
                        var annMatch = annotation.match(/Odds\s+Places?\s+([\d,\s]+)/i);
                        if (annMatch) placeCount = annMatch[1].split(',').length;
                    }

                    candidates.push({ section: s, title: title, placeCount: placeCount });
                }

                if (wantedHeader) {
                    var needle = wantedHeader.toLowerCase();
                    for (var h = 0; h < candidates.length; h++) {
                        if (candidates[h].title.toLowerCase().indexOf(needle) !== -1) {
                            return candidates[h].section;
                        }
                    }
                }

                if (wantedPlaces) {
                    for (var p = 0; p < candidates.length; p++) {
                        if (candidates[p].placeCount === wantedPlaces) return candidates[p].section;
                    }
                }

                for (var w = 0; w < candidates.length; w++) {
                    if (candidates[w].title.toLowerCase().indexOf('winner') !== -1) return candidates[w].section;
                }

                return candidates.length ? candidates[0].section : null;
            """, header, places) as? WebElement
        } catch (e: Exception) {
            println("  William Hill section lookup error: ${e.message}")
            null
        }
    }

    /**
     * Clicks the section's "Show more" button until the full field is listed.
     */
    private fun clickShowMoreInSection(js: JavascriptExecutor, section: WebElement) {
        for (iteration in 1..30) {
            val clicked = try {
                js.executeScript("""
                    var section = arguments[0];
                    var more = section.querySelector('[data-testid="sb-ui-expandable-list-more"]');
                    if (!more) return false;
                    if (more.textContent.trim().toLowerCase().indexOf('less') !== -1) return false;
                    more.click();
                    return true;
                """, section) as? Boolean ?: false
            } catch (e: Exception) {
                false
            }

            if (!clicked) break
            Thread.sleep(1200)
        }
    }

    /**
     * Reads each-way terms from the market annotation, e.g.
     * "1/4  Odds Place 1, 2, 3, 4, 5, 6" -> 1/4 odds, 6 places.
     *
     * Falls back to 1/5 on the configured place count when no annotation is shown.
     */
    private fun extractEachWayTerms(js: JavascriptExecutor, section: WebElement): EachWayTerms {
        val fallback = EachWayTerms("1/5", places ?: 10)

        return try {
            val annotation = js.executeScript("""
                var section = arguments[0];
                var el = section.querySelector('[class*="marketAnnotation"]');
                return el ? el.textContent.trim() : '';
            """, section) as? String ?: ""

            if (annotation.isBlank()) return fallback

            val fraction = Regex("""(\d+/\d+)""").find(annotation)?.value ?: fallback.placeOdds
            val positions = Regex("""Odds\s+Places?\s+([\d,\s]+)""", RegexOption.IGNORE_CASE)
                .find(annotation)?.groupValues?.get(1)
                ?.split(",")?.count { it.isNotBlank() }

            EachWayTerms(fraction, positions ?: fallback.numberOfPlaces)
        } catch (e: Exception) {
            fallback
        }
    }

    private fun extractPlayerOdds(
        js: JavascriptExecutor,
        section: WebElement,
        terms: EachWayTerms
    ): List<PlayerOdds> {
        val players = mutableListOf<PlayerOdds>()

        try {
            @Suppress("UNCHECKED_CAST")
            val results = js.executeScript("""
                var section = arguments[0];
                var results = [];
                var buttons = section.querySelectorAll('button[data-testid="bet-button"]');

                for (var i = 0; i < buttons.length; i++) {
                    var button = buttons[i];
                    var name = (button.getAttribute('data-selection-name') || '').trim();
                    var odds = (button.textContent || '').trim();

                    if (!name) continue;
                    if (!odds.match(/^\d+\/\d+${'$'}/)) continue;

                    results.push(name + '|||' + odds);
                }
                return results;
            """, section) as? List<String> ?: emptyList()

            for (item in results) {
                val parts = item.split("|||")
                if (parts.size != 2) continue

                val playerName = parts[0]
                val odds = parts[1]

                if (playerName.contains(" & ") || playerName.contains(" and ")) continue

                val decimalOdds = parseOdds(odds) ?: continue
                val (placeOdds, placeDecimal) = calculatePlaceOdds(odds, terms)

                if (placeOdds != null && placeDecimal != null) {
                    players.add(PlayerOdds(playerName, odds, decimalOdds, placeOdds, placeDecimal))
                }
            }
        } catch (e: Exception) {
            println("  William Hill extraction error: ${e.message}")
        }

        return players.distinctBy { it.playerName }
    }
}
