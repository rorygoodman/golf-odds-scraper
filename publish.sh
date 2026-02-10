#!/bin/bash
# Runs the scraper and publishes results to GitHub Pages

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Create output directory
mkdir -p public

# Run the scraper and capture output
echo "Running scraper at $(date)"
./gradlew run --console=plain 2>&1 | tee output.txt || true

# Generate timestamp
TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S %Z')

# Convert ANSI colors to HTML
python3 << 'PYTHON'
import html
import re

with open('output.txt', 'r') as f:
    content = f.read()

# Escape HTML special characters
content = html.escape(content)

# Convert ANSI green to span
content = re.sub(r'\x1b\[32m', '<span class="positive">', content)
content = re.sub(r'\x1b\[0m', '</span>', content)

with open('output_escaped.txt', 'w') as f:
    f.write(content)
PYTHON

OUTPUT=$(cat output_escaped.txt)

# Generate HTML
cat > public/index.html << HTMLEOF
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="refresh" content="600">
  <title>Golf Odds - E/W Arbitrage</title>
  <style>
    body {
      background: #1a1a2e;
      color: #eee;
      font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
      font-size: 12px;
      padding: 20px;
      margin: 0;
    }
    h1 { color: #00d4ff; margin-bottom: 5px; }
    .timestamp { color: #888; margin-bottom: 20px; }
    pre {
      background: #16213e;
      padding: 20px;
      border-radius: 8px;
      overflow-x: auto;
      line-height: 1.4;
    }
    .positive { color: #00ff88; font-weight: bold; }
  </style>
</head>
<body>
  <h1>Golf E/W Arbitrage Opportunities</h1>
  <div class="timestamp">Last updated: ${TIMESTAMP}</div>
  <pre>${OUTPUT}</pre>
</body>
</html>
HTMLEOF

echo "HTML generated at public/index.html"

# Push to gh-pages branch
cd public
git init -q 2>/dev/null || true
git checkout -B gh-pages
git add index.html
git commit -m "Update $(date '+%Y-%m-%d %H:%M')" --allow-empty
git push -f "https://github.com/rorygoodman/golf-odds-scraper.git" gh-pages

echo "Published to GitHub Pages at $(date)"
