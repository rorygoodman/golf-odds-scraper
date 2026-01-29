package com.golf.odds

data class Page(
    val url: String,
    val bookmaker: Bookmaker
)

data class Event(
    val name: String,
    val betfairLink: String?,
    val pages: List<Page>
)

data class ScraperConfig(
    val events: List<Event>
)
