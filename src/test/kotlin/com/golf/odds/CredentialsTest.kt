package com.golf.odds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CredentialsTest {
    @Test
    fun `parseCredentials reads all three fields`() {
        val json = """{ "username": "u", "password": "p", "appKey": "k" }"""
        assertEquals(Credentials("u", "p", "k"), parseCredentials(json))
    }

    @Test
    fun `parseCredentials ignores extra fields`() {
        val json = """{ "username": "u", "password": "p", "appKey": "k", "note": "x" }"""
        assertEquals(Credentials("u", "p", "k"), parseCredentials(json))
    }

    @Test
    fun `parseCredentials lists every missing field in one message`() {
        val e = assertFailsWith<IllegalArgumentException> {
            parseCredentials("""{ "username": "u" }""")
        }
        assertTrue("password" in (e.message ?: ""), e.message)
        assertTrue("appKey" in (e.message ?: ""), e.message)
    }

    @Test
    fun `parseCredentials rejects non-string fields`() {
        val e = assertFailsWith<IllegalArgumentException> {
            parseCredentials("""{ "username": 1, "password": "p", "appKey": "k" }""")
        }
        assertTrue("username" in (e.message ?: ""), e.message)
    }

    @Test
    fun `parseCredentials rejects malformed JSON`() {
        assertFailsWith<IllegalArgumentException> { parseCredentials("not json") }
    }

    @Test
    fun `defaultCredentialsPath points at golf-scraper dir`() {
        assertTrue(defaultCredentialsPath().toString().endsWith(".golf-scraper/credentials.json"))
    }

    @Test
    fun `loadCredentials errors with path in message when file missing`() {
        val e = assertFailsWith<IllegalArgumentException> {
            loadCredentials(java.nio.file.Paths.get("/nonexistent/credentials.json"))
        }
        assertTrue("/nonexistent/credentials.json" in (e.message ?: ""), e.message)
    }
}
