
# Golf Odds Scraper

A Kotlin-based scraper for analyzing golf betting odds across different bookmakers to identify value bets and hedge opportunities.

## Overview

This project scrapes golf tournament odds from various bookmakers to:
- Create summaries of odds for golf outright bets
- Calculate each-way place odds for top finishes
- Highlight potential profitable bets
- Identify hedge opportunities

## Features

- **Configuration-based scraping**: Define events and bookmakers in a simple JSON config file
- **Each-way betting support**: Automatically calculates place odds based on bookmaker terms
- **Multiple bookmaker support**: Extensible architecture to support multiple bookmakers
- **Headless browser automation**: Uses Selenium to scrape JavaScript-rendered pages
- **Fractional and decimal odds**: Displays both formats for easy comparison

## Prerequisites

The following tools are required to run the scraper:

### Installation via Homebrew (macOS)

```bash
# Install Java (OpenJDK)
brew install openjdk@11

# Install Gradle
brew install gradle

# Install ChromeDriver
brew install chromedriver
```

### Verify Installation

```bash
# Check Java version (should be 11 or higher)
java -version

# Check Gradle version
gradle --version

# Check ChromeDriver location
which chromedriver
```

## Configuration

The scraper uses a `config.json` file to define which events and pages to scrape.

### Config Structure

```json
{
  "events": [
    {
      "name": "Event Name",
      "pages": [
        {
          "url": "https://bookmaker.com/event-url",
          "bookmaker": "LADBROKES"
        }
      ]
    }
  ]
}
```

### Config Fields

- **events**: Array of golf events to scrape
  - **name**: Display name for the event (e.g., "2026 US Masters")
  - **pages**: Array of bookmaker pages to scrape for this event
    - **url**: Full URL to the betting page
    - **bookmaker**: Enum value identifying the bookmaker (currently: `LADBROKES`)

### Example Configuration

```json
{
  "events": [
    {
      "name": "2026 US Masters",
      "pages": [
        {
          "url": "https://www.ladbrokes.com/en/sports/event/golf/golf/us-masters/2026-us-masters/250076402/main-markets",
          "bookmaker": "LADBROKES"
        }
      ]
    },
    {
      "name": "2026 The Open",
      "pages": [
        {
          "url": "https://www.ladbrokes.com/en/sports/event/golf/golf/the-open/2026-the-open/123456789/main-markets",
          "bookmaker": "LADBROKES"
        }
      ]
    }
  ]
}
```

## Usage

### Run the scraper

```bash
gradle run
```

### Run with a custom config file

```bash
gradle run --args="path/to/config.json"
```

## Output

The scraper displays:

1. **Event information**: Name, URL, and scrape timestamp
2. **Each-way terms**: Place odds fraction and number of paying places
3. **Player odds**: For each player:
   - Player name
   - Win odds (fractional and decimal)
   - Place odds (fractional and decimal)

### Sample Output

```
Event: 2026 US Masters
URL: https://www.ladbrokes.com/...
Scraped at: 2025-12-25T18:20:55.344618

Each-Way Terms:
  Place Odds: 1/4 of win odds
  Paying Places: 5

Player Odds (91 players):
--------------------------------------------------------------------------------
Scottie Scheffler                   7/2 (4.50) | Place:    7/8 (1.88)
Rory McIlroy                       13/2 (7.50) | Place:   13/8 (2.63)
Ludvig Aberg                       12/1 (13.00) | Place:    3/1 (4.00)
...
```

## Project Structure

```
golf-odds-scraper/
├── config.json                         # Configuration file
├── build.gradle.kts                    # Gradle build configuration
├── settings.gradle.kts                 # Project settings
└── src/main/kotlin/com/golf/odds/
    ├── Main.kt                         # Main orchestration and config loading
    ├── Bookmaker.kt                    # Bookmaker enum
    ├── Config.kt                       # Configuration data classes
    ├── LadbrokesScraper.kt            # Ladbrokes-specific scraper
    └── [Future scrapers...]
```

## Adding New Bookmakers

To add support for a new bookmaker:

1. **Add enum value** in `Bookmaker.kt`:
   ```kotlin
   enum class Bookmaker {
       LADBROKES,
       BETFAIR  // New bookmaker
   }
   ```

2. **Create scraper class** (e.g., `BetfairScraper.kt`):
   ```kotlin
   class BetfairScraper(private val url: String) {
       fun scrape(): EventOdds {
           // Implement scraping logic
       }
   }
   ```

3. **Add case to routing** in `Main.kt`:
   ```kotlin
   fun scrapeEvent(page: Page): EventOdds? {
       return when (page.bookmaker) {
           Bookmaker.LADBROKES -> LadbrokesScraper(page.url).scrape()
           Bookmaker.BETFAIR -> BetfairScraper(page.url).scrape()
       }
   }
   ```

4. **Update config.json** with new bookmaker pages

## How It Works

1. **Configuration Loading**: Reads `config.json` to get events and pages
2. **Page Routing**: For each page, routes to the appropriate scraper based on bookmaker enum
3. **Web Scraping**: Uses Selenium with headless Chrome to:
   - Load the betting page
   - Click "Show All" to expand all players
   - Extract player names from `data-crlat="oddsNames"` elements
   - Extract odds from `data-crlat="betButton"` elements
   - Extract each-way terms from `data-crlat="eachWayContainer"`
4. **Odds Calculation**: Calculates place odds using each-way terms
5. **Display**: Formats and prints all data

## Current Limitations

- Only supports Ladbrokes bookmaker
- Requires ChromeDriver to be installed
- Assumes fractional odds format
- UK-specific betting terms

## Future Enhancements

- Support for additional bookmakers (Bet365, William Hill, etc.)
- Odds comparison across bookmakers
- Arbitrage opportunity detection
- Historical odds tracking
- Value bet identification based on statistical models
- Export to CSV/JSON for further analysis
- API mode for programmatic access
