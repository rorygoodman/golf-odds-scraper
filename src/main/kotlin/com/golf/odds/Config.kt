package com.golf.odds

/**
 * Represents a bookmaker page to scrape.
 *
 * @property url The URL of the bookmaker's event page
 * @property bookmaker The bookmaker identifier
 */
data class Page(
    val url: String,
    val bookmaker: Bookmaker,
    val places: Int? = null
)

/**
 * Represents a golf event with associated bookmaker and Betfair pages.
 *
 * @property name Human-readable name of the event
 * @property betfairLink URL to the Betfair Winner market (optional)
 * @property betfairTop10Link URL to the Betfair Top 10 Finish market (optional)
 * @property pages List of bookmaker pages to scrape
 */
data class Event(
    val name: String,
    val betfairLink: String?,
    val betfairTop5Link: String,
    val betfairTop10Link: String,
    val pages: List<Page>
)

/**
 * Top-level configuration for the scraper.
 *
 * @property events List of events to process
 */
data class ScraperConfig(
    val events: List<Event>
)
