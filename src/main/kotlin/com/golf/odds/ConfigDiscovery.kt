package com.golf.odds

import com.google.gson.GsonBuilder
import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.io.File
import java.time.Duration

/**
 * Discover mode: builds config.json for this week's PGA Tour event.
 *
 * Betfair Exchange names PGA Tour events "PGA ...", and the William Hill golf
 * landing page lists only the coming week's competitions, so the event is a
 * Betfair "PGA" event that William Hill also lists. Its Winner / Top 5 /
 * Top 10 markets come from the Betfair event page, and each bookmaker page
 * is the extra-places market with the most places.
 *
 * Usage: `./run.sh --args="discover [--event <name hint>] [--out <path>]"`
 */
fun runDiscovery(args: List<String>) {
    val hint = args.valueAfter("--event")
    val outPath = args.valueAfter("--out") ?: "config.json"

    println("Discovering this week's PGA event")
    println("=".repeat(80))

    print("Betfair golf events... ")
    val betfairEvents = discoverBetfairEvents().filter { it.name.startsWith("PGA ") }
    println(betfairEvents.joinToString { it.name }.ifEmpty { "none" })

    print("William Hill competitions... ")
    val whCompetitions = discoverWilliamHillCompetitions()
    println(whCompetitions.joinToString { it.name }.ifEmpty { "none" })

    val candidates = if (hint != null) {
        betfairEvents.filter { nameMatchScore(hint, it.name) > 0 }
    } else {
        betfairEvents.filter { bf -> whCompetitions.any { nameMatchScore(it.name, bf.name) > 0 } }
    }
    if (candidates.isEmpty()) {
        System.err.println("No PGA event found${hint?.let { " matching \"$it\"" } ?: " for this week"}. " +
            "Betfair PGA events: ${betfairEvents.joinToString { it.name }}. Use --event to pick one.")
        return
    }
    val event = candidates.first()
    println("\nEvent: ${event.name}")
    if (candidates.size > 1) {
        println("  Other candidates: ${candidates.drop(1).joinToString { it.name }} (use --event to pick)")
    }

    print("Betfair markets... ")
    val markets = discoverBetfairMarkets(event.url)
    println(markets.entries.joinToString { "${it.key} ${marketIdFromUrl(it.value)}" }.ifEmpty { "none" })

    val pages = mutableListOf<Page>()
    fun addPage(label: String, discover: () -> DiscoveredPage?) {
        print("$label... ")
        val found = try {
            discover()
        } catch (e: Exception) {
            System.err.println("$label discovery failed: ${e.message}")
            null
        }
        if (found == null) {
            println("not found")
            return
        }
        pages.add(found.page)
        println("${found.chosen.places} places at ${found.chosen.placeFraction} (${found.chosen.title})")
        found.others.forEach { println("  also: ${it.places} places at ${it.placeFraction} (${it.title})") }
    }

    val whCompetition = bestMatch(event.name, whCompetitions) { it.name }
    addPage("William Hill") { whCompetition?.let { discoverWilliamHillPage(it.url) } }
    addPage("Paddy Power") { discoverPaddyPowerPage(event.name) }
    addPage("Ladbrokes") { discoverLadbrokesPage(event.name) }

    val config = ScraperConfig(
        events = listOf(
            Event(
                name = shortEventName(event.name),
                betfairLink = markets[BETFAIR_WINNER],
                betfairTop5Link = markets[BETFAIR_TOP5],
                betfairTop10Link = markets[BETFAIR_TOP10],
                ew = true,
                pages = pages
            )
        )
    )
    File(outPath).writeText(renderConfig(config) + "\n")
    println("\nConfig written to $outPath")
}

/** A named link found on a listing page. */
data class NamedLink(val name: String, val url: String)

/** One each-way outright market and its terms. */
data class EachWayMarket(val title: String, val places: Int, val placeFraction: String)

/** The config page for a bookmaker plus the markets passed over for it. */
data class DiscoveredPage(val page: Page, val chosen: EachWayMarket, val others: List<EachWayMarket>)

const val BETFAIR_WINNER = "Winner"
const val BETFAIR_TOP5 = "Top 5 Finish"
const val BETFAIR_TOP10 = "Top 10 Finish"

private const val BETFAIR_GOLF_URL = "https://www.betfair.com/exchange/plus/golf"
private const val WILLIAM_HILL_GOLF_URL = "https://sports.williamhill.com/betting/en-gb/golf"
private const val PADDY_POWER_GOLF_URL = "https://www.paddypower.com/golf"
private const val LADBROKES_OUTRIGHTS_URL = "https://www.ladbrokes.com/en/sports/sport/golf/outrights"

