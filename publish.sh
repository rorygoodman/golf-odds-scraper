#!/bin/bash
# Runs the scraper and publishes results to GitHub Pages

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Pull latest changes from master
echo "Pulling latest changes..."
git pull --ff-only origin master || true

# Create output directory
mkdir -p public

# Run the scraper and capture output
echo "Running scraper at $(date)"
./gradlew run --console=plain 2>&1 | tee output.txt || true

# Copy JSON data to public folder
if [ -f data.json ]; then
    cp data.json public/data.json
    echo "Copied data.json to public/"
fi

# Copy the HTML frontend
cp index.html public/index.html

echo "Files generated in public/"

# Push to gh-pages branch
cd public
git init -q 2>/dev/null || true
git checkout -B gh-pages
git add -A
git commit -m "Update $(date '+%Y-%m-%d %H:%M')" --allow-empty
git push -f "https://github.com/rorygoodman/golf-odds-scraper.git" gh-pages

echo "Published to GitHub Pages at $(date)"
