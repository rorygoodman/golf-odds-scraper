# Golf Odds Scraper

A Kotlin-based scraper for finding each-way arbitrage opportunities in golf betting.

## What it does

- Scrapes outright winner odds from multiple bookmakers (Ladbrokes, Paddy Power, Boylesports)
- Scrapes Betfair exchange lay prices from Winner and Top 10 markets
- Calculates E/W arbitrage opportunities by comparing bookmaker back prices against Betfair lay prices
- Shows edge percentages for each player/bookmaker combination
- Identifies players profitable at multiple bookmakers

## Prerequisites

- Java 19+
- Google Chrome
- ChromeDriver

```bash
# macOS
brew install openjdk@19
brew install chromedriver
```

## Usage

```bash
./run.sh
```

Or with a custom config:

```bash
./run.sh --args="path/to/config.json"
```

## Configuration

Edit `config.json` to set up events, bookmaker pages, and Betfair links:

```json
{
  "events": [
    {
      "name": "2026 Pebble",
      "betfairLink": "https://www.betfair.com/exchange/plus/en/golf/event-betting-123",
      "betfairTop10Link": "https://www.betfair.com/exchange/plus/golf/market/123",
      "pages": [
        {
          "url": "https://www.ladbrokes.com/en/sports/event/golf/...",
          "bookmaker": "LADBROKES"
        },
        {
          "url": "https://www.paddypower.com/golf/...",
          "bookmaker": "PADDY_POWER"
        },
        {
          "url": "https://www.boylesports.com/sports/golf/...",
          "bookmaker": "BOYLESPORTS"
        }
      ]
    }
  ]
}
```

Both `betfairLink` (Winner market) and `betfairTop10Link` (Top 10 Finish market) are required for E/W arbitrage calculations.

### Supported bookmakers

| Bookmaker | Enum value | Notes |
|-----------|-----------|-------|
| Ladbrokes | `LADBROKES` | Fractional odds, each-way terms |
| Paddy Power | `PADDY_POWER` | Fractional odds, each-way terms |
| Boylesports | `BOYLESPORTS` | Fractional odds, each-way terms |
| 10Bet | `TEN_BET` | Fractional odds, each-way terms |
| Betfair Exchange | via `betfairLink` | Lay prices, virtual scroll |

## Adding a new bookmaker

1. Add a value to the `Bookmaker` enum in `Bookmaker.kt`
2. Create a scraper class that returns `EventOdds` (see `LadbrokesScraper.kt` or `PaddyPowerScraper.kt`)
3. Wire it up in `scrapeEvent()` in `Main.kt`
4. Add the URL detection in `LayableEWCalculator.kt` and `OddsComparison.kt`

## Project structure

```
src/main/kotlin/com/golf/odds/
  Main.kt                 # Entry point, config loading, orchestration
  Config.kt               # Config data classes
  Bookmaker.kt            # Bookmaker enum
  LadbrokesScraper.kt     # Ladbrokes scraper + shared data classes (EventOdds, PlayerOdds)
  PaddyPowerScraper.kt    # Paddy Power scraper
  BoylesportsScraper.kt   # Boylesports scraper
  TenBetScraper.kt        # 10Bet scraper
  BetfairScraper.kt       # Betfair exchange scraper (scroll + extract)
  LayableEWCalculator.kt  # E/W arbitrage calculation and output
  OddsComparison.kt       # Cross-bookmaker comparison utilities
```
