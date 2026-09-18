package io.github.romanvht.byedpi.utility

import io.github.romanvht.byedpi.utility.LauncherIcons.Icon
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class LauncherIconsTest {

    private fun on(month: Int, day: Int) = LauncherIcons.iconFor(
        Calendar.getInstance().apply { set(2026, month, day, 12, 0) },
    )

    @Test
    fun seasons() {
        assertEquals(Icon.NEW_YEAR, on(Calendar.DECEMBER, 1))
        assertEquals(Icon.NEW_YEAR, on(Calendar.DECEMBER, 31))
        assertEquals(Icon.NEW_YEAR, on(Calendar.JANUARY, 1))
        assertEquals(Icon.NEW_YEAR, on(Calendar.JANUARY, 31))
        assertEquals(Icon.REGULAR, on(Calendar.FEBRUARY, 1))
        assertEquals(Icon.REGULAR, on(Calendar.NOVEMBER, 30))
        assertEquals(Icon.PEAR, on(Calendar.OCTOBER, 12))
        assertEquals(Icon.REGULAR, on(Calendar.OCTOBER, 11))
        assertEquals(Icon.REGULAR, on(Calendar.OCTOBER, 13))
    }
}
