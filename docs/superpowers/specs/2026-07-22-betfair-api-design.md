# Betfair Exchange via API (replacing Selenium scrape)

**Date:** 2026-07-22
**Status:** Approved

## Goal

Replace the Selenium-based `BetfairScraper` (fragile virtual-scroll scraping of
betfair.com exchange pages) with the Betfair Exchange REST API, the same way
horsey-scraper does it. Use the same Betfair account/app key as horsey-scraper,
but from this project's own credentials file.

## Background

- horsey-scraper's Python Betfair client was ported *from* Kotlin. The original
  Kotlin sources are recoverable from horsey-scraper git history:
  - `BetfairClient.kt` — commit `fe25d26`
  - `Credentials.kt` — commit `0975866`
- The golf config (`config.json`) already carries the market IDs inside the
  exchange URLs, e.g. `https://www.betfair.com/exchange/plus/golf/market/1.260239900`.
- Downstream consumers (`LayableEWCalculator`, `WinOnlyCalculator`,
  `OddsComparison`, JSON output) only depend on the `BetfairEventOdds` /
  `PlayerLayPrice` data classes, so the swap is contained.

## Components

### Credentials.kt (new)

Port of horsey's `Credentials.kt`:

- Reads `~/.golf-scraper/credentials.json` — a JSON object with string fields
  `username`, `password`, `appKey`. Extra fields ignored.
- Missing file or missing/non-string fields → clear failure message listing
  the problem.
- Warns on stderr if the file is group/other-readable (recommend `chmod 600`).

Setup (both machines — Mac and Pi):
`cp ~/.horsey-scraper/credentials.json ~/.golf-scraper/credentials.json && chmod 600 ~/.golf-scraper/credentials.json`

### BetfairClient.kt (new)

Ported nearly verbatim from horsey history (package renamed to
`com.golf.odds`). Thin REST client over `java.net.http.HttpClient`:

- `login(username, password)` → POST `https://identitysso.betfair.com/api/login`
  (form-encoded, `X-Application` header), parses `status`/`token`, stores ssoid.
  Non-SUCCESS status → error (with the 2FA hint on `LOGIN_RESTRICTED`).
- `listMarketCatalogue(body)` / `listMarketBook(body)` → POST to
  `https://api.betfair.com/exchange/betting/rest/v1.0/...` with
  `X-Application` + `X-Authentication` headers.
- Non-2xx → error with status code and first 500 chars of body.
- No retries — a failed market prints FAILED and the calculators skip the
  event, exactly as today.

### BetfairApiFetcher (new, replaces BetfairScraper)

- `marketIdFromUrl(url)` — extracts the `1.xxxxxxxxx` market ID from an
  exchange URL. Unparseable URL → error naming the URL.
- Per event, makes **one** catalogue call and **one** book call covering all
  configured markets (Winner, Top 5, Top 10):
  - `listMarketCatalogue` with `filter: {marketIds: [...]}`,
    `marketProjection: [EVENT, RUNNER_DESCRIPTION]` → per market:
    selectionId→runnerName map, plus event name.
  - `listMarketBook` with `priceProjection: {priceData: [EX_BEST_OFFERS]}` →
    per market: best available-to-lay price per selectionId (first offer).
- Joins to produce one `BetfairEventOdds` per market with `PlayerLayPrice`
  entries. Selections with no lay offer are skipped (matches current
  behavior). `url` field keeps the config URL; `eventName` comes from the
  catalogue's event name (cosmetic — downstream uses the config event name).
- A market missing from the response, or with a non-OPEN status, is treated
  as a failed scrape for that market (null), matching today's per-market
  FAILED handling.

### Main.kt changes

- Load credentials + construct client + `login()` once per run, before the
  event loop. Login failure → print error and skip all Betfair fetches
  (bookmaker scraping still runs; calculators will report the missing
  markets as they do now).
- Replace the three `BetfairScraper(link).scrape()` blocks with calls into
  `BetfairApiFetcher`.
- `config.json` format unchanged — still paste exchange URLs.

### Cleanup

- Delete `BetfairScraper.kt`. Selenium stays for the bookmaker scrapers.

## Testing

- Unit tests for the pure parts, mirroring horsey's style: market-ID
  extraction from URLs, login response parsing, catalogue parsing
  (selectionId→name), book parsing (best lay, missing lay, non-OPEN status),
  request-body builders.
- Real-run verification: run the scraper against the current config and
  compare per-market player counts against the Selenium-era `data.json`
  before calling it done.

## Risks

- Runner-name mismatch between API and site-rendered names: unlikely (the
  site renders API names), verified by the real run.
- Interactive login geo/2FA issues: known-good — horsey-scraper uses the same
  account interactively from the same Pi.

## Deployment

- Copy credentials file on the Mac and the Pi (see Credentials section).
- No cron/publish.sh changes; the jar keeps doing what it did, minus one
  Selenium session.