// --- Site lookups (Selenium) ---

/** Event links from the Betfair Exchange golf page, e.g. "PGA WW Technology Championship 2026". */
fun discoverBetfairEvents(): List<NamedLink> = withPage(BETFAIR_GOLF_URL) { js ->
    linksFromScript(js, """
        return Array.from(document.querySelectorAll('a[href]'))
            .filter(function(a) { return /\/exchange\/plus\/en\/golf\/.+-betting-\d+$/.test(a.href); })
            .map(function(a) { return [a.textContent.trim().replace(/\s+/g, ' '), a.href]; });
    """)
}.distinctBy { it.url }

/**
 * Winner / Top 5 / Top 10 market links from a Betfair event page, keyed by
 * market name. Read from each market's own link: the IDs aren't always in order.
 */
fun discoverBetfairMarkets(eventUrl: String): Map<String, String> = withPage(eventUrl) { js ->
    val links = linksFromScript(js, """
        return Array.from(document.querySelectorAll('a[href]'))
            .filter(function(a) { return /\/market\/1\.\d+/.test(a.href); })
            .map(function(a) { return [a.textContent.trim().replace(/\s+/g, ' '), a.href]; });
    """)
    listOf(BETFAIR_WINNER, BETFAIR_TOP5, BETFAIR_TOP10).mapNotNull { name ->
        links.firstOrNull { it.name == name }?.let { name to it.url.substringBefore("?") }
    }.toMap()
}

/** The coming week's competitions from the William Hill golf page. */
fun discoverWilliamHillCompetitions(): List<NamedLink> = withPage(WILLIAM_HILL_GOLF_URL) { js ->
    linksFromScript(js, """
        var articles = document.querySelectorAll('[data-testid="competitions-outrights-container"] article');
        return Array.from(articles).map(function(article) {
            var name = article.querySelector('h3');
            var link = Array.from(article.querySelectorAll('a[href]')).find(function(a) {
                return a.href.indexOf('/golf/competitions/') !== -1;
            });
            return [name ? name.textContent.trim() : '', link ? link.href : ''];
        });
    """)
}

/** The William Hill winner market with the most places on a competition outrights page. */
fun discoverWilliamHillPage(url: String): DiscoveredPage? = withPage(url) { js ->
    // Sections start collapsed and nest, so open them until nothing else opens
    for (pass in 1..6) {
        val opened = js.executeScript("""
            var triggers = document.querySelectorAll(
                'section[data-testid="sb-ui-collapsible-section"] > button[aria-expanded="false"]');
            for (var i = 0; i < triggers.length; i++) {
                try { triggers[i].click(); } catch (e) {}
            }
            return triggers.length;
        """) as? Long ?: 0L
        if (opened == 0L) break
        Thread.sleep(1500)
    }

    val rows = rowsFromScript(js, """
        var sections = document.querySelectorAll('section[data-testid="sb-ui-collapsible-section"]');
        var out = [];
        for (var i = 0; i < sections.length; i++) {
            var s = sections[i];
            if (s.querySelector('section[data-testid="sb-ui-collapsible-section"]')) continue;
            if (s.querySelectorAll('button[data-testid="bet-button"]').length < 3) continue;
            var title = s.querySelector('[data-testid="sb-ui-collapsible-section-title"]');
            var annotation = s.querySelector('[class*="marketAnnotation"]');
            out.push([title ? title.textContent.trim() : '', annotation ? annotation.textContent.trim() : '']);
        }
        return out;
    """)
    val markets = rows
        .filter { (title, _) -> isWinnerMarket(title) }
        .mapNotNull { (title, annotation) -> parseWilliamHillTerms(title, annotation) }
    pickPage(markets) { Page(url = url, bookmaker = Bookmaker.WILLIAM_HILL, places = it.places, placeFraction = nonDefaultFraction(it)) }
}

