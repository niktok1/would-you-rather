package io.ntole.wyr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The build number the iOS app reads from its bundle's `CFBundleVersion` (CLAUDE.md §8g, *The build
 * number*), given through [buildNumberFrom]: the test binary's own bundle is no app's.
 */
class BundledBuildNumberTest {
    @Test
    fun `the build number is CFBundleVersion's`() {
        assertEquals(10000, buildNumberFrom { key -> if (key == "CFBundleVersion") "10000" else null })
    }

    @Test
    fun `no CFBundleVersion or none that is a whole number is no build number`() {
        assertNull(buildNumberFrom { null })
        assertNull(buildNumberFrom { "1.0" })
    }
}
