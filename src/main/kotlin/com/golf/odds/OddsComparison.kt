package com.golf.odds

/**
 * Normalizes a player name for matching across different sources.
 *
 * Removes extra whitespace and converts to lowercase to handle
 * variations in name formatting between bookmakers.
 *
 * @param name The player name to normalize
 * @return Normalized player name
 */
fun normalizePlayerName(name: String): String {
    return name.trim().replace(Regex("\\s+"), " ").lowercase()
}
