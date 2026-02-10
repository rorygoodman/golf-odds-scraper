package com.golf.odds

import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions

/**
 * Shared utility functions for odds calculation and web scraping.
 */

/**
 * Creates a headless Chrome WebDriver with standard options for scraping.
 *
 * Includes options to work in CI environments and reduce bot detection.
 *
 * @return Configured ChromeDriver instance
 */
fun createChromeDriver(): WebDriver {
    val options = ChromeOptions().apply {
        addArguments("--headless=new")
        addArguments("--no-sandbox")
        addArguments("--disable-dev-shm-usage")
        addArguments("--disable-gpu")
        addArguments("--window-size=1920,1080")
        addArguments("--disable-blink-features=AutomationControlled")
        addArguments("--disable-extensions")
        addArguments("--disable-infobars")
        addArguments("--remote-allow-origins=*")
        addArguments("--user-data-dir=/tmp/chrome-profile")
        addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        setExperimentalOption("excludeSwitches", listOf("enable-automation"))
        setExperimentalOption("useAutomationExtension", false)
    }
    return ChromeDriver(options)
}

/**
 * Calculates the greatest common divisor of two integers using Euclidean algorithm.
 *
 * @param a First integer
 * @param b Second integer
 * @return Greatest common divisor of a and b
 */
fun gcd(a: Int, b: Int): Int {
    return if (b == 0) a else gcd(b, a % b)
}

/**
 * Parses odds from a string representation to decimal format.
 *
 * Supports fractional odds (e.g., "5/1" -> 6.0) and decimal odds.
 *
 * @param oddsString Odds in string format (fractional or decimal)
 * @return Decimal odds value, or null if parsing fails
 */
fun parseOdds(oddsString: String): Double? {
    return try {
        when {
            oddsString.contains("/") -> {
                val parts = oddsString.split("/")
                (parts[0].toDouble() / parts[1].toDouble()) + 1.0
            }
            else -> oddsString.toDouble()
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Calculates place odds from win odds using each-way terms.
 *
 * For example, with 10/1 win odds and 1/5 E/W terms:
 * Place odds = (10 * 1) / (1 * 5) = 2/1 = 3.0 decimal
 *
 * @param winOdds Fractional win odds string (e.g., "10/1")
 * @param eachWayTerms The each-way terms (fraction and places)
 * @return Pair of (fractional place odds string, decimal place odds), or (null, null) if calculation fails
 */
fun calculatePlaceOdds(winOdds: String, eachWayTerms: EachWayTerms?): Pair<String?, Double?> {
    if (eachWayTerms == null || !winOdds.contains("/")) {
        return Pair(null, null)
    }

    return try {
        val winParts = winOdds.split("/")
        val winNumerator = winParts[0].toInt()
        val winDenominator = winParts[1].toInt()

        val placeParts = eachWayTerms.placeOdds.split("/")
        val placeFractionNum = placeParts[0].toInt()
        val placeFractionDen = placeParts[1].toInt()

        val placeNumerator = winNumerator * placeFractionNum
        val placeDenominator = winDenominator * placeFractionDen

        val divisor = gcd(placeNumerator, placeDenominator)
        val simplifiedNum = placeNumerator / divisor
        val simplifiedDen = placeDenominator / divisor

        val placeFractional = "$simplifiedNum/$simplifiedDen"
        val placeDecimal = (simplifiedNum.toDouble() / simplifiedDen.toDouble()) + 1.0

        Pair(placeFractional, placeDecimal)
    } catch (e: Exception) {
        Pair(null, null)
    }
}
