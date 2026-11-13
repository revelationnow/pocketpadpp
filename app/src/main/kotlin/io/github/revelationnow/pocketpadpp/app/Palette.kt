package io.github.revelationnow.pocketpadpp.app

/**
 * Colours of the bars around the editor. The editor itself takes its colours from the Notepad++ theme;
 * the surroundings only need to be light or dark to match it.
 */
class Palette(val dark: Boolean) {
    private fun pick(light: Long, darkValue: Long): Int = (if (dark) darkValue else light).toInt()

    /** Toolbar, status bar and the system bars. */
    val chrome = pick(0xFFEFEFEF, 0xFF2B2B2B)
    val accent = pick(0xFF2A7BDE, 0xFF5AA2F0)
    val chromeText = pick(0xFF202020, 0xFFE0E0E0)
    val chromeDimText = pick(0xFF404040, 0xFFB0B0B0)
    val keysBack = pick(0xFFF7F7F7, 0xFF333333)
    val findBack = pick(0xFFF0F0F0, 0xFF303030)

    val tabBar = pick(0xFFC0C0C0, 0xFF1C1C1C)
    val tabActiveBack = pick(0xFFFFFFFF, 0xFF3C3C3C)
    val tabInactiveBack = pick(0xFFD8D8D8, 0xFF282828)
    val tabActiveText = pick(0xFF000000, 0xFFFFFFFF)
    val tabInactiveText = pick(0xFF606060, 0xFFA0A0A0)
    val tabDirty = pick(0xFFC02020, 0xFFFF7070)

    /** View > Tab > Apply Colour 1 to 5: yellow, green, blue, orange, pink. */
    val tabColours = if (dark) {
        intArrayOf(0xFF6B5E1E.toInt(), 0xFF2F5E33.toInt(), 0xFF2B4F78.toInt(), 0xFF7A4A1E.toInt(), 0xFF6E3564.toInt())
    } else {
        intArrayOf(0xFFF6E58D.toInt(), 0xFFB8E2A0.toInt(), 0xFFA9CEF4.toInt(), 0xFFF7C592.toInt(), 0xFFEDB4E3.toInt())
    }

    // Notepad++'s find status colours, lightened where the background is dark.
    val findMessage = pick(0xFF0000FF, 0xFF7FB0FF)
    val findNotFound = pick(0xFFFF0000, 0xFFFF7070)
    val findEndReached = pick(0xFF008000, 0xFF60C060)
}
