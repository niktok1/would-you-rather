package io.ntole.wyr

import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The test binary's bundle has no `WYR_ENV` key, as an app built without the setting would not. The
 * app's own Info.plist, and the value `Config.xcconfig` gives it, are checked only by building the
 * app (CLAUDE.md §9).
 */
class BundledEnvironmentTest {
    @Test
    fun `a bundle without the key names no environment`() {
        assertNull(bundledEnvironmentName())
    }
}