/** The Paddy Power winner market with the most places for [eventName]. */
fun discoverPaddyPowerPage(eventName: String): DiscoveredPage? {
    val events = withPage(PADDY_POWER_GOLF_URL) { js ->
        linksFromScript(js, """
            return Array.from(document.querySelectorAll('a[href]'))
                .filter(function(a) { return /paddypower\.com\/golf\/[a-z0-9-]+$/.test(a.href); })
                .map(function(a) { return [a.textContent.trim().replace(/\s+/g, ' '), a.href]; });
        """)
    }
    val event = bestMatch(eventName, events) { it.name } ?: return null

    return withPage(event.url) { js ->
        val rows = rowsFromScript(js, """
            var titles = document.querySelectorAll('.accordion__title');
            return Array.from(titles).map(function(t) {
                var accordion = t.closest('.accordion');
                var terms = accordion && accordion.querySelector('.outright-coupon-card-item__eachway-terms');
                return [t.textContent.trim(), terms ? terms.textContent.trim() : ''];
            });
        """)
        val markets = rows
            .filter { (title, _) -> isWinnerMarket(title) }
            .mapNotNull { (title, terms) -> parsePaddyPowerTerms(title, terms) }
        pickPage(markets) {
            Page(url = event.url, bookmaker = Bookmaker.PADDY_POWER, places = it.places, header = it.title, placeFraction = nonDefaultFraction(it))
        }
    }
}

/** The Ladbrokes outright market for [eventName]; Ladbrokes lists one per event. */
fun discoverLadbrokesPage(eventName: String): DiscoveredPage? {
    val eventUrl = withPage(LADBROKES_OUTRIGHTS_URL, waitMs = 10000) { js ->
        val headers = rowsFromScript(js, """
            return Array.from(document.querySelectorAll('accordion header[data-crlat="containerHeader"]'))
                .map(function(h) { return [h.textContent.trim()]; });
        """).map { it[0] }
        val header = bestMatch(eventName, headers) { it } ?: return@withPage null

        // A competition's events only render once its accordion is opened
        js.executeScript("""
            var headers = document.querySelectorAll('accordion header[data-crlat="containerHeader"]');
            for (var i = 0; i < headers.length; i++) {
                if (headers[i].textContent.trim() === arguments[0]) { headers[i].click(); return; }
            }
        """, header)
        Thread.sleep(3000)
        js.executeScript("""
            var headers = document.querySelectorAll('accordion header[data-crlat="containerHeader"]');
            for (var i = 0; i < headers.length; i++) {
                if (headers[i].textContent.trim() !== arguments[0]) continue;
                var links = headers[i].closest('accordion').querySelectorAll('a[href]');
                for (var j = 0; j < links.length; j++) {
                    if (links[j].href.indexOf('/event/golf/') !== -1) return links[j].href;
                }
                return null;
            }
            return null;
        """, header) as? String
    } ?: return null

    val url = if (eventUrl.endsWith("/main-markets")) eventUrl else eventUrl.trimEnd('/') + "/main-markets"
    return withPage(url, waitMs = 10000) { js ->
        val terms = js.executeScript("""
            var el = document.querySelector('[data-crlat="eachWayContainer"]');
            return el ? el.textContent.trim() : null;
        """) as? String
        val market = terms?.let { parseLadbrokesTerms(it) }
        pickPage(listOfNotNull(market)) { Page(url = url, bookmaker = Bookmaker.LADBROKES, places = it.places, placeFraction = nonDefaultFraction(it)) }
    }
}

/**
 * Loads [url] in a fresh headless Chrome, dismisses the cookie banner and runs
 * [block] against the page.
 */
private fun <T> withPage(url: String, waitMs: Long = 8000, block: (JavascriptExecutor) -> T): T {
    val driver = createChromeDriver()
    try {
        driver.get(url)
        WebDriverWait(driver, Duration.ofSeconds(30))
            .until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")))
        Thread.sleep(waitMs)
        val js = driver as JavascriptExecutor
        try {
            js.executeScript("""
                var accept = document.getElementById('onetrust-accept-btn-handler');
                if (accept) accept.click();
            """)
            Thread.sleep(1500)
        } catch (e: Exception) {
            // No cookie banner
        }
        return block(js)
    } finally {
        driver.quit()
    }
}

/** Runs a script returning an array of string arrays. */
@Suppress("UNCHECKED_CAST")
private fun rowsFromScript(js: JavascriptExecutor, script: String): List<List<String>> =
    (js.executeScript(script) as? List<List<Any?>>).orEmpty()
        .map { row -> row.map { it?.toString().orEmpty() } }

/** Runs a script returning [name, href] pairs, dropping blank ones. */
private fun linksFromScript(js: JavascriptExecutor, script: String): List<NamedLink> =
    rowsFromScript(js, script)
        .filter { it.size >= 2 && it[0].isNotBlank() && it[1].isNotBlank() }
        .map { NamedLink(it[0], it[1]) }

