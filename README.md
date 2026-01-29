# Golf Odds Scraper

A Kotlin-based scraper for comparing golf betting odds across bookmakers and the Betfair exchange.

## What it does

- Scrapes outright winner odds from multiple bookmakers (Ladbrokes, Paddy Power)
- Scrapes Betfair exchange lay prices with virtual scroll support
- Calculates each-way place odds from bookmaker terms
- Compares odds across bookmakers to highlight the best available prices

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
      "name": "2026 Farmers",
      "betfairLink": "https://www.betfair.com/exchange/plus/en/golf-betting-3",
      "pages": [
        {
          "url": "https://www.ladbrokes.com/en/sports/event/golf/...",
          "bookmaker": "LADBROKES"
        },
        {
          "url": "https://www.paddypower.com/golf/...",
          "bookmaker": "PADDY_POWER"
        }
      ]
    }
  ]
}
```

### Supported bookmakers

| Bookmaker | Enum value | Notes |
|-----------|-----------|-------|
| Ladbrokes | `LADBROKES` | Fractional odds, each-way terms |
| Paddy Power | `PADDY_POWER` | Fractional odds, each-way terms |
| 10Bet | `TEN_BET` | Fractional odds, each-way terms |
| Betfair Exchange | via `betfairLink` | Lay prices, virtual scroll |

## Adding a new bookmaker

1. Add a value to the `Bookmaker` enum in `Bookmaker.kt`
2. Create a scraper class that returns `EventOdds` (see `LadbrokesScraper.kt` or `PaddyPowerScraper.kt`)
3. Wire it up in `scrapeEvent()` in `Main.kt`
4. Add the URL detection in `OddsComparison.kt`

## Project structure

```
src/main/kotlin/com/golf/odds/
  Main.kt                 # Entry point, config loading, orchestration
  Config.kt               # Config data classes
  Bookmaker.kt            # Bookmaker enum
  LadbrokesScraper.kt     # Ladbrokes scraper + shared data classes (EventOdds, PlayerOdds)
  PaddyPowerScraper.kt    # Paddy Power scraper
  TenBetScraper.kt        # 10Bet scraper
  BetfairScraper.kt       # Betfair exchange scraper (scroll + extract)
  OddsComparison.kt       # Cross-bookmaker comparison
```
