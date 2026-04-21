package com.golf.odds

/**
 * Normalizes a player name for matching across different sources.
 *
 * Strips all non-alphanumeric characters (whitespace, punctuation, dots,
 * apostrophes, hyphens) and lowercases the result so bookmakers' stylistic
 * differences don't prevent a match. Never use for display — only as a key.
 */
fun normalizePlayerName(name: String): String {
    return name.replace(Regex("[^\\p{L}\\p{N}]"), "").lowercase()
}
