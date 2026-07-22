# Betfair Exchange via API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Selenium-based `BetfairScraper` with the Betfair Exchange REST API (login → listMarketCatalogue → listMarketBook), keeping the existing `BetfairEventOdds`/`PlayerLayPrice` output shapes so downstream calculators are untouched.

**Architecture:** Port the proven Kotlin Betfair client from horsey-scraper's git history (`BetfairClient.kt` @ `fe25d26`, `Credentials.kt` @ `0975866` in `/Users/rorygoodman/git-repos/horsey-scraper`) into `com.golf.odds`. Market IDs are extracted from the exchange URLs already in `config.json` (e.g. `.../market/1.260239900`). Per event: one catalogue call (runner names) + one book call (best lay prices), joined into `BetfairEventOdds`.

**Tech Stack:** Kotlin 1.9.21 / JVM 17 / Gradle 9.0.0. `java.net.http.HttpClient` (JDK built-in) + Gson 2.10.1 (already a dependency). Tests: `kotlin.test` on JUnit Platform (new — this repo currently has no tests).

## Global Constraints

- Config format (`config.json`) is UNCHANGED — market IDs are parsed out of the existing `betfairLink`/`betfairTop5Link`/`betfairTop10Link` URLs.
- Credentials file: `~/.golf-scraper/credentials.json`, JSON object with string fields `username`, `password`, `appKey`.
- `BetfairEventOdds` and `PlayerLayPrice` keep their exact current shapes (downstream matches players by name via `normalizePlayerName`).
- Current behavior to preserve: players without a lay offer are skipped; lay prices must be `> 1.0`; a failed/missing/non-OPEN market prints FAILED and the calculators skip accordingly.
- No retries in the client — a failed call is a failed market.
- All new code in package `com.golf.odds`, files under `src/main/kotlin/com/golf/odds/` and `src/test/kotlin/com/golf/odds/`.
- Run tests with `./gradlew test --tests '<ClassName>'` from the repo root.

---

### Task 1: Test infrastructure + Credentials

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/test/kotlin/com/golf/odds/CredentialsTest.kt`
- Create: `src/main/kotlin/com/golf/odds/Credentials.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class Credentials(val username: String, val password: String, val appKey: String)`, `fun parseCredentials(json: String): Credentials` (throws `IllegalArgumentException`), `fun defaultCredentialsPath(): Path` (= `~/.golf-scraper/credentials.json`), `fun loadCredentials(path: Path): Credentials` (throws `IllegalArgumentException`).

- [ ] **Step 1: Add test framework to build.gradle.kts**

In `build.gradle.kts`, add to the `dependencies` block:

```kotlin
    testImplementation(kotlin("test"))
    // Gradle 9 requires explicit junit-platform-console for useJUnitPlatform() test task discovery
    testRuntimeOnly("org.junit.platform:junit-platform-console:1.9.3")
