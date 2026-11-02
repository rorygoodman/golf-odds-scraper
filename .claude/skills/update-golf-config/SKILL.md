---
name: update-golf-config
description: Update config.json for this week's main PGA Tour event by running the scraper's discover mode, which finds the Betfair Exchange Winner/Top 5/Top 10 markets and the extra-places each-way markets at William Hill, Paddy Power and Ladbrokes. Use when the user asks to set up the config for this week's golf, update the golf config, or find this week's extra places.
---

# Update golf config for this week's PGA event

The scraper has a `discover` mode (`src/main/kotlin/com/golf/odds/ConfigDiscovery.kt`)
that does the lookups in headless Chrome via Selenium and rewrites `config.json`.
Don't use the Claude in Chrome extension: it blocks betting sites.

## 1. Run discover

```bash
./gradlew run --console=plain -q --args="discover"
```

If Gradle can't find a Java 17 toolchain (as on the Mac), add
`-Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
The run takes about 1–2 minutes. Ignore the Selenium `CdpVersionFinder` warnings.

Options:
- `--event "<name hint>"` picks a specific event, e.g. `--event "Mexico"`.
  Use it when the output lists "Other candidates" and the first pick isn't
  the main event (opposite-field weeks), or when nothing was found.
- `--out <path>` writes somewhere other than `config.json`.

How it chooses: a Betfair "PGA ..." event that William Hill also lists on its
golf page (which only shows the coming week's events). For each bookmaker it
takes the winner market with the most places, and it writes `placeFraction`
only when it isn't the 1/5 default.

## 2. Check the result

- If a bookmaker printed `not found`, or a Betfair market is missing, say so.
  The config will just leave it out.
- If the event or a market looks wrong, rerun with `--event`. If a site's
  layout seems to have changed, fix the matching lookup in `ConfigDiscovery.kt`.
- Optionally check that the scrapers read the new config: `./gradlew run --console=plain -q`.
  Each bookmaker should report around 100+ players. Betfair fails without
  `~/.golf-scraper/credentials.json`, which is expected off the cron machine.
  This run overwrites `data.json` only when Betfair succeeds.

## 3. Report

Show the user a short table: each bookmaker with its places and fraction, plus
any other markets listed as `also:`. Then show `git diff config.json`.

Don't commit or push unless asked. Remind the user that the cron publisher
hard-resets to `origin/master`, so the change only takes effect once it has been
pushed. If they ask you to commit, use a short message naming the event, like
past commits (e.g. `Mexico`).
