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