// --- Parsing and matching ---

private val STOP_WORDS = setOf(
    "pga", "dpw", "tour", "golf", "the", "at", "of", "and", "championship", "open",
    "classic", "invitational", "tournament", "winner", "betting", "outright", "outrights"
)

/** Lowercase name words that identify an event, without tour names, years and generic words. */
fun distinctiveTokens(name: String): Set<String> =
    name.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() && it !in STOP_WORDS && !it.all(Char::isDigit) }
        .toSet()

/** Number of distinctive words two event names share. */
fun nameMatchScore(a: String, b: String): Int = distinctiveTokens(a).intersect(distinctiveTokens(b)).size

/** The item whose name shares the most distinctive words with [target], or null if none share any. */
fun <T> bestMatch(target: String, items: List<T>, nameOf: (T) -> String): T? =
    items.map { it to nameMatchScore(target, nameOf(it)) }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first

/** True for an outright winner market, excluding "without X" markets. */
fun isWinnerMarket(title: String): Boolean {
    val t = title.lowercase()
    return "winner" in t && "w/o" !in t && "without" !in t
}

private val FRACTION_REGEX = Regex("""(\d+/\d+)\s*Odds""", RegexOption.IGNORE_CASE)

/**
 * William Hill terms: places from a "... 10 Places" title, otherwise counted
 * from a "1/4 Odds Place 1, 2, 3, 4, 5" annotation; fraction from the annotation.
 */
fun parseWilliamHillTerms(title: String, annotation: String): EachWayMarket? {
    val fraction = FRACTION_REGEX.find(annotation)?.groupValues?.get(1) ?: return null
    val places = Regex("""(\d+)\s*Places?""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.toInt()
        ?: Regex("""Odds\s+Places?\s+([\d,\s]+)""", RegexOption.IGNORE_CASE).find(annotation)
            ?.groupValues?.get(1)?.split(',')?.count { it.isNotBlank() }
        ?: return null
    return EachWayMarket(title, places, fraction)
}

/** Paddy Power terms, e.g. "Each Way: 1/5 Odds, 10 Places". */
fun parsePaddyPowerTerms(title: String, terms: String): EachWayMarket? {
    val match = Regex("""(\d+/\d+)\s*Odds,\s*(\d+)\s*Places""", RegexOption.IGNORE_CASE).find(terms) ?: return null
    return EachWayMarket(title, match.groupValues[2].toInt(), match.groupValues[1])
}

/** Ladbrokes terms, e.g. "EW: 1/5 Odds - Places 1-2-3-4-5-6-7-8". */
fun parseLadbrokesTerms(terms: String): EachWayMarket? {
    val fraction = FRACTION_REGEX.find(terms)?.groupValues?.get(1) ?: return null
    val positions = Regex("""Places\s+([\d\s-]+)""", RegexOption.IGNORE_CASE).find(terms)?.groupValues?.get(1) ?: return null
    val places = positions.split('-').count { it.isNotBlank() }
    if (places == 0) return null
    return EachWayMarket(terms, places, fraction)
}

/** Picks the market with the most places and builds its config page. */
fun pickPage(markets: List<EachWayMarket>, toPage: (EachWayMarket) -> Page): DiscoveredPage? {
    val chosen = markets.maxByOrNull { it.places } ?: return null
    return DiscoveredPage(toPage(chosen), chosen, markets.filter { it !== chosen })
}

/** The place fraction for the config, or null when it's the scraper default. */
fun nonDefaultFraction(market: EachWayMarket): String? =
    market.placeFraction.takeUnless { it == DEFAULT_PLACE_FRACTION }

/** "PGA WW Technology Championship 2026" -> "PGA WW Technology"; "PGA Mexico Open 2026" -> "PGA Mexico". */
fun shortEventName(betfairName: String): String {
    val words = betfairName.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
    val trailing = setOf("championship", "open", "classic", "invitational", "tournament")
    while (words.size > 2 && (words.last().all(Char::isDigit) || words.last().lowercase() in trailing)) {
        words.removeAt(words.size - 1)
    }
    return words.joinToString(" ")
}

/** Renders the config in the file's existing style: 2-space indent, nulls omitted. */
fun renderConfig(config: ScraperConfig): String =
    GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(config)

private fun List<String>.valueAfter(flag: String): String? =
    indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }
