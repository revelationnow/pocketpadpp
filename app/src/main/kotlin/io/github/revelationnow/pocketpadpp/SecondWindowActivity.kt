package io.github.revelationnow.pocketpadpp

/**
 * Notepad++'s "new instance": the same editor in a window of its own, for the file it was started with.
 * Android shows it as a separate entry among the recent apps, and beside the first window in split-screen mode.
 */
class SecondWindowActivity : MainActivity() {
    override val secondWindow: Boolean = true
}
