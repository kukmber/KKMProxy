package io.github.romanvht.byedpi.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {

    @Test
    fun comparesVersions() {
        assertTrue(Updates.isNewer("v1.0.1", "1.0.0"))
        assertTrue(Updates.isNewer("1.1", "1.0.9"))
        assertTrue(Updates.isNewer("v0.17.4", "v17.3".let { "v0.17.3" }))
        assertFalse(Updates.isNewer("v1.0.0", "1.0.0"))
        assertFalse(Updates.isNewer("1.0.0", "1.0.1"))
        // Формат тегов у ByeByeDPI — "v.1.7.8"
        assertTrue(Updates.isNewer("v.1.7.9", "v1.7.8"))
        assertFalse(Updates.isNewer("v.1.7.8", "v1.7.8"))
    }
}