```

And add after the `dependencies` block:

```kotlin
tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/com/golf/odds/CredentialsTest.kt`:

```kotlin
package com.golf.odds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CredentialsTest {
    @Test
    fun `parseCredentials reads all three fields`() {
        val json = """{ "username": "u", "password": "p", "appKey": "k" }"""
        assertEquals(Credentials("u", "p", "k"), parseCredentials(json))
    }

    @Test
    fun `parseCredentials ignores extra fields`() {
        val json = """{ "username": "u", "password": "p", "appKey": "k", "note": "x" }"""
        assertEquals(Credentials("u", "p", "k"), parseCredentials(json))
    }

    @Test
    fun `parseCredentials lists every missing field in one message`() {
        val e = assertFailsWith<IllegalArgumentException> {
            parseCredentials("""{ "username": "u" }""")
        }
        assertTrue("password" in (e.message ?: ""), e.message)
        assertTrue("appKey" in (e.message ?: ""), e.message)
    }

    @Test
    fun `parseCredentials rejects non-string fields`() {
        val e = assertFailsWith<IllegalArgumentException> {
            parseCredentials("""{ "username": 1, "password": "p", "appKey": "k" }""")
        }
        assertTrue("username" in (e.message ?: ""), e.message)
    }

    @Test
    fun `parseCredentials rejects malformed JSON`() {
        assertFailsWith<IllegalArgumentException> { parseCredentials("not json") }
    }

    @Test
    fun `defaultCredentialsPath points at golf-scraper dir`() {
        assertTrue(defaultCredentialsPath().toString().endsWith(".golf-scraper/credentials.json"))
    }

    @Test
    fun `loadCredentials errors with path in message when file missing`() {
        val e = assertFailsWith<IllegalArgumentException> {
            loadCredentials(java.nio.file.Paths.get("/nonexistent/credentials.json"))
        }
        assertTrue("/nonexistent/credentials.json" in (e.message ?: ""), e.message)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests 'com.golf.odds.CredentialsTest'`
Expected: FAIL to compile — `Unresolved reference: Credentials` (compilation error counts as the failing state).

- [ ] **Step 4: Write the implementation**

Create `src/main/kotlin/com/golf/odds/Credentials.kt` (port of horsey-scraper's `Credentials.kt` @ `0975866`; only the package and default path change):

```kotlin
package com.golf.odds

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFileAttributeView

data class Credentials(
    val username: String,
    val password: String,
    val appKey: String,
)

/**
 * Pure parser. Accepts a JSON object with string fields `username`,
 * `password`, `appKey`. Extra fields are ignored. Missing fields throw
 * `IllegalArgumentException` listing every offender in one message.
 */
fun parseCredentials(json: String): Credentials {
    val root: JsonObject = try {
        JsonParser.parseString(json).asJsonObject
    } catch (e: Exception) {
        throw IllegalArgumentException("credentials JSON is not a valid object: ${e.message}")
    }
    val missing = mutableListOf<String>()
    fun stringOrMiss(key: String): String? {
        val el = root.get(key)
        if (el == null || !el.isJsonPrimitive || !el.asJsonPrimitive.isString) {
            missing += key; return null
        }
        return el.asString
    }
    val username = stringOrMiss("username")
    val password = stringOrMiss("password")
    val appKey   = stringOrMiss("appKey")
    require(missing.isEmpty()) {
        "credentials JSON missing or non-string fields: ${missing.joinToString(",")}"
    }
    return Credentials(username!!, password!!, appKey!!)
}

/** Default path: `~/.golf-scraper/credentials.json`. */
fun defaultCredentialsPath(): Path =
    Paths.get(System.getProperty("user.home"), ".golf-scraper", "credentials.json")

/**
 * Reads and parses the credentials file at [path]. Errors with the path
 * embedded for easy debugging. If the file mode is wider than `0600` on a
 * POSIX filesystem, prints a single warning to stderr and continues.
 */
fun loadCredentials(path: Path): Credentials {
    if (!Files.exists(path)) {
        throw IllegalArgumentException("credentials file not found: $path")
    }
    warnIfWorldReadable(path)
    val json = try {
        Files.readString(path)
    } catch (e: Exception) {
        throw IllegalArgumentException("failed to read $path: ${e.message}")
    }
    return parseCredentials(json)
}

private fun warnIfWorldReadable(path: Path) {
    val view = Files.getFileAttributeView(
        path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS
    ) ?: return
    val perms = view.readAttributes().permissions()
    val tooOpen = perms.any { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }
    if (tooOpen) {
        System.err.println("Warning: $path is readable by group/others; recommend `chmod 600`.")
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'com.golf.odds.CredentialsTest'`
Expected: BUILD SUCCESSFUL, 7 tests pass.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/main/kotlin/com/golf/odds/Credentials.kt src/test/kotlin/com/golf/odds/CredentialsTest.kt
git commit -m "Add test framework + Betfair credentials loader (~/.golf-scraper)"
```

---

### Task 2: Betfair response parsers + request-body builders

**Files:**
- Create: `src/main/kotlin/com/golf/odds/BetfairResponses.kt`
- Test: `src/test/kotlin/com/golf/odds/BetfairResponsesTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces (all top-level in `BetfairResponses.kt`):
  - `enum class MarketBookStatus { OPEN, OTHER }`
  - `data class MarketBookSnapshot(val status: MarketBookStatus, val layBySelectionId: Map<Long, Double?>)`
  - `data class CatalogueMarket(val marketId: String, val eventName: String, val runners: Map<Long, String>)`
  - `fun parseSsoid(json: String): String` — throws `IllegalStateException`
  - `fun buildLoginBody(username: String, password: String): String`
  - `fun marketIdFromUrl(url: String): String` — throws `IllegalArgumentException`
  - `fun buildMarketIdsCatalogueBody(marketIds: List<String>): String`
  - `fun buildBookBody(marketIds: List<String>): String`
  - `fun parseCatalogueMarkets(json: String): Map<String, CatalogueMarket>` — keyed by marketId
  - `fun parseBookMarkets(json: String): Map<String, MarketBookSnapshot>` — keyed by marketId

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/golf/odds/BetfairResponsesTest.kt`:

```kotlin
package com.golf.odds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BetfairResponsesTest {
    // --- parseSsoid ---
    @Test
    fun `parseSsoid extracts token on SUCCESS`() {
        val json = """{ "token": "abc123", "status": "SUCCESS", "error": "" }"""
        assertEquals("abc123", parseSsoid(json))
    }

    @Test
    fun `parseSsoid throws on non-SUCCESS with status in message`() {
        val json = """{ "token": "", "status": "INVALID_USERNAME_OR_PASSWORD", "error": "" }"""
        val e = assertFailsWith<IllegalStateException> { parseSsoid(json) }
        assertTrue("INVALID_USERNAME_OR_PASSWORD" in (e.message ?: ""), e.message)
    }

    @Test
    fun `parseSsoid mentions 2FA hint on LOGIN_RESTRICTED`() {
        val json = """{ "token": "", "status": "LOGIN_RESTRICTED", "error": "" }"""
        val e = assertFailsWith<IllegalStateException> { parseSsoid(json) }
        assertTrue("2FA" in (e.message ?: ""), "expected 2FA hint: ${e.message}")
    }

    @Test
    fun `parseSsoid throws on malformed JSON`() {
        assertFailsWith<IllegalStateException> { parseSsoid("not json") }
    }

    @Test
    fun `parseSsoid throws when token absent on SUCCESS`() {
        assertFailsWith<IllegalStateException> { parseSsoid("""{ "status": "SUCCESS" }""") }
    }

    // --- buildLoginBody ---
    @Test
    fun `buildLoginBody url-encodes both fields`() {
        assertEquals("username=a%40b.com&password=p%26q", buildLoginBody("a@b.com", "p&q"))
    }

    // --- marketIdFromUrl ---
    @Test
    fun `marketIdFromUrl extracts id from exchange link`() {
        assertEquals(
            "1.260239900",
            marketIdFromUrl("https://www.betfair.com/exchange/plus/golf/market/1.260239900")
        )
    }

    @Test
    fun `marketIdFromUrl tolerates trailing path or query`() {
        assertEquals(
            "1.260239901",
            marketIdFromUrl("https://www.betfair.com/exchange/plus/golf/market/1.260239901?priceHistory=1")
        )
    }

    @Test
    fun `marketIdFromUrl throws with url in message when unparseable`() {
        val e = assertFailsWith<IllegalArgumentException> {
            marketIdFromUrl("https://www.betfair.com/exchange/plus/en/golf-betting-3")
        }
        assertTrue("golf-betting-3" in (e.message ?: ""), e.message)
    }

    // --- body builders ---
    @Test
    fun `buildMarketIdsCatalogueBody filters by marketIds with runner projection`() {
        val body = buildMarketIdsCatalogueBody(listOf("1.1", "1.2"))
        assertTrue(""""marketIds":["1.1","1.2"]""" in body, body)
        assertTrue(""""EVENT"""" in body, body)
        assertTrue(""""RUNNER_DESCRIPTION"""" in body, body)
        assertTrue(""""maxResults":"2"""" in body, body)
    }

    @Test
    fun `buildMarketIdsCatalogueBody rejects empty list`() {
        assertFailsWith<IllegalArgumentException> { buildMarketIdsCatalogueBody(emptyList()) }
    }

    @Test
    fun `buildBookBody requests EX_BEST_OFFERS`() {
        val body = buildBookBody(listOf("1.1"))
        assertTrue(""""marketIds":["1.1"]""" in body, body)
        assertTrue(""""EX_BEST_OFFERS"""" in body, body)
    }

    @Test
    fun `buildBookBody rejects empty and oversize lists`() {
        assertFailsWith<IllegalArgumentException> { buildBookBody(emptyList()) }
        assertFailsWith<IllegalArgumentException> { buildBookBody(List(41) { "1.$it" }) }
    }

    // --- parseCatalogueMarkets ---
    @Test
    fun `parseCatalogueMarkets maps marketId to event name and runners`() {
        val json = """
        [
          {
            "marketId": "1.260239900",
            "marketName": "Winner",
            "event": { "id": "34141234", "name": "3M Open 2026" },
            "runners": [
              { "selectionId": 47999, "runnerName": "Rory McIlroy", "sortPriority": 1 },
              { "selectionId": 48000, "runnerName": "Scottie Scheffler", "sortPriority": 2 }
            ]
          }
        ]
        """.trimIndent()
        val markets = parseCatalogueMarkets(json)
        val m = markets.getValue("1.260239900")
        assertEquals("3M Open 2026", m.eventName)
        assertEquals(mapOf(47999L to "Rory McIlroy", 48000L to "Scottie Scheffler"), m.runners)
    }

    @Test
    fun `parseCatalogueMarkets defaults eventName when event missing`() {
        val json = """[ { "marketId": "1.1", "runners": [] } ]"""
        assertEquals("Golf Betting", parseCatalogueMarkets(json).getValue("1.1").eventName)
    }

    @Test
    fun `parseCatalogueMarkets skips entries without marketId and runners without names`() {
        val json = """
        [
          { "runners": [] },
          { "marketId": "1.2", "runners": [ { "selectionId": 5 }, { "runnerName": "X" } ] }
        ]
        """.trimIndent()
        val markets = parseCatalogueMarkets(json)
        assertEquals(setOf("1.2"), markets.keys)
        assertTrue(markets.getValue("1.2").runners.isEmpty())
    }

    @Test
    fun `parseCatalogueMarkets throws on non-array response`() {
        assertFailsWith<IllegalStateException> { parseCatalogueMarkets("""{"error":"x"}""") }
    }

    // --- parseBookMarkets ---
    @Test
    fun `parseBookMarkets extracts best lay per selection`() {
        val json = """
        [
          {
            "marketId": "1.260239900",
            "status": "OPEN",
            "runners": [
              {
                "selectionId": 47999,
                "ex": { "availableToLay": [ { "price": 8.4, "size": 120.0 }, { "price": 8.6, "size": 50.0 } ] }
              },
              { "selectionId": 48000, "ex": { "availableToLay": [] } }
            ]
          }
        ]
        """.trimIndent()
        val books = parseBookMarkets(json)
        val snap = books.getValue("1.260239900")
        assertEquals(MarketBookStatus.OPEN, snap.status)
        assertEquals(8.4, snap.layBySelectionId.getValue(47999L))
        assertNull(snap.layBySelectionId.getValue(48000L))
    }

    @Test
    fun `parseBookMarkets collapses non-OPEN status to OTHER with no prices`() {
        val json = """[ { "marketId": "1.1", "status": "SUSPENDED", "runners": [ { "selectionId": 1 } ] } ]"""
        val snap = parseBookMarkets(json).getValue("1.1")
        assertEquals(MarketBookStatus.OTHER, snap.status)
        assertTrue(snap.layBySelectionId.isEmpty())
    }

    @Test
    fun `parseBookMarkets throws on non-array response`() {
        assertFailsWith<IllegalStateException> { parseBookMarkets("""{"error":"x"}""") }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'com.golf.odds.BetfairResponsesTest'`
Expected: FAIL to compile — `Unresolved reference: parseSsoid` etc.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/golf/odds/BetfairResponses.kt`. `parseSsoid`, `buildLoginBody`, `layPricesFromBook` (here as a private helper of `parseBookMarkets`), and `buildBookBody` are ports of horsey-scraper's `BetfairResponses.kt` @ `fe25d26`; `marketIdFromUrl`, `buildMarketIdsCatalogueBody`, `parseCatalogueMarkets`, and `parseBookMarkets` are new for golf's fetch-by-market-ID flow:

```kotlin
package com.golf.odds

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class MarketBookStatus { OPEN, OTHER }

data class MarketBookSnapshot(
    val status: MarketBookStatus,
    /** selectionId → best lay price; value is `null` when `availableToLay` is empty. */
    val layBySelectionId: Map<Long, Double?>,
)

/** One market's catalogue entry: event name plus selectionId → runner name. */
data class CatalogueMarket(
    val marketId: String,
    val eventName: String,
    val runners: Map<Long, String>,
)

/**
 * Parses the response body of `POST /api/login` and returns the ssoid token.
 * Throws `IllegalStateException` on any non-`SUCCESS` status or malformed
 * JSON. The error message includes the status string and, for the very
 * common `LOGIN_RESTRICTED` case, a hint about 2FA being incompatible with
 * interactive login.
 */
fun parseSsoid(json: String): String {
    val root: JsonObject = try {
        JsonParser.parseString(json).asJsonObject
    } catch (e: Exception) {
        throw IllegalStateException("login response is not a valid JSON object: ${e.message}")
    }
    val statusEl = root.get("status")
    val status = if (statusEl != null && statusEl.isJsonPrimitive) statusEl.asString else "UNKNOWN"
    if (status != "SUCCESS") {
        val hint = if (status == "LOGIN_RESTRICTED")
            " — this likely means 2FA is enabled on the account. 2FA must be disabled for interactive login, or switch to cert-based login."
            else ""
        throw IllegalStateException("login failed with status=$status$hint")
    }
    val tokenEl = root.get("token")
    return if (tokenEl != null && tokenEl.isJsonPrimitive) tokenEl.asString
           else throw IllegalStateException("login response has SUCCESS status but no token")
}

/**
 * Builds the `application/x-www-form-urlencoded` body for the interactive
 * login endpoint. URL-encodes both fields.
 */
fun buildLoginBody(username: String, password: String): String {
    fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
    return "username=${enc(username)}&password=${enc(password)}"
}

private val MARKET_ID_REGEX = Regex("""/market/(1\.\d+)""")

/**
 * Extracts the market ID (e.g. `1.260239900`) from a Betfair exchange URL
 * like `https://www.betfair.com/exchange/plus/golf/market/1.260239900`.
 * Throws `IllegalArgumentException` naming the URL when it doesn't match.
 */
fun marketIdFromUrl(url: String): String =
    MARKET_ID_REGEX.find(url)?.groupValues?.get(1)
        ?: throw IllegalArgumentException("cannot extract market ID from Betfair URL: $url")

/**
 * Builds the JSON body for `listMarketCatalogue` filtered to exactly
 * [marketIds], with EVENT + RUNNER_DESCRIPTION projections (event name and
 * selectionId → runnerName).
 */
fun buildMarketIdsCatalogueBody(marketIds: List<String>): String {
    require(marketIds.isNotEmpty()) { "buildMarketIdsCatalogueBody: marketIds must not be empty" }
    val root = JsonObject().apply {
        add("filter", JsonObject().apply {
            add("marketIds", JsonArray().apply { marketIds.forEach { add(it) } })
        })
        add("marketProjection", JsonArray().apply {
            add("EVENT")
            add("RUNNER_DESCRIPTION")
        })
        addProperty("maxResults", marketIds.size.toString())
    }
    return root.toString()
}

/** Builds the JSON body for `listMarketBook` over up to 40 marketIds. */
fun buildBookBody(marketIds: List<String>): String {
    require(marketIds.size in 1..40) {
        "buildBookBody: marketIds size must be 1..40 (got ${marketIds.size})"
    }
    val root = JsonObject().apply {
        add("marketIds", JsonArray().apply { marketIds.forEach { add(it) } })
        add("priceProjection", JsonObject().apply {
            add("priceData", JsonArray().apply { add("EX_BEST_OFFERS") })
        })
    }
    return root.toString()
}

/**
 * Parses a `listMarketCatalogue` response (JSON array) into
 * marketId → [CatalogueMarket]. Entries without a marketId are skipped;
 * runners without a selectionId or runnerName are skipped; a missing event
 * name falls back to "Golf Betting" (cosmetic — downstream uses the config
 * event name).
 */
fun parseCatalogueMarkets(json: String): Map<String, CatalogueMarket> {
    val arr = try {
        JsonParser.parseString(json).asJsonArray
    } catch (e: Exception) {
        throw IllegalStateException("catalogue response is not a JSON array: ${e.message}")
    }
    val out = linkedMapOf<String, CatalogueMarket>()
    for (el in arr) {
        if (!el.isJsonObject) continue
        val m = el.asJsonObject
        val marketId = m.get("marketId")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
        val eventName = m.get("event")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "Golf Betting"
        val runners = linkedMapOf<Long, String>()
        val runnersEl = m.get("runners")?.takeIf { it.isJsonArray }?.asJsonArray
        if (runnersEl != null) {
            for (rEl in runnersEl) {
                if (!rEl.isJsonObject) continue
                val r = rEl.asJsonObject
                val sel = r.get("selectionId")?.takeIf { it.isJsonPrimitive }?.asLong ?: continue
                val name = r.get("runnerName")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                runners[sel] = name
            }
        }
        out[marketId] = CatalogueMarket(marketId, eventName, runners)
    }
    return out
}

/**
 * Parses a `listMarketBook` response (JSON array) into
 * marketId → [MarketBookSnapshot]. Status is `OPEN` only when the market is
 * live; anything else (`SUSPENDED`, `CLOSED`, unknown) collapses to `OTHER`
 * and the caller treats the market as a failed scrape.
 */
fun parseBookMarkets(json: String): Map<String, MarketBookSnapshot> {
    val arr = try {
        JsonParser.parseString(json).asJsonArray
    } catch (e: Exception) {
        throw IllegalStateException("book response is not a JSON array: ${e.message}")
    }
    val out = linkedMapOf<String, MarketBookSnapshot>()
    for (el in arr) {
        if (!el.isJsonObject) continue
        val m = el.asJsonObject
        val marketId = m.get("marketId")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
        out[marketId] = layPricesFromBook(m)
    }
    return out
}

private fun layPricesFromBook(root: JsonObject): MarketBookSnapshot {
    val status = if (root.get("status")?.asString == "OPEN") MarketBookStatus.OPEN
                 else MarketBookStatus.OTHER
    if (status != MarketBookStatus.OPEN) {
        return MarketBookSnapshot(status, emptyMap())
    }
    val runners = root.get("runners")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: return MarketBookSnapshot(status, emptyMap())
    val out = linkedMapOf<Long, Double?>()
    for (rEl in runners) {
        if (!rEl.isJsonObject) continue
        val r = rEl.asJsonObject
        val sel = r.get("selectionId")?.takeIf { it.isJsonPrimitive }?.asLong ?: continue
        val ex = r.get("ex")?.takeIf { it.isJsonObject }?.asJsonObject
        val lays = ex?.get("availableToLay")?.takeIf { it.isJsonArray }?.asJsonArray
        val firstPrice: Double? = lays?.firstOrNull { it.isJsonObject }
            ?.asJsonObject?.get("price")?.takeIf { it.isJsonPrimitive }?.asDouble
        out[sel] = firstPrice
    }
    return MarketBookSnapshot(status, out)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'com.golf.odds.BetfairResponsesTest'`
Expected: BUILD SUCCESSFUL, 20 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/golf/odds/BetfairResponses.kt src/test/kotlin/com/golf/odds/BetfairResponsesTest.kt
git commit -m "Add Betfair response parsers + request-body builders"
```

---

### Task 3: BetfairClient

**Files:**
- Create: `src/main/kotlin/com/golf/odds/BetfairClient.kt`

**Interfaces:**
- Consumes: `parseSsoid(json)`, `buildLoginBody(username, password)` from Task 2.
- Produces: `class BetfairClient(appKey: String)` with `fun login(username: String, password: String)`, `fun listMarketCatalogue(body: String): String`, `fun listMarketBook(body: String): String`. All throw `IllegalStateException` on HTTP/login failure.

No unit tests — this is thin I/O over `java.net.http.HttpClient` (mirrors horsey, whose client is also untested). The compile + Task 4's usage cover it; the real-run task exercises it for real.

- [ ] **Step 1: Write the implementation**

Create `src/main/kotlin/com/golf/odds/BetfairClient.kt` (port of horsey-scraper's `BetfairClient.kt` @ `fe25d26`; only the package changes):

```kotlin
package com.golf.odds

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val LOGIN_URL     = "https://identitysso.betfair.com/api/login"
private const val CATALOGUE_URL = "https://api.betfair.com/exchange/betting/rest/v1.0/listMarketCatalogue/"
private const val BOOK_URL      = "https://api.betfair.com/exchange/betting/rest/v1.0/listMarketBook/"

/**
 * Thin REST client for the three Betfair Exchange endpoints we use.
 *
 * Construct with the app key only. Call [login] once with username/password
 * — it stores the returned ssoid and uses it for every subsequent call.
 *
 * Errors:
 * - Login failures throw `IllegalStateException` (status surfaced).
 * - HTTP errors (non-2xx) throw `IllegalStateException` with the status code
 *   and the first 500 chars of the body.
 *
 * Retries: none. The caller decides what to drop on transient failures.
 */
class BetfairClient(
    private val appKey: String,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    private var ssoid: String? = null

    fun login(username: String, password: String) {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(LOGIN_URL))
            .timeout(Duration.ofSeconds(15))
            .header("X-Application", appKey)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(buildLoginBody(username, password)))
            .build()
        val body = sendForBody(req)
        ssoid = parseSsoid(body)
    }

    fun listMarketCatalogue(body: String): String {
        val req = bettingRequest(CATALOGUE_URL, body)
        return sendForBody(req)
    }

    fun listMarketBook(body: String): String {
        val req = bettingRequest(BOOK_URL, body)
        return sendForBody(req)
    }

    private fun bettingRequest(url: String, body: String): HttpRequest {
        val token = ssoid ?: error("BetfairClient: must call login() before betting endpoints")
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("X-Application", appKey)
            .header("X-Authentication", token)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun sendForBody(req: HttpRequest): String {
        val res: HttpResponse<String> = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() / 100 != 2) {
            val snip = res.body().take(500)
            error("HTTP ${res.statusCode()} from ${req.uri()}: $snip")
        }
        return res.body()
    }
}
```

- [ ] **Step 2: Verify it compiles and existing tests still pass**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (27 tests from Tasks 1-2 still pass).

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/golf/odds/BetfairClient.kt
git commit -m "Add BetfairClient (ported from horsey-scraper)"
```

---

### Task 4: BetfairApiFetcher (models move + join + fetch)

**Files:**
- Create: `src/main/kotlin/com/golf/odds/BetfairModels.kt`
- Modify: `src/main/kotlin/com/golf/odds/BetfairScraper.kt` (remove the two data classes — lines 11-35, i.e. `PlayerLayPrice` and `BetfairEventOdds` with their KDoc)
- Create: `src/main/kotlin/com/golf/odds/BetfairApiFetcher.kt`
- Test: `src/test/kotlin/com/golf/odds/BetfairApiFetcherTest.kt`

**Interfaces:**
- Consumes: `BetfairClient` (Task 3); `CatalogueMarket`, `MarketBookSnapshot`, `MarketBookStatus`, `marketIdFromUrl`, `buildMarketIdsCatalogueBody`, `buildBookBody`, `parseCatalogueMarkets`, `parseBookMarkets` (Task 2).
- Produces:
  - `BetfairModels.kt`: `data class PlayerLayPrice(val playerName: String, val price: Double)` and `data class BetfairEventOdds(val eventName: String, val url: String, val players: List<PlayerLayPrice>, val scrapedAt: String = ...)` — the EXACT existing shapes, just moved out of `BetfairScraper.kt`.
  - `fun buildEventOdds(url: String, catalogue: CatalogueMarket?, book: MarketBookSnapshot?): BetfairEventOdds?`
  - `class BetfairApiFetcher(client: BetfairClient)` with `fun fetchMarkets(urls: List<String>): Map<String, BetfairEventOdds>` (keyed by config URL; failed/missing/non-OPEN markets absent).

- [ ] **Step 1: Move the data classes to BetfairModels.kt**

Create `src/main/kotlin/com/golf/odds/BetfairModels.kt`:

```kotlin
package com.golf.odds

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
 * Represents lay prices for one Betfair golf market.
 *
 * @property eventName Name of the golf event
 * @property url Source URL from the config
 * @property players List of player lay prices
 * @property scrapedAt Timestamp when data was fetched
 */
data class BetfairEventOdds(
    val eventName: String,
    val url: String,
    val players: List<PlayerLayPrice>,
    val scrapedAt: String = java.time.LocalDateTime.now().toString()
)
```

Then in `src/main/kotlin/com/golf/odds/BetfairScraper.kt`, delete the two data-class declarations and their KDoc blocks (lines 11-35: everything from `/**` above `data class PlayerLayPrice` through the closing `)` of `BetfairEventOdds`). The `BetfairScraper` class itself stays for now (deleted in Task 5).

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — same package, so `BetfairScraper` and the calculators resolve the moved classes unchanged.

- [ ] **Step 2: Write the failing test**

Create `src/test/kotlin/com/golf/odds/BetfairApiFetcherTest.kt` (tests the pure join; the `fetchMarkets` plumbing is exercised by the real run):

```kotlin
package com.golf.odds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BetfairApiFetcherTest {
    private val url = "https://www.betfair.com/exchange/plus/golf/market/1.260239900"
    private val catalogue = CatalogueMarket(
        marketId = "1.260239900",
        eventName = "3M Open 2026",
        runners = linkedMapOf(1L to "Rory McIlroy", 2L to "Scottie Scheffler", 3L to "Shane Lowry"),
    )

    @Test
    fun `buildEventOdds joins names with lay prices and skips missing lays`() {
        val book = MarketBookSnapshot(
            MarketBookStatus.OPEN,
            mapOf(1L to 8.4, 2L to null, 3L to 25.0),
        )
        val odds = buildEventOdds(url, catalogue, book)!!
        assertEquals("3M Open 2026", odds.eventName)
        assertEquals(url, odds.url)
        assertEquals(
            listOf(PlayerLayPrice("Rory McIlroy", 8.4), PlayerLayPrice("Shane Lowry", 25.0)),
            odds.players,
        )
    }

    @Test
    fun `buildEventOdds skips lay prices at or below 1_0`() {
        val book = MarketBookSnapshot(MarketBookStatus.OPEN, mapOf(1L to 1.0, 2L to 1.01))
        val odds = buildEventOdds(url, catalogue, book)!!
        assertEquals(listOf(PlayerLayPrice("Scottie Scheffler", 1.01)), odds.players)
    }

    @Test
    fun `buildEventOdds skips selections absent from the book`() {
        val book = MarketBookSnapshot(MarketBookStatus.OPEN, mapOf(1L to 8.4))
        val odds = buildEventOdds(url, catalogue, book)!!
        assertEquals(listOf(PlayerLayPrice("Rory McIlroy", 8.4)), odds.players)
    }

    @Test
    fun `buildEventOdds returns null when market not OPEN`() {
        val book = MarketBookSnapshot(MarketBookStatus.OTHER, emptyMap())
        assertNull(buildEventOdds(url, catalogue, book))
    }

    @Test
    fun `buildEventOdds returns null when catalogue or book missing`() {
        val book = MarketBookSnapshot(MarketBookStatus.OPEN, mapOf(1L to 8.4))
        assertNull(buildEventOdds(url, null, book))
        assertNull(buildEventOdds(url, catalogue, null))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests 'com.golf.odds.BetfairApiFetcherTest'`
Expected: FAIL to compile — `Unresolved reference: buildEventOdds`.

- [ ] **Step 4: Write the implementation**

Create `src/main/kotlin/com/golf/odds/BetfairApiFetcher.kt`:

```kotlin
package com.golf.odds

/**
 * Fetches golf market lay prices from the Betfair Exchange API.
 * Replaces the Selenium-based BetfairScraper.
 */
class BetfairApiFetcher(private val client: BetfairClient) {

    /**
     * Fetches all [urls] (Betfair exchange market links from the config) in
     * one listMarketCatalogue call + one listMarketBook call.
     *
     * @return map of config URL → [BetfairEventOdds]. URLs whose market is
     *   missing from the response or not OPEN are absent from the map — the
     *   caller treats those as failed markets.
     * @throws IllegalArgumentException if a URL has no parseable market ID
     * @throws IllegalStateException on HTTP or response-shape errors
     */
    fun fetchMarkets(urls: List<String>): Map<String, BetfairEventOdds> {
        if (urls.isEmpty()) return emptyMap()
        val idByUrl = urls.associateWith { marketIdFromUrl(it) }
        val marketIds = idByUrl.values.distinct()
        val catalogues = parseCatalogueMarkets(
            client.listMarketCatalogue(buildMarketIdsCatalogueBody(marketIds))
        )
        val books = parseBookMarkets(client.listMarketBook(buildBookBody(marketIds)))
        val out = linkedMapOf<String, BetfairEventOdds>()
        for ((url, marketId) in idByUrl) {
            val odds = buildEventOdds(url, catalogues[marketId], books[marketId])
            if (odds != null) out[url] = odds
        }
        return out
    }
}

/**
 * Joins one market's catalogue (runner names) and book (lay prices) into
 * [BetfairEventOdds]. Returns null when either half is missing or the market
 * is not OPEN. Runners with no lay offer, or a lay at/below 1.0, are skipped
 * (matches the old scraper's `n > 1` filter).
 */
fun buildEventOdds(
    url: String,
    catalogue: CatalogueMarket?,
    book: MarketBookSnapshot?,
): BetfairEventOdds? {
    if (catalogue == null || book == null) return null
    if (book.status != MarketBookStatus.OPEN) return null
    val players = catalogue.runners.mapNotNull { (sel, name) ->
        val lay = book.layBySelectionId[sel]
        if (lay != null && lay > 1.0) PlayerLayPrice(name, lay) else null
    }
    return BetfairEventOdds(eventName = catalogue.eventName, url = url, players = players)
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests 'com.golf.odds.BetfairApiFetcherTest'`
Expected: BUILD SUCCESSFUL, 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/golf/odds/BetfairModels.kt src/main/kotlin/com/golf/odds/BetfairScraper.kt src/main/kotlin/com/golf/odds/BetfairApiFetcher.kt src/test/kotlin/com/golf/odds/BetfairApiFetcherTest.kt
git commit -m "Add BetfairApiFetcher; move Betfair models to their own file"
```

---

### Task 5: Wire Main.kt to the API, delete the Selenium scraper

**Files:**
- Modify: `src/main/kotlin/com/golf/odds/Main.kt` (lines 26-107, the `config.events.forEach` block; plus a new helper)
- Delete: `src/main/kotlin/com/golf/odds/BetfairScraper.kt`
- Modify: `README.md` (lines 8, 77, 97 — Betfair mentions)

**Interfaces:**
- Consumes: `loadCredentials`, `defaultCredentialsPath` (Task 1); `BetfairClient` (Task 3); `BetfairApiFetcher.fetchMarkets(urls): Map<String, BetfairEventOdds>` (Task 4).
- Produces: nothing new — behavior change only. Console output keeps per-market `N players` / `FAILED` lines; `data.json` shape unchanged.

- [ ] **Step 1: Rewire Main.kt**

In `src/main/kotlin/com/golf/odds/Main.kt`, add this helper at the bottom of the file:

```kotlin
/**
 * Loads credentials from ~/.golf-scraper/credentials.json and logs in to
 * the Betfair API. Returns null (with a stderr message) if credentials are
 * missing or login fails — bookmaker scraping still runs; Betfair-dependent
 * calculations are skipped per market as before.
 */
fun createBetfairFetcher(): BetfairApiFetcher? {
    return try {
        val credentials = loadCredentials(defaultCredentialsPath())
        val client = BetfairClient(credentials.appKey)
        client.login(credentials.username, credentials.password)
        BetfairApiFetcher(client)
    } catch (e: Exception) {
        System.err.println("Betfair API unavailable: ${e.message}")
        null
    }
}
```

In `main()`, after `val config = loadConfig(configPath)` (line 22), add:

```kotlin
    print("Logging in to Betfair API... ")
    val fetcher = createBetfairFetcher()
    println(if (fetcher != null) "OK" else "FAILED")
```

Then inside the `config.events.forEach { event ->` block, replace everything from `var winnerMarketOdds: BetfairEventOdds? = null` (line 43) through the end of the Betfair scraping in the `else` branch — specifically: (a) the winner-scraping block at lines 43-53, and (b) the `top5MarketOdds`/`top10MarketOdds` scraping blocks at lines 66-88 inside the `else` branch. Replace (a) with:

```kotlin
        val betfairUrls = listOfNotNull(
            event.betfairLink.takeUnless { it.isNullOrBlank() },
            event.betfairTop5Link.takeUnless { it.isNullOrBlank() },
            event.betfairTop10Link.takeUnless { it.isNullOrBlank() },
        )
        var betfairOdds: Map<String, BetfairEventOdds> = emptyMap()
        if (fetcher != null && betfairUrls.isNotEmpty()) {
            betfairOdds = try {
                fetcher.fetchMarkets(betfairUrls)
            } catch (e: Exception) {
                System.err.println("Betfair fetch failed: ${e.message}")
                emptyMap()
            }
        }

        fun betfairMarket(label: String, link: String?): BetfairEventOdds? {
            if (link.isNullOrBlank()) return null
            val odds = betfairOdds[link]
            println("Betfair $label... " + (odds?.let { "${it.players.size} players" } ?: "FAILED"))
            return odds
        }

        val winnerMarketOdds = betfairMarket("Winner", event.betfairLink)
```

and replace the two scraping blocks in (b) with:

```kotlin
            val top5MarketOdds = betfairMarket("Top 5", event.betfairTop5Link)
            val top10MarketOdds = betfairMarket("Top 10", event.betfairTop10Link)
```

The rest of the branch logic (`if (!event.ew)` comparison, `LayableEWCalculator` call, missing-market messages, JSON assembly) is unchanged — `winnerMarketOdds`/`top5MarketOdds`/`top10MarketOdds` keep their names and `BetfairEventOdds?` types, they just become `val`s populated from `betfairOdds` instead of `var`s populated by Selenium scrapes.

- [ ] **Step 2: Delete the Selenium scraper**

```bash
git rm src/main/kotlin/com/golf/odds/BetfairScraper.kt
```

- [ ] **Step 3: Verify compile + full test suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all 32 tests pass, no remaining references to `BetfairScraper`.

Also run: `grep -rn 'BetfairScraper' src/` — Expected: no matches.

- [ ] **Step 4: Update README**

In `README.md`:
- Line 8: `- Scrapes Betfair exchange lay prices from Winner and Top 10 markets` → `- Fetches Betfair exchange lay prices from Winner, Top 5, and Top 10 markets via the Betfair API`
- Line 77 (sources table): change the Betfair Exchange row's notes from `Lay prices, virtual scroll` to `Lay prices, via API`
- Line 97 (project structure): `BetfairScraper.kt       # Betfair exchange scraper (scroll + extract)` → `BetfairApiFetcher.kt    # Betfair exchange lay prices (REST API)`
- Add a "Betfair API credentials" subsection under Setup (after the config.json section around line 67):

```markdown
### Betfair API credentials

Create `~/.golf-scraper/credentials.json` (mode 600) with your Betfair API
credentials:

```json
{ "username": "...", "password": "...", "appKey": "..." }
```

Same account/app key as horsey-scraper — copy its `~/.horsey-scraper/credentials.json`.
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/golf/odds/Main.kt README.md
git commit -m "Fetch Betfair markets via API; drop Selenium exchange scraper"
```

---

### Task 6: Credentials setup + real-run verification + Pi deploy

**Files:**
- No source changes expected (fixes only if the real run exposes a bug).

**Interfaces:**
- Consumes: the complete pipeline from Tasks 1-5.
- Produces: verified `data.json` from a real API run; credentials installed on Mac + Pi; Pi repo updated.

- [ ] **Step 1: Install credentials on the Mac**

```bash
mkdir -p ~/.golf-scraper
cp ~/.horsey-scraper/credentials.json ~/.golf-scraper/credentials.json
chmod 600 ~/.golf-scraper/credentials.json
```

- [ ] **Step 2: Real run**

Note the previous Selenium-era baseline from git-committed `data.json`: event `3M Open`, 283 opportunities, timestamp `2026-07-21 20:20:03 IST`.

Run: `./gradlew run`
Expected: console shows `Logging in to Betfair API... OK`, then per-market lines like `Betfair Winner... ~150 players` (player counts same order of magnitude as the exchange site shows), bookmaker scrapes proceed as before, and `JSON written to data.json`.

NOTE: geo-blocking of betfair.com from the Mac was historically an issue for the *website* (see project memory) — if login fails from the Mac with a geo error, that is environmental, not a code bug; verification then happens on the Pi in Step 4.

- [ ] **Step 3: Compare output**

```bash
python3 -c "
import json
d = json.load(open('data.json'))
for e in d['events']:
    print(e['eventName'], '- opportunities:', len(e.get('opportunities', [])))"
```

Expected: `3M Open` present with an opportunity count in the same ballpark as the 283 baseline (market prices move, so exact equality is not expected; zero or a collapse to a handful indicates a name-matching or price-extraction bug — investigate before proceeding). Spot-check a few player names in `data.json` against the Betfair site.

Do NOT commit `data.json` unless the repo's existing workflow expects it (publish.sh handles data publication).

- [ ] **Step 4: Deploy to the Pi**

```bash
ssh rory@192.168.0.38 'mkdir -p ~/.golf-scraper && cp ~/.horsey-scraper/credentials.json ~/.golf-scraper/credentials.json && chmod 600 ~/.golf-scraper/credentials.json'
git push
ssh rory@192.168.0.38 'cd ~/golf-odds-scraper && git pull'
```

Expected: credentials in place on the Pi; Pi repo at the new commit. The next 20-minute cron cycle exercises the full publish path.

- [ ] **Step 5: Verify the Pi cron run**

After the next cron slot (≤20 min):

```bash
ssh rory@192.168.0.38 'tail -40 ~/.golf-scraper/publish.log'
```

Expected: `Logging in to Betfair API... OK`, per-market player counts, `JSON written to data.json`, successful gh-pages push. If login fails on the Pi, check the error status (`LOGIN_RESTRICTED` → 2FA hint; geo issues should not occur — the Pi is in Ireland and runs horsey's API login daily).
