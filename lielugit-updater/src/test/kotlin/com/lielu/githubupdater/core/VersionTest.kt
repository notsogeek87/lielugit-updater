package com.lielu.githubupdater.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {

    private fun v(raw: String) = assertNotNull(Version.parseOrNull(raw), "should parse: $raw")

    @Test
    fun `compares numerically, not as strings`() {
        assertTrue(v("1.2.0") < v("1.3.0"))
        assertTrue(v("1.9.0") < v("1.10.0"))
        assertTrue(v("1.10.0") > v("1.9.0"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("10.0.0") > v("9.9.9"))
    }

    @Test
    fun `equal versions compare equal`() {
        assertEquals(v("1.2.0"), v("1.2.0"))
        assertEquals(0, v("1.2.0").compareTo(v("1.2.0")))
    }

    @Test
    fun `missing segments count as zero`() {
        assertEquals(v("1.2"), v("1.2.0"))
        assertEquals(v("1.2").hashCode(), v("1.2.0.0").hashCode())
        assertTrue(v("1.2.1") > v("1.2"))
    }

    @Test
    fun `parses common tag formats`() {
        assertEquals("1.2.3", v("v1.2.3").normalized)
        assertEquals("1.2.3", v("V1.2.3").normalized)
        assertEquals("1.2.3", v("1.2.3").normalized)
        assertEquals("1.2.3", v("release-1.2.3").normalized)
        assertEquals("1.2.3", v("release-v1.2.3").normalized)
        assertEquals("1.2.3", v("app_v1.2.3").normalized)
        assertEquals("1.2.3", v("  v1.2.3 ").normalized)
        assertEquals(v("v1.2.3"), v("release-1.2.3"))
    }

    @Test
    fun `pre-release is lower than its release`() {
        assertTrue(v("1.0.0-beta") < v("1.0.0"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-beta"))
        assertTrue(v("1.0.0-beta.2") < v("1.0.0-beta.11"))
        assertTrue(v("1.0.0-beta.1") < v("1.0.0-beta.1.1"))
        assertTrue(v("1.0.0-1") < v("1.0.0-alpha"))
        assertTrue(v("1.0.0-rc.1") < v("1.0.1"))
        assertEquals("1.0.0-beta.1", v("v1.0.0-beta.1").normalized)
    }

    @Test
    fun `build metadata is ignored for ordering but kept as a number`() {
        assertEquals(v("1.2.3+45"), v("1.2.3+99"))
        assertEquals(45L, v("v1.2.3+45").buildNumber)
        assertNull(v("1.2.3").buildNumber)
        assertNull(v("1.2.3+abc").buildNumber)
    }

    @Test
    fun `rejects values that are not versions`() {
        listOf("", "   ", "latest", "nightly", "v", "1.2.3.4.5", "1..2", ".1", "1.x").forEach {
            assertNull(Version.parseOrNull(it), "should reject \"$it\"")
        }
        assertNull(Version.parseOrNull(null))
    }
}
