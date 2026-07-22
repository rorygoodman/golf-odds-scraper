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
