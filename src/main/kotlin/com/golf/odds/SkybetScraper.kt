package com.golf.odds

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebDriver
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.support.ui.ExpectedConditions
import java.time.Duration

/**
 * Scraper for extracting golf betting odds from Sky Bet website.
 * Targets a direct market page URL (e.g. outright-winner-10-places).
 *
 * @property url The Sky Bet market page URL to scrape
 */
class SkybetScraper(private val url: String) {
    private var driver: WebDriver? = null

    fun scrape(): EventOdds {
        try {
            driver = createChromeDriver()
            driver!!.get(url)
            waitForPageLoad()

            val js = driver as JavascriptExecutor
            val eventName = extractEventName(js)
            val eachWayTerms = extractEachWayTerms(js)
            val players = extractPlayerOdds(js, eachWayTerms)

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

    private fun waitForPageLoad() {
        val wait = WebDriverWait(driver!!, Duration.ofSeconds(15))
        wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")))
        Thread.sleep(5000)

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
                var overlay = document.querySelector('.onetrust-pc-dark-filter');
                if (overlay) overlay.remove();
                var banner = document.getElementById('onetrust-banner-sdk');
                if (banner) banner.remove();
            """)
            Thread.sleep(2000)
        } catch (e: Exception) {
            // No cookie popup
        }
    }

    private fun extractEventName(js: JavascriptExecutor): String {
        return try {
            js.executeScript("""
                var h1 = document.querySelector('h1');
                if (h1) return h1.textContent.trim();
                return 'Unknown Event';
            """) as? String ?: "Unknown Event"
        } catch (e: Exception) {
            "Unknown Event"
        }
    }

    private fun extractEachWayTerms(js: JavascriptExecutor): EachWayTerms? {
        return try {
            val text = js.executeScript("""
                var els = document.querySelectorAll('*');
                for (var i = 0; i < els.length; i++) {
                    var t = els[i].textContent || '';
                    if ((t.match(/each\s*way.*\d+\/\d+.*\d+\s*place/i) ||
                         t.match(/\d+\/\d+.*\d+\s*place/i) ||
                         t.match(/\d+\s*places?\s*at\s*\d+\/\d+/i)) && t.length < 100) {
                        return t;
                    }
                }
                return null;
            """) as? String ?: return null

            val fraction = Regex("""(\d+/\d+)""").find(text)?.value ?: return null
            val places = Regex("""(\d+)\s*places?""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null

            EachWayTerms(fraction, places)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPlayerOdds(js: JavascriptExecutor, eachWayTerms: EachWayTerms?): List<PlayerOdds> {
        val players = mutableListOf<PlayerOdds>()

        try {
            // Extract directly from the preloaded catalog data
            @Suppress("UNCHECKED_CAST")
            val results = js.executeScript("""
                var catalog = window.__TBD_PRELOADED_CATALOG__;
                if (!catalog || !catalog.data) return [];

                var data = catalog.data;
                var markets = data.SportsbookMarket;
                var liveDataMap = data.SportsbookRunnerLiveData;
                if (!markets || !liveDataMap) return [];

                // Find market with runners
                var market = null;
                var marketKeys = Object.keys(markets);
                for (var i = 0; i < marketKeys.length; i++) {
                    if (markets[marketKeys[i]].runners) {
                        market = markets[marketKeys[i]];
                        break;
                    }
                }
                if (!market) return [];

                // Build selectionId -> odds lookup from live data
                var oddsById = {};
                var liveKeys = Object.keys(liveDataMap);
                for (var i = 0; i < liveKeys.length; i++) {
                    var ld = liveDataMap[liveKeys[i]];
                    if (ld && ld.odds && ld.odds.fractional) {
                        oddsById[ld.selectionId] = ld.odds.fractional;
                    }
                }

                var results = [];
                for (var i = 0; i < market.runners.length; i++) {
                    var runner = market.runners[i];
                    var name = runner.name;
                    if (!name) continue;

                    var frac = oddsById[runner.selectionId];
                    if (!frac) continue;

                    results.push(name + '|||' + frac.numerator + '/' + frac.denominator);
                }

                return results;
            """) as? List<String> ?: emptyList()

            println("  Skybet catalog results: ${results.size} runners")

            // Fallback to DOM scraping if catalog extraction fails
            val finalResults = if (results.isEmpty()) extractFromDom(js) else results

            for (item in finalResults) {
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
            println("  Skybet extraction error: ${e.message}")
        }

        return players.distinctBy { it.playerName }
    }

    private fun extractFromDom(js: JavascriptExecutor): List<String> {
        println("  Skybet catalog empty, falling back to DOM")

        // Click Show More to expand all runners
        try {
            js.executeScript("window.scrollTo(0, document.body.scrollHeight);")
            Thread.sleep(1500)
            js.executeScript("""
                var buttons = document.querySelectorAll('button');
                for (var i = 0; i < buttons.length; i++) {
                    var text = buttons[i].textContent.trim().toLowerCase();
                    if (text.includes('show more') || text.includes('show all') || text.includes('view more')) {
                        buttons[i].scrollIntoView({block: 'center'});
                        buttons[i].click();
                        break;
                    }
                }
            """)
            Thread.sleep(3000)
            for (i in 1..5) {
                js.executeScript("window.scrollTo(0, document.body.scrollHeight * $i / 5);")
                Thread.sleep(800)
            }
        } catch (e: Exception) {
            // Ignore expand errors
        }

        @Suppress("UNCHECKED_CAST")
        val results = js.executeScript("""
            var results = [];
            var rows = document.querySelectorAll('div[class*="runnerLine"]:not([class*="grid"])');

            for (var i = 0; i < rows.length; i++) {
                var nameEl = rows[i].querySelector('p[class*="runnerName"]');
                if (!nameEl) continue;

                var name = nameEl.textContent.trim();
                if (!name) continue;

                // Single market page - first bet button has the odds we want
                var oddsEl = rows[i].querySelector('[class*="betButtonWrapper"] span[class*="label"]');
                if (!oddsEl) continue;

                var odds = oddsEl.textContent.trim();
                if (odds && odds.match(/^\d+\/\d+$/)) {
                    results.push(name + '|||' + odds);
                }
            }
            return results;
        """) as? List<String> ?: emptyList()

        println("  Skybet DOM results: ${results.size} runners")
        return results
    }
}