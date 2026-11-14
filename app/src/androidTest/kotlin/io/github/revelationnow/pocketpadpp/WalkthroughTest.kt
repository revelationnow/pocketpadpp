package io.github.revelationnow.pocketpadpp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.revelationnow.pocketpadpp.core.Eol
import io.github.revelationnow.pocketpadpp.core.KeyBinding
import io.github.revelationnow.pocketpadpp.core.Marks
import io.github.revelationnow.pocketpadpp.core.SearchOptions
import io.github.revelationnow.pocketpadpp.editor.ScintillaView
import io.github.revelationnow.pocketpadpp.scintilla.Sci
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * Drives the real app on a device or emulator the way a person would, checks what the editor ends up
 * holding, and saves a screenshot at each step to /sdcard/Download/pocketpad for review.
 *
 * Run with tools/run_ui_tests.sh, which also pulls the screenshots.
 */
@RunWith(AndroidJUnit4::class)
class WalkthroughTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val context: Context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>

    @get:Rule
    val testName = TestName()

    // One test that hangs must not take the rest of the run with it.
    @get:Rule
    val timeLimit: Timeout = Timeout.seconds(480)

    @Before
    fun launchClean() {
        // Start every test from a fresh install's state.
        File(context.filesDir, "session.xml").delete()
        File(context.filesDir, "backup").deleteRecursively()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        // A freshly booted emulator can still be showing its lock screen or a "not responding" notice.
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.waitForIdle()
        clearSystemNotices()
        if (device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 15000) != true) {
            logFocus("app window not found")
        }
    }

    @After
    fun close() {
        // The state each test ended in, which is what explains a failure.
        logFocus("end of ${testName.methodName}")
        shot("end-${testName.methodName}")
        scenario.close()
    }

    private fun clearSystemNotices() {
        repeat(3) {
            val notice = device.findObject(By.text(Pattern.compile(".*(isn't responding|keeps stopping).*"))) ?: return
            Log.w(TAG, "system notice on screen: ${notice.text}")
            (device.findObject(By.text(Pattern.compile("wait", Pattern.CASE_INSENSITIVE)))
                ?: device.findObject(By.text(Pattern.compile("close app", Pattern.CASE_INSENSITIVE))))?.click()
            device.waitForIdle()
            SystemClock.sleep(500)
        }
    }

    private fun logFocus(label: String) {
        val focus = device.executeShellCommand("dumpsys window displays").lineSequence()
            .filter { "mCurrentFocus" in it || "mFocusedApp" in it }.joinToString(" | ") { it.trim() }
        Log.i(TAG, "$label: $focus")
    }

    // region Helpers

    private fun <T> onUi(block: (MainActivity) -> T): T {
        var result: T? = null
        scenario.onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun sci(msg: Int, wParam: Long = 0, lParam: Long = 0): Long = onUi { it.editor.send(msg, wParam, lParam) }

    private fun text(): String = onUi { it.editor.text }

    private fun setText(value: String, language: String = "normal") = onUi { activity ->
        activity.editor.text = value
        activity.workspace.setLanguage(activity.workspace.languages.byName(language)!!)
    }

    private fun selection(): String = "${sci(Sci.SCI_GETSELECTIONSTART)}-${sci(Sci.SCI_GETSELECTIONEND)}"

    private fun shot(name: String) {
        device.waitForIdle()
        SystemClock.sleep(250)
        shots++
        val file = "/sdcard/Download/pocketpad/%02d-%s.png".format(shots, name)
        device.executeShellCommand("screencap -p $file")
        Log.i(TAG, "screenshot $file")
    }

    private fun find(label: String, timeout: Long = 4000): UiObject2 =
        device.wait(Until.findObject(By.text(label)), timeout) ?: throw AssertionError("Nothing on screen says \"$label\"")

    private fun tap(label: String) {
        find(label).click()
        device.waitForIdle()
    }

    private fun tapDescribed(description: String) {
        val target = device.wait(Until.findObject(By.desc(description)), 4000)
            ?: throw AssertionError("Nothing on screen is described as \"$description\"")
        target.click()
        device.waitForIdle()
    }

    private fun onScreen(label: String, timeout: Long = 2500): Boolean = device.wait(Until.hasObject(By.text(label)), timeout) == true

    private fun gone(label: String, timeout: Long = 2500): Boolean = device.wait(Until.gone(By.text(label)), timeout) == true

    /** Dialog buttons: some Android themes show their labels in capitals. */
    private fun tapButton(label: String) {
        val pattern = Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE)
        val button = device.wait(Until.findObject(By.clazz("android.widget.Button").text(pattern)), 4000)
            ?: throw AssertionError("No button says \"$label\"")
        button.click()
        device.waitForIdle()
    }

    /** Taps a row of the open menu, scrolling long menus until the row appears. */
    private fun menu(label: String) {
        // Long menus are not swiped through row by row: the app is asked to bring the row on screen, which
        // keeps the walkthrough from taking most of an hour. Lists that cannot do that are swiped as before.
        if (onUi { it.revealMenuRow(label) }) {
            device.waitForIdle()
            val row = device.wait(Until.findObject(By.clazz("android.widget.ScrollView")), 4000)?.wait(Until.findObject(By.text(label)), 2000)
            if (row != null && row.visibleBounds.height() > 10) {
                row.click()
                device.waitForIdle()
                SystemClock.sleep(150)
                return
            }
        }
        var atEnd = false
        repeat(60) {
            val list = device.wait(Until.findObject(By.clazz("android.widget.ScrollView")), 4000)
                ?: throw AssertionError("No menu is open (looking for \"$label\")")
            val row = list.findObject(By.text(label))
            if (row != null && row.visibleBounds.height() > 10) {
                row.click()
                device.waitForIdle()
                SystemClock.sleep(150)
                return
            }
            if (atEnd) throw AssertionError("Menu has no row \"$label\"")
            // Slow enough that the list does not coast past rows before they are looked at.
            atEnd = !list.scroll(Direction.DOWN, 0.6f, 1500)
            device.waitForIdle()
        }
        throw AssertionError("Menu has no row \"$label\"")
    }

    /** A row of the side panel, waited for because folders and function lists are read in the background. */
    private fun panelRow(label: String): UiObject2 {
        val list = device.wait(Until.findObject(By.clazz("android.widget.ScrollView")), 4000) ?: throw AssertionError("No panel is open")
        return list.wait(Until.findObject(By.text(label)), 8000) ?: throw AssertionError("The panel has no row \"$label\"")
    }

    // The panel draws its rows again whenever what it lists changes, which can happen between finding a row
    // and touching it; the row is then simply looked up again.
    private fun <T> onFreshRow(action: () -> T): T {
        repeat(4) {
            try {
                return action()
            } catch (e: StaleObjectException) {
                SystemClock.sleep(300)
            }
        }
        return action()
    }

    private fun panelClick(label: String) = onFreshRow { panelRow(label).click() }.also { device.waitForIdle() }

    private fun panelLongClick(label: String) = onFreshRow { panelRow(label).longClick() }.also { device.waitForIdle() }

    private fun openMenu(vararg path: String) {
        tapDescribed("Menu")
        for (step in path) menu(step)
    }

    private fun waitUntil(what: String, timeout: Long = 8000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for: $what")
    }

    private fun editorBounds(): Rect = onUi { activity ->
        val location = IntArray(2)
        activity.editor.getLocationOnScreen(location)
        Rect(location[0], location[1], location[0] + activity.editor.width, location[1] + activity.editor.height)
    }

    /** Screen coordinates of the middle of the character at a document position. */
    private fun pointOf(position: Long): Pair<Float, Float> {
        val bounds = editorBounds()
        val x = sci(Sci.SCI_POINTXFROMPOSITION, 0, position) + 4
        val y = sci(Sci.SCI_POINTYFROMPOSITION, 0, position) + sci(Sci.SCI_TEXTHEIGHT) / 2
        return (bounds.left + x).toFloat() to (bounds.top + y).toFloat()
    }

    private fun touch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    /** A tap. [at] fixes the time stamp, so that the gap between two taps does not depend on how busy the emulator is. */
    private fun tapAt(x: Float, y: Float, at: Long = SystemClock.uptimeMillis()) {
        touch(MotionEvent.ACTION_DOWN, x, y, at, at)
        touch(MotionEvent.ACTION_UP, x, y, at, at + 40)
    }

    /** A quick flick: the finger covers the distance in [millis] by the events' own clock and lifts without stopping. */
    private fun flick(fromX: Float, fromY: Float, toX: Float, toY: Float, millis: Long = 60, steps: Int = 6) {
        val down = SystemClock.uptimeMillis() - millis - 20
        touch(MotionEvent.ACTION_DOWN, fromX, fromY, down, down)
        for (i in 1..steps) {
            touch(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps, down, down + millis * i / steps)
        }
        touch(MotionEvent.ACTION_UP, toX, toY, down, down + millis + 2)
    }

    private fun longPressAt(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x, y, down, down)
        SystemClock.sleep(900)
        touch(MotionEvent.ACTION_UP, x, y, down, SystemClock.uptimeMillis())
    }

    /** Drags a finger across the screen. With [settle] the finger rests before lifting, so nothing is flung. */
    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int = 12, stepDelay: Long = 16, settle: Boolean = true) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, fromX, fromY, down, down)
        for (i in 1..steps) {
            SystemClock.sleep(stepDelay)
            touch(MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps, down, SystemClock.uptimeMillis())
        }
        if (settle) {
            repeat(4) {
                SystemClock.sleep(80)
                touch(MotionEvent.ACTION_MOVE, toX, toY, down, SystemClock.uptimeMillis())
            }
        }
        touch(MotionEvent.ACTION_UP, toX, toY, down, SystemClock.uptimeMillis())
    }

    /** Two fingers on one row, moving apart or together around a centre point. */
    private fun pinch(centreX: Float, centreY: Float, startSpan: Float, endSpan: Float, steps: Int = 12) {
        val properties = Array(2) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, pointers: Int, span: Float) {
            val coords = Array(2) { index ->
                MotionEvent.PointerCoords().apply {
                    x = centreX + (if (index == 0) -span else span) / 2
                    y = centreY
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pointers, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        send(MotionEvent.ACTION_DOWN, 1, startSpan)
        send(MotionEvent.ACTION_POINTER_DOWN or second, 2, startSpan)
        for (i in 1..steps) {
            SystemClock.sleep(20)
            send(MotionEvent.ACTION_MOVE, 2, startSpan + (endSpan - startSpan) * i / steps)
        }
        send(MotionEvent.ACTION_POINTER_UP or second, 2, endSpan)
        send(MotionEvent.ACTION_UP, 1, endSpan)
    }

    /** Brightness of a Scintilla colour (0xBBGGRR), 0 to 255. */
    private fun brightness(colour: Long): Double =
        0.299 * (colour and 0xFF) + 0.587 * ((colour shr 8) and 0xFF) + 0.114 * ((colour shr 16) and 0xFF)

    /** Counts pixels inside the editor whose colour is close to the given 0xRRGGBB. */
    private fun countPixels(rgb: Int, tolerance: Int = 40): Int {
        device.waitForIdle()
        SystemClock.sleep(300)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return -1
        val software = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val bounds = editorBounds()
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        var count = 0
        for (y in bounds.top until minOf(bounds.bottom, software.height)) {
            for (x in bounds.left until minOf(bounds.right, software.width)) {
                val p = software.getPixel(x, y)
                if (kotlin.math.abs(((p shr 16) and 0xFF) - r) < tolerance &&
                    kotlin.math.abs(((p shr 8) and 0xFF) - g) < tolerance &&
                    kotlin.math.abs((p and 0xFF) - b) < tolerance
                ) {
                    count++
                }
            }
        }
        return count
    }

    // endregion

    @Test
    fun startsWithOneEmptyTab() {
        assertTrue("first tab is called new 1", onScreen("new 1"))
        assertEquals("", text())
        assertTrue("status bar shows the caret position", onScreen("Ln 1, Col 1  \u00b7  1 lines"))
        assertTrue(onScreen("UTF-8"))
        assertTrue(onScreen("LF"))
        shot("start")
    }

    @Test
    fun typingThroughTheKeyboardConnection() {
        // The same calls a soft keyboard makes, on the real Android runtime.
        onUi { activity ->
            val connection = activity.editor.onCreateInputConnection(EditorInfo())!!
            connection.commitText("if (ready) {", 1)
            connection.commitText("\n", 1)
            connection.commitText("\t", 1)
            for (step in listOf("h", "he", "hel", "hell", "hello")) connection.setComposingText(step, 1)
            connection.finishComposingText()
            connection.commitText("\n", 1)
            connection.setComposingText("wrld", 1)
            connection.setComposingText("world", 1)
            connection.commitText("world ", 1)
            connection.deleteSurroundingText(1, 0)
            connection.commitText("😀", 1)
        }
        assertEquals("if (ready) {\n\thello\n\tworld😀", text())
        onUi { it.editor.onCreateInputConnection(EditorInfo())!!.deleteSurroundingText(2, 0) }
        assertEquals("backspace removes a whole emoji", "if (ready) {\n\thello\n\tworld", text())
        assertEquals("text before the caret, as a keyboard would ask for it", "world",
            onUi { it.editor.onCreateInputConnection(EditorInfo())!!.getTextBeforeCursor(5, 0).toString() })

        // Hardware keys reach the editor too.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_X)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_Y)
        assertEquals("if (ready) {\n\thello\n\tworldx\n\ty", text())
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)
        assertEquals("if (ready) {\n\thello\n\tworldx\n\t", text())
        assertTrue("the tab is marked as modified", onUi { it.workspace.isDirty(it.workspace.active) })
        shot("typed")

        // Everything typed can be undone and redone, in a sensible number of steps.
        var steps = 0
        while (sci(Sci.SCI_CANUNDO) != 0L && steps < 100) {
            sci(Sci.SCI_UNDO)
            steps++
        }
        Log.i(TAG, "typing took $steps undo steps")
        assertEquals("undo takes back everything that was typed", "", text())
        assertTrue("typing is grouped into a few undo steps, not one per keystroke ($steps)", steps in 2..20)
        while (sci(Sci.SCI_CANREDO) != 0L) sci(Sci.SCI_REDO)
        assertEquals("redo brings it all back", "if (ready) {\n\thello\n\tworldx\n\t", text())
    }

    @Test
    fun tappingShowsTheKeyboard() {
        setText("int main() {\n\treturn 0;\n}\n", "cpp")
        val (x, y) = pointOf(4)
        tapAt(x, y)
        SystemClock.sleep(1500)
        shot("keyboard-open")
        assertEquals("tap placed the caret", 4L, sci(Sci.SCI_GETCURRENTPOS))
        assertTrue("the editor shrank to make room for the keyboard",
            editorBounds().height() < device.displayHeight * 0.6)
        device.pressBack()
        SystemClock.sleep(800)
        shot("keyboard-closed")
    }

    @Test
    fun menusCanBeNavigatedBackAndForth() {
        tapDescribed("Menu")
        shot("menu-top")
        for (top in listOf("File", "Edit", "Search", "View", "Encoding", "Language", "Settings")) {
            assertTrue("top menu lists $top", onScreen(top))
        }
        menu("Edit")
        shot("menu-edit")
        assertTrue("Edit opened", onScreen("Select All"))
        menu("Line Operations")
        shot("menu-edit-line-operations")
        assertTrue(onScreen("Duplicate Current Line"))

        // Back arrow: one level at a time.
        tapDescribed("Back")
        assertTrue("back returns to Edit", onScreen("Select All"))
        shot("menu-back-to-edit")
        tapDescribed("Back")
        assertTrue("back returns to the top", onScreen("Settings"))

        // The Back key does the same, and at the top it closes the menu.
        menu("View")
        shot("menu-view")
        menu("Show Symbol")
        shot("menu-view-show-symbol")
        device.pressBack()
        assertTrue("Back key returns to View", onScreen("Word wrap"))
        device.pressBack()
        assertTrue("Back key returns to the top", onScreen("Settings"))
        device.pressBack()
        assertTrue("Back key at the top closes the menu", gone("Settings"))

        for ((top, probe) in listOf("File" to "Save As...", "Search" to "Find...", "Encoding" to "UTF-8-BOM", "Settings" to "Show line numbers")) {
            tapDescribed("Menu")
            menu(top)
            assertTrue("$top menu shows $probe", onScreen(probe))
            shot("menu-${top.lowercase()}")
            tapDescribed("Close menu")
        }
        tapDescribed("Menu")
        menu("Language")
        shot("menu-language")
        menu("Python")
        assertEquals("python", onUi { it.workspace.active.language.name })
        tapDescribed("Close menu")
        assertTrue("status bar shows the language", onScreen("Python"))

        tapDescribed("Menu")
        menu("Encoding")
        menu("Character sets")
        menu("Cyrillic")
        shot("menu-encoding-character-sets-cyrillic")
        assertTrue("character sets are listed by group", onScreen("KOI8-R"))
        tapDescribed("Close menu")
    }

    @Test
    fun showSymbolsAreVisible() {
        setText("a b  c\td\n    indented\n")
        val before = countPixels(0xFFB56A)
        openMenu("View", "Show Symbol", "Show Space and Tab")
        assertEquals(Sci.SCWS_VISIBLEALWAYS.toLong(), sci(Sci.SCI_GETVIEWWS))
        shot("menu-show-space-checked")
        menu("Show End of Line")
        assertEquals(1L, sci(Sci.SCI_GETVIEWEOL))
        tapDescribed("Close menu")
        shot("symbols-visible")
        val after = countPixels(0xFFB56A)
        Log.i(TAG, "whitespace pixels before=$before after=$after")
        assertTrue("space and tab marks are drawn large enough to see ($before -> $after)", after - before > 100)

        openMenu("View", "Show Symbol", "Show All Characters")
        assertEquals("Show All Characters turns both off again", 0L, sci(Sci.SCI_GETVIEWWS) + sci(Sci.SCI_GETVIEWEOL))
        tapDescribed("Close menu")
        openMenu("View", "Word wrap")
        assertEquals(Sci.SC_WRAP_WORD.toLong(), sci(Sci.SCI_GETWRAPMODE))
        tapDescribed("Close menu")
    }

    @Test
    fun highlightingForSeveralLanguages() {
        val samples = listOf(
            Triple("cpp", "sample.cpp", "#include <vector>\n// comment\nint main() {\n\tconst char *s = \"text\";\n\tfor (int i = 0; i < 10; i++) {\n\t\tif (i % 2) return 42;\n\t}\n}\n"),
            Triple("python", "sample.py", "import os\n\n# comment\ndef greet(name: str) -> str:\n    if not name:\n        return \"nobody\"\n    return f\"hello {name}\"\n\nclass A:\n    x = 3.14\n"),
            Triple("html", "sample.html", "<!DOCTYPE html>\n<html>\n<body class=\"main\">\n<!-- comment -->\n<script>var a = 1; function f() { return a; }</script>\n<?php echo \"hi\"; ?>\n</body>\n</html>\n"),
            Triple("json", "sample.json", "{\n  \"name\": \"pocketpad\",\n  \"count\": 3,\n  \"ok\": true,\n  \"items\": [1, 2, null]\n}\n"),
        )
        for ((language, name, content) in samples) {
            val file = File(context.cacheDir, name).apply { writeText(content) }
            onUi { it.openUris(listOf(Uri.fromFile(file))) }
            waitUntil("$name to open") { onUi { it.workspace.active.name } == name }
            assertEquals(language, onUi { it.workspace.active.language.name })
            assertEquals(content, text())
            sci(Sci.SCI_COLOURISE, 0, -1)
            shot("language-$language")
        }
        assertEquals("one tab per file; the empty start tab was reused", 4, onUi { it.workspace.docs.size })
        assertTrue("a C++ keyword is styled", run {
            onUi { it.workspace.activate(it.workspace.docs[0]) }
            sci(Sci.SCI_COLOURISE, 0, -1)
            sci(Sci.SCI_GETSTYLEAT, text().indexOf("return").toLong()) == 5L
        })
    }

    @Test
    fun fileRoundTripKeepsEncodingAndLineEndings() {
        val original = "первая строка\r\nsecond line €\r\n"
        val file = File(context.cacheDir, "utf16.txt").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + original.toByteArray(Charsets.UTF_16LE))
        }
        onUi { it.openUris(listOf(Uri.fromFile(file))) }
        waitUntil("the file to open") { onUi { it.workspace.active.name } == "utf16.txt" }
        assertEquals(original, text())
        assertTrue("encoding shown in the status bar", onScreen("UTF-16 LE BOM"))
        assertTrue("line ending shown in the status bar", onScreen("CRLF"))
        shot("utf16-opened")

        sci(Sci.SCI_DOCUMENTEND)
        onUi { it.editor.typeText("added") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        assertTrue(onUi { it.workspace.isDirty(it.workspace.active) })
        shot("utf16-modified")
        tap("Save")
        waitUntil("the save to finish") { !onUi { it.workspace.isDirty(it.workspace.active) } }
        val saved = file.readBytes()
        assertEquals("byte order mark kept", listOf(0xFF.toByte(), 0xFE.toByte()), saved.take(2))
        assertEquals("content kept, new line uses CRLF", original + "added\r\n", String(saved, 2, saved.size - 2, Charsets.UTF_16LE))
        shot("utf16-saved")

        // Convert line endings and encoding from the status bar.
        tap("CRLF")
        shot("menu-eol")
        menu("Unix (LF)")
        tapDescribed("Close menu")
        assertEquals(Sci.SC_EOL_LF.toLong(), sci(Sci.SCI_GETEOLMODE))
        assertTrue("no CR left", '\r' !in text())
        tap("UTF-16 LE BOM")
        menu("UTF-8")
        tapDescribed("Close menu")
        tap("Save")
        waitUntil("the second save to finish") { !onUi { it.workspace.isDirty(it.workspace.active) } }
        assertEquals("первая строка\nsecond line €\nadded\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun tabsCloseDialogAndSessionRestore() {
        onUi { it.editor.typeText("unsaved note") }
        tap("New")
        assertTrue(onScreen("new 2"))
        onUi { it.editor.typeText("second tab") }
        shot("two-tabs")

        // Switching tabs brings the other document back with its text.
        tap("\u25CF new 1")
        assertEquals("unsaved note", text())
        tap("\u25CF new 2")
        assertEquals("second tab", text())

        // Closing a modified tab asks first.
        tapDescribed("Close new 2")
        assertTrue("asks before closing a modified tab", onScreen("Save file \"new 2\"?"))
        shot("close-dialog")
        tapButton("No")
        assertEquals(1, onUi { it.workspace.docs.size })
        assertEquals("unsaved note", text())

        // The session snapshot brings unsaved text back after the app is closed.
        tap("New")
        onUi { it.editor.typeText("third") }
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitUntil("the session to be restored") { onUi { it.workspace.docs.size } == 2 }
        shot("session-restored")
        assertEquals(listOf("new 1", "new 2"), onUi { activity -> activity.workspace.docs.map { it.name } })
        assertEquals("third", text())
        tap("\u25CF new 1")
        assertEquals("unsaved note", text())
    }

    @Test
    fun findAndReplace() {
        setText("alpha beta alpha\nGamma alpha beta\n")
        tap("Find")
        shot("find-bar")
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        assertTrue("find bar has a text field", fields.isNotEmpty())
        fields[0].text = "alpha"
        tapDescribed("Find next")
        assertEquals("0-5", selection())
        tapDescribed("Find next")
        assertEquals("11-16", selection())
        tapDescribed("Find previous")
        assertEquals("0-5", selection())
        tapDescribed("Count matches")
        assertTrue(onScreen("Count: 3 matches."))
        shot("find-count")
        tapDescribed("Close")

        openMenu("Search", "Replace...")
        // Looked for until they are there: the bar is still opening when the menu has gone.
        var replaceFields = emptyList<UiObject2>()
        waitUntil("replace mode to show two fields") {
            replaceFields = device.findObjects(By.clazz("android.widget.EditText"))
            replaceFields.size == 2
        }
        replaceFields[0].text = "(\\w+) beta"
        replaceFields[1].text = "$1 BETA"
        tapDescribed("Regular expression")
        tapDescribed("Replace all")
        shot("replace-all")
        assertEquals("alpha BETA alpha\nGamma alpha BETA\n", text())
        sci(Sci.SCI_UNDO)
        assertEquals("replace all is a single undo step", "alpha beta alpha\nGamma alpha beta\n", text())
        tapDescribed("Close")
        assertTrue("find bar closed", onUi { !it.findBar.isOpen })
    }

    @Test
    fun touchSelectionAndScrolling() {
        val body = (1..600).joinToString("\n") { "line $it has some words in it" }
        setText(body)
        val bounds = editorBounds()
        val centreX = bounds.exactCenterX()

        // Dragging scrolls by the distance moved and the view stays where it was left.
        val lineHeight = sci(Sci.SCI_TEXTHEIGHT)
        drag(centreX, bounds.bottom - 200f, centreX, bounds.top + 200f, steps = 30, stepDelay = 30)
        SystemClock.sleep(1500)
        val afterDrag = sci(Sci.SCI_GETFIRSTVISIBLELINE)
        val expected = (bounds.height() - 400) / lineHeight
        assertTrue("drag scrolled the text by the distance moved ($afterDrag lines, expected about $expected)",
            afterDrag >= expected - 3 && afterDrag <= expected + 3)
        shot("scrolled")
        SystemClock.sleep(1200)
        assertEquals("the view stays where it was left", afterDrag, sci(Sci.SCI_GETFIRSTVISIBLELINE))

        // A fast flick keeps going after the finger lifts.
        flick(centreX, bounds.bottom - 200f, centreX, bounds.top + 300f)
        SystemClock.sleep(3000)
        assertTrue("fling carried on", sci(Sci.SCI_GETFIRSTVISIBLELINE) > afterDrag + 40)

        // Double tap selects a word; long press selects a word and shows the edit toolbar.
        setText("alpha beta gamma\nsecond line\n")
        val (x, y) = pointOf(7)
        val firstTap = SystemClock.uptimeMillis() - 400
        tapAt(x, y, firstTap)
        tapAt(x, y, firstTap + 150)
        device.waitForIdle()
        assertEquals("double tap selects the word", "6-10", selection())
        shot("double-tap-selection")
        tapAt(pointOf(20).first, pointOf(20).second)
        SystemClock.sleep(500)
        val (gx, gy) = pointOf(13)
        longPressAt(gx, gy)
        device.waitForIdle()
        assertEquals("long press selects the word", "11-16", selection())
        SystemClock.sleep(600)
        shot("long-press-selection")
        assertTrue("edit toolbar offers Copy", onScreen("Copy", 2500))

        // The end handle hangs below the end of the selection; pull it down one line.
        val (endX, endY) = pointOf(16)
        val handleY = endY + lineHeight
        drag(endX + 20, handleY, endX + 20, handleY + lineHeight, steps = 15, stepDelay = 30)
        device.waitForIdle()
        shot("handle-dragged")
        assertEquals("selection still starts at the word", 11L, sci(Sci.SCI_GETSELECTIONSTART))
        assertTrue("dragging the end handle down extended the selection", sci(Sci.SCI_GETSELECTIONEND) > 16)
    }

    @Test
    fun editMenuCommandsReachTheEditor() {
        setText("pear\napple\nfig\n", "cpp")
        sci(Sci.SCI_SETSEL, 0, 14)
        openMenu("Edit", "Line Operations", "Sort Lines Lexicographically Ascending")
        assertEquals("apple\nfig\npear\n", text())
        sci(Sci.SCI_SETSEL, 0, 5)
        openMenu("Edit", "Convert Case to", "UPPERCASE")
        assertEquals("APPLE\nfig\npear\n", text())
        sci(Sci.SCI_SETSEL, 0, 5)
        openMenu("Edit", "Convert Case to", "Proper Case")
        assertEquals("Apple\nfig\npear\n", text())
        sci(Sci.SCI_GOTOPOS, 7)
        openMenu("Edit", "Comment/Uncomment", "Toggle Single Line Comment")
        assertEquals("Apple\n// fig\npear\n", text())
        openMenu("Edit", "Line Operations", "Duplicate Current Line")
        assertEquals("Apple\n// fig\n// fig\npear\n", text())
        openMenu("Search", "Bookmark", "Toggle Bookmark")
        assertTrue("bookmark set on the caret line",
            sci(Sci.SCI_MARKERGET, sci(Sci.SCI_LINEFROMPOSITION, sci(Sci.SCI_GETCURRENTPOS))) and (1L shl 20) != 0L)
        shot("after-edit-commands")

        openMenu("Search", "Go to...")
        assertTrue(onScreen("Go to line"))
        shot("go-to-line")
        device.findObject(By.clazz("android.widget.EditText")).text = "4"
        tapButton("Go")
        assertEquals(3L, sci(Sci.SCI_LINEFROMPOSITION, sci(Sci.SCI_GETCURRENTPOS)))

        openMenu("View", "Summary...")
        assertTrue(onScreen("Summary"))
        shot("summary")
        tapButton("OK")

        sci(Sci.SCI_GOTOPOS, 10)
        openMenu("Edit", "Multi-select All", "Match Case & Whole Word")
        assertEquals("multi-select put a caret on each occurrence of the word", 2L, sci(Sci.SCI_GETSELECTIONS))
        shot("multi-select")
        openMenu("View", "Zoom", "Zoom In")
        assertEquals(1L, sci(Sci.SCI_GETZOOM))
    }

    @Test
    fun viewAndSettingsToggles() {
        setText("int main() {\n\tif (a) {\n\t\tb();\n\t}\n\treturn 0;\n}\n", "cpp")
        sci(Sci.SCI_COLOURISE, 0, -1)

        // Settings
        val numbersWidth = sci(Sci.SCI_GETMARGINWIDTHN, 0)
        assertTrue("line numbers are shown by default", numbersWidth > 0)
        openMenu("Settings", "Show line numbers")
        assertEquals("line numbers hidden", 0L, sci(Sci.SCI_GETMARGINWIDTHN, 0))
        shot("settings-no-line-numbers")
        menu("Show line numbers")
        assertEquals("line numbers back", numbersWidth, sci(Sci.SCI_GETMARGINWIDTHN, 0))
        menu("Keyboard suggestions and autocorrect")
        assertEquals(ScintillaView.KeyboardMode.TEXT, onUi { it.editor.keyboardMode })
        menu("Keyboard suggestions and autocorrect")
        assertEquals(ScintillaView.KeyboardMode.CODE, onUi { it.editor.keyboardMode })
        tapDescribed("Close menu")
        val size = sci(Sci.SCI_STYLEGETSIZE, Sci.STYLE_DEFAULT.toLong())
        openMenu("Settings", "Bigger text")
        openMenu("Settings", "Bigger text")
        assertEquals("bigger text", size + 2, sci(Sci.SCI_STYLEGETSIZE, Sci.STYLE_DEFAULT.toLong()))
        shot("settings-bigger-text")
        openMenu("Settings", "Smaller text")
        openMenu("Settings", "Smaller text")
        assertEquals("smaller text", size, sci(Sci.SCI_STYLEGETSIZE, Sci.STYLE_DEFAULT.toLong()))

        // View: folding
        openMenu("View", "Fold All")
        assertEquals("fold all hides the function body", 0L, sci(Sci.SCI_GETLINEVISIBLE, 1))
        shot("view-fold-all")
        openMenu("View", "Unfold All")
        assertEquals("unfold all shows it again", 1L, sci(Sci.SCI_GETLINEVISIBLE, 2))
        openMenu("View", "Fold Level", "2")
        assertEquals("fold level 2 hides the inner block only", listOf(1L, 0L),
            listOf(sci(Sci.SCI_GETLINEVISIBLE, 1), sci(Sci.SCI_GETLINEVISIBLE, 2)))
        shot("view-fold-level-2")
        openMenu("View", "Unfold Level", "2")
        assertEquals(1L, sci(Sci.SCI_GETLINEVISIBLE, 2))
        sci(Sci.SCI_GOTOPOS, 20)
        openMenu("View", "Fold Current Level")
        assertEquals("fold current level", 0L, sci(Sci.SCI_GETLINEVISIBLE, 2))
        openMenu("View", "Unfold Current Level")
        assertEquals("unfold current level", 1L, sci(Sci.SCI_GETLINEVISIBLE, 2))

        // View: symbols, wrap, zoom
        openMenu("View", "Show Symbol", "Show Indent Guide")
        assertEquals("indent guides off", Sci.SC_IV_NONE.toLong(), sci(Sci.SCI_GETINDENTATIONGUIDES))
        menu("Show Indent Guide")
        assertTrue("indent guides on", sci(Sci.SCI_GETINDENTATIONGUIDES) != Sci.SC_IV_NONE.toLong())
        menu("Show Wrap Symbol")
        assertEquals(Sci.SC_WRAPVISUALFLAG_END.toLong(), sci(Sci.SCI_GETWRAPVISUALFLAGS))
        tapDescribed("Back")
        menu("Word wrap")
        assertEquals(Sci.SC_WRAP_WORD.toLong(), sci(Sci.SCI_GETWRAPMODE))
        tapDescribed("Close menu")
        setText("A long line that has to wrap around on a phone screen because it does not fit: " + "word ".repeat(30) + "\nshort\n")
        shot("view-word-wrap")
        assertTrue("the long line takes several rows", sci(Sci.SCI_WRAPCOUNT, 0) > 1)
        openMenu("View", "Word wrap")
        assertEquals(Sci.SC_WRAP_NONE.toLong(), sci(Sci.SCI_GETWRAPMODE))
        tapDescribed("Close menu")
        openMenu("View", "Zoom", "Zoom In")
        openMenu("View", "Zoom", "Zoom In")
        assertEquals(2L, sci(Sci.SCI_GETZOOM))
        shot("view-zoomed-in")
        openMenu("View", "Zoom", "Zoom Out")
        assertEquals(1L, sci(Sci.SCI_GETZOOM))
        openMenu("View", "Zoom", "Restore Default Zoom")
        assertEquals(0L, sci(Sci.SCI_GETZOOM))

        // Edit: read-only
        openMenu("Edit", "Read-Only")
        tapDescribed("Close menu")
        assertEquals(1L, sci(Sci.SCI_GETREADONLY))
        val before = text()
        onUi { it.editor.typeText("x") }
        assertEquals("a read-only document cannot be typed into", before, text())
        shot("read-only")
        openMenu("Edit", "Read-Only")
        tapDescribed("Close menu")
        assertEquals(0L, sci(Sci.SCI_GETREADONLY))

        // Settings survive a restart.
        openMenu("View", "Word wrap")
        tapDescribed("Close menu")
        openMenu("Settings", "Show line numbers")
        tapDescribed("Close menu")
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.waitForIdle()
        assertEquals("word wrap is remembered", Sci.SC_WRAP_WORD.toLong(), sci(Sci.SCI_GETWRAPMODE))
        assertEquals("hidden line numbers are remembered", 0L, sci(Sci.SCI_GETMARGINWIDTHN, 0))
    }

    @Test
    fun tabCommands() {
        fun names() = onUi { activity -> activity.workspace.docs.map { it.name } }
        fun active() = onUi { it.workspace.active.name }
        tap("New")
        tap("New")
        tap("New")
        assertEquals(listOf("new 1", "new 2", "new 3", "new 4"), names())
        assertEquals("new 4", active())

        openMenu("View", "Tab", "First Tab")
        assertEquals("new 1", active())
        openMenu("View", "Tab", "Next Tab")
        assertEquals("new 2", active())
        openMenu("View", "Tab", "Previous Tab")
        openMenu("View", "Tab", "Previous Tab")
        assertEquals("previous wraps around", "new 4", active())
        openMenu("View", "Tab", "Move to Start")
        assertEquals(listOf("new 4", "new 1", "new 2", "new 3"), names())
        openMenu("View", "Tab", "Move Tab Forward")
        assertEquals(listOf("new 1", "new 4", "new 2", "new 3"), names())
        shot("tabs-moved")

        // Long press on a tab opens its own menu.
        find("new 2").longClick()
        device.waitForIdle()
        assertTrue("tab menu opened", onScreen("Close All but This"))
        shot("tab-menu")
        menu("Close All to the Right")
        assertEquals(listOf("new 1", "new 4", "new 2"), names())
        tapDescribed("Close new 4")
        assertEquals("an unchanged tab closes without asking", listOf("new 1", "new 2"), names())

        // Unsaved tabs each ask before Close All discards them.
        onUi { it.editor.typeText("keep?") }
        openMenu("File", "Close All")
        assertTrue("asks about the modified tab", onScreen("Save file \"${active()}\"?"))
        tapButton("Cancel")
        assertEquals("cancel stops closing", 1, names().count { it == active() })
        tap("New")
        openMenu("File", "Close Multiple Documents", "Close All Unchanged")
        assertEquals("only the modified tab is left", 1, names().size)
        shot("tabs-after-close-unchanged")
        openMenu("File", "Close")
        tapButton("No")
        assertEquals("closing the last tab leaves one empty tab", listOf("new 1"), names())
        assertEquals("", text())
    }

    @Test
    fun themesAndDarkMode() {
        fun background() = sci(Sci.SCI_STYLEGETBACK, Sci.STYLE_DEFAULT.toLong())
        val code = "#include <vector>\n// comment\nint main() {\n\tconst char *s = \"text\";\n\treturn 42;\n}\n"
        val file = File(context.cacheDir, "theme.cpp").apply { writeText(code) }
        onUi { it.openUris(listOf(Uri.fromFile(file))) }
        waitUntil("the file to open") { onUi { it.workspace.active.name } == "theme.cpp" }
        tap("New")
        onUi { it.editor.typeText("unsaved words") }
        assertEquals("the default theme is white", 0xFFFFFFL, background())

        // Dark mode changes the bars around the editor; the editor, its text and its undo history stay.
        openMenu("Settings", "Dark mode")
        waitUntil("the screen to turn dark") { brightness(background()) < 128 }
        assertTrue("the menu stays open across the switch", onScreen("Keyboard suggestions and autocorrect"))
        shot("dark-mode-menu")
        tapDescribed("Close menu")
        assertEquals("unsaved text survives the switch", "unsaved words", text())
        assertEquals(2, onUi { it.workspace.docs.size })
        assertTrue("the undo history survives the switch", sci(Sci.SCI_CANUNDO) != 0L)
        while (sci(Sci.SCI_CANUNDO) != 0L) sci(Sci.SCI_UNDO)
        assertEquals("", text())
        while (sci(Sci.SCI_CANREDO) != 0L) sci(Sci.SCI_REDO)
        assertEquals("unsaved words", text())
        shot("dark-mode")
        tap("theme.cpp")
        assertEquals(code, text())
        sci(Sci.SCI_COLOURISE, 0, -1)
        shot("dark-mode-code")
        tap("Find")
        shot("dark-mode-find")
        tapDescribed("Close")

        // Another dark theme applies on the spot.
        val darkDefault = background()
        openMenu("Settings", "Theme", "Monokai")
        assertTrue("Monokai has its own dark background", background() != darkDefault && brightness(background()) < 128)
        tapDescribed("Close menu")
        shot("theme-monokai")

        // A light theme rebuilds the screen again.
        openMenu("Settings", "Theme", "Solarized-light")
        waitUntil("the screen to turn light") { brightness(background()) > 128 }
        assertTrue("the theme list stays open when a pick changes between dark and light", onScreen("Solarized-light"))
        shot("theme-menu-after-light-pick")
        tapDescribed("Close menu")
        sci(Sci.SCI_COLOURISE, 0, -1)
        shot("theme-solarized-light")

        // The choice is remembered.
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitUntil("the session to be restored") { onUi { it.workspace.docs.size } == 2 }
        assertTrue("the theme is remembered", background() != 0xFFFFFFL && brightness(background()) > 128)
        openMenu("Settings", "Theme", "Default (stylers.xml)")
        assertEquals("back to the default theme", 0xFFFFFFL, background())
        tapDescribed("Close menu")
    }

    @Test
    fun pinchZoomAndScrollThumb() {
        setText((1..2000).joinToString("\n") { "line $it" })
        val bounds = editorBounds()
        val centreX = bounds.exactCenterX()
        val centreY = bounds.top + bounds.height() * 0.4f

        pinch(centreX, centreY, 200f, 400f)
        val zoomed = sci(Sci.SCI_GETZOOM)
        assertTrue("spreading two fingers enlarges the text (zoom $zoomed)", zoomed >= 8)
        assertEquals("a pinch does not move the caret or select anything", "0-0", selection())
        shot("pinch-zoomed-in")
        pinch(centreX, centreY, 400f, 200f)
        assertTrue("pinching back restores the size (zoom ${sci(Sci.SCI_GETZOOM)})", kotlin.math.abs(sci(Sci.SCI_GETZOOM)) <= 1)
        sci(Sci.SCI_SETZOOM, 0)

        // A short scroll brings out the thumb at the right edge; dragging it goes through the whole file.
        drag(centreX, bounds.bottom - 150f, centreX, bounds.top + 150f)
        val edge = bounds.right - 12f
        var down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, edge, bounds.top + 70f, down, down)
        for (i in 1..20) {
            SystemClock.sleep(16)
            touch(MotionEvent.ACTION_MOVE, edge, bounds.top + 70f + (bounds.height() / 2f - 70f) * i / 20, down, SystemClock.uptimeMillis())
        }
        val middle = sci(Sci.SCI_GETFIRSTVISIBLELINE)
        shot("scroll-thumb-held")
        assertTrue("half way down the track is around the middle of the file (line $middle)", middle in 700..1300)
        for (i in 1..20) {
            SystemClock.sleep(16)
            touch(MotionEvent.ACTION_MOVE, edge, bounds.exactCenterY() + (bounds.height() / 2f - 5f) * i / 20, down, SystemClock.uptimeMillis())
        }
        touch(MotionEvent.ACTION_UP, edge, bounds.bottom - 5f, down, SystemClock.uptimeMillis())
        assertTrue("the bottom of the track is the end of the file (line ${sci(Sci.SCI_GETFIRSTVISIBLELINE)})",
            sci(Sci.SCI_GETFIRSTVISIBLELINE) > 1900)
        down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, edge, bounds.bottom - 60f, down, down)
        for (i in 1..20) {
            SystemClock.sleep(16)
            touch(MotionEvent.ACTION_MOVE, edge, bounds.bottom - 60f - (bounds.height() - 60f) * i / 20, down, SystemClock.uptimeMillis())
        }
        touch(MotionEvent.ACTION_UP, edge, bounds.top.toFloat(), down, SystemClock.uptimeMillis())
        assertEquals("and the top of the track is the start", 0L, sci(Sci.SCI_GETFIRSTVISIBLELINE))
    }

    @Test
    fun foldsStayWithTheirTab() {
        setText("int main() {\n\tif (a) {\n\t\tb();\n\t}\n\treturn 0;\n}\n", "cpp")
        sci(Sci.SCI_COLOURISE, 0, -1)
        sci(Sci.SCI_FOLDLINE, 1, Sci.SC_FOLDACTION_CONTRACT.toLong())
        assertEquals(0L, sci(Sci.SCI_GETLINEVISIBLE, 2))
        tap("New")
        waitUntil("the new tab") { onUi { it.workspace.docs.size } == 2 }
        assertEquals("the fold is noted when its tab is left", listOf(1L), onUi { it.workspace.docs[0].folds })
        onUi { it.editor.typeText("other tab") }

        // Straight after switching back, before the screen has been drawn again...
        val atOnce = onUi { activity ->
            activity.workspace.activate(activity.workspace.docs[0])
            activity.editor.send(Sci.SCI_GETLINEVISIBLE, 2)
        }
        assertEquals("the fold is put back when the tab is shown again", 0L, atOnce)
        // ...and once everything has settled.
        device.waitForIdle()
        SystemClock.sleep(800)
        assertEquals("the fold is still there after the screen has settled", 0L, sci(Sci.SCI_GETLINEVISIBLE, 2))
        assertEquals("and only that block is folded", 1L, sci(Sci.SCI_GETLINEVISIBLE, 4))
        shot("fold-kept-per-tab")

        // The same through the tab strip.
        tap("\u25CF new 2")
        tap("new 1")
        device.waitForIdle()
        assertEquals("the fold survives a tap on its tab", 0L, sci(Sci.SCI_GETLINEVISIBLE, 2))
    }

    @Test
    fun modifierKeysAndShortcuts() {
        fun soft(text: String) = onUi { it.editor.onCreateInputConnection(EditorInfo())!!.commitText(text, 1) }
        fun held() = onUi { it.editor.softModifiers }
        setText("one two three\nfour\n")

        // One tap holds Ctrl for the next key only.
        tap("Ctrl")
        assertEquals(Sci.SCMOD_CTRL, held())
        shot("ctrl-held")
        soft("a")
        assertEquals("Ctrl+A selects everything instead of typing", "0-19", selection())
        assertEquals("one two three\nfour\n", text())
        assertEquals("Ctrl lets go after one key", 0, held())

        sci(Sci.SCI_GOTOPOS, 2)
        tap("Ctrl")
        soft("d")
        assertEquals("Ctrl+D duplicates the line", "one two three\none two three\nfour\n", text())
        tap("Ctrl")
        soft("z")
        assertEquals("Ctrl+Z undoes", "one two three\nfour\n", text())

        // A second tap locks it; a third releases it.
        sci(Sci.SCI_GOTOPOS, 0)
        tap("Ctrl")
        tap("Ctrl")
        shot("ctrl-locked")
        tap("\u2192")
        tap("\u2192")
        assertEquals("Ctrl+Right moves by words while Ctrl is locked", 8L, sci(Sci.SCI_GETCURRENTPOS))
        assertEquals(Sci.SCMOD_CTRL, held())
        tap("Ctrl")
        assertEquals("a third tap releases it", 0, held())
        soft("x")
        assertEquals("typing is back to normal", "one two xthree\nfour\n", text())
        sci(Sci.SCI_UNDO)

        // Shift extends the selection; Alt+Shift makes a column selection.
        sci(Sci.SCI_GOTOPOS, 0)
        tap("Shift")
        tap("\u2192")
        assertEquals("Shift+Right selects", "0-1", selection())
        sci(Sci.SCI_GOTOPOS, 0)
        sci(Sci.SCI_CHOOSECARETX)
        tap("Shift")
        tap("Shift")
        tap("Alt")
        tap("Alt")
        tap("\u2193")
        tap("\u2192")
        tap("\u2192")
        tap("\u2192")
        assertTrue("Alt+Shift+arrows make a column selection", sci(Sci.SCI_SELECTIONISRECTANGLE) != 0L)
        assertEquals("over two lines", 2L, sci(Sci.SCI_GETSELECTIONS))
        shot("column-selection")
        tap("Shift")
        tap("Alt")
        assertEquals(0, held())
        onUi { it.editor.typeText("#") }
        assertEquals("typing goes to every line of the column", "# two three\n#r\n", text())
        sci(Sci.SCI_UNDO)
        sci(Sci.SCI_GOTOPOS, 0)

        // Alt and a letter opens that menu.
        tap("Alt")
        soft("f")
        assertTrue("Alt+F opens the File menu", onScreen("Save As..."))
        shot("alt-f-menu")
        tapDescribed("Back")
        assertTrue("with the main menu one level up", onScreen("Settings"))
        tapDescribed("Close menu")

        // Notepad++'s shortcuts.
        tap("Ctrl")
        soft("f")
        assertTrue("Ctrl+F opens the find bar", onUi { it.findBar.isOpen })
        tapDescribed("Close")
        tap("Ctrl")
        soft("n")
        assertEquals("Ctrl+N opens a tab", 2, onUi { it.workspace.docs.size })
        tap("Ctrl")
        soft("w")
        assertEquals("Ctrl+W closes it", 1, onUi { it.workspace.docs.size })

        // With suggestions on, the keyboard sends the whole word it is composing on every key press.
        // The emulator's own keyboard would end a composition it did not start, so each step sets up the
        // composed word and presses the key in one go, after a pause that lets the keyboard reconnect.
        setText("hello wo")
        sci(Sci.SCI_GOTOPOS, 8)
        onUi { it.editor.setClipboard("PASTE") }
        SystemClock.sleep(700)
        tap("Ctrl")
        onUi {
            val composing = it.editor.onCreateInputConnection(EditorInfo())!!
            composing.setComposingRegion(6, 8)
            composing.setComposingText("wov", 1)
        }
        assertEquals("Ctrl+V pastes in the middle of a composed word", "hello woPASTE", text())
        assertEquals(0, held())
        // The keyboard then adopts the word at the caret, as it does after a paste or a tap.
        SystemClock.sleep(700)
        tap("Ctrl")
        onUi {
            val adopted = it.editor.onCreateInputConnection(EditorInfo())!!
            adopted.setComposingRegion(6, 13)
            adopted.setComposingText("woPASTEv", 1)
        }
        assertEquals("and pastes again once the word has been adopted", "hello woPASTEPASTE", text())
        SystemClock.sleep(700)
        tap("Ctrl")
        onUi {
            val adopted = it.editor.onCreateInputConnection(EditorInfo())!!
            adopted.setComposingRegion(6, 18)
            adopted.setComposingText("woPASTEPASTEa", 1)
        }
        assertEquals("Ctrl+A in the same state selects all", "0-18", selection())
        assertEquals("hello woPASTEPASTE", text())
        SystemClock.sleep(700)
        onUi {
            val adopted = it.editor.onCreateInputConnection(EditorInfo())!!
            adopted.setComposingRegion(6, 18)
            adopted.setComposingText("woPASTEPASTEx", 1)
            adopted.finishComposingText()
        }
        assertEquals("without Ctrl the keyboard composes as usual", "hello woPASTEPASTEx", text())
        // The caret can also sit inside the adopted word; the key is then the letter added there, not the last one.
        setText("abcdef")
        sci(Sci.SCI_GOTOPOS, 3)
        SystemClock.sleep(700)
        tap("Ctrl")
        onUi {
            val adopted = it.editor.onCreateInputConnection(EditorInfo())!!
            adopted.setComposingRegion(0, 6)
            adopted.setComposingText("abcadef", 1)
        }
        assertEquals("Ctrl+A with the caret inside a word", "0-6", selection())
        assertEquals("abcdef", text())
        assertEquals("no tab was closed or opened by mistake", 1, onUi { it.workspace.docs.size })

        // Copy and cut need the selection, which a suggestion keyboard replaces with the typed letter.
        fun clipboard() = onUi { it.editor.getClipboard() }
        setText("alpha beta")
        sci(Sci.SCI_SETSEL, 6, 10)
        onUi { it.editor.setClipboard("") }
        SystemClock.sleep(700)
        tap("Ctrl")
        onUi {
            val adopted = it.editor.onCreateInputConnection(EditorInfo())!!
            adopted.setComposingRegion(6, 10)
            adopted.setComposingText("c", 1)
        }
        assertEquals("Ctrl+C on a selected, composed word copies it", "beta", clipboard())
        assertEquals("and leaves it in place", "alpha beta" to "6-10", text() to selection())

        sci(Sci.SCI_SETSEL, 0, 5)
        SystemClock.sleep(700)
        tap("Ctrl")
        onUi { it.editor.onCreateInputConnection(EditorInfo())!!.setComposingText("c", 1) }
        assertEquals("Ctrl+C on a plain selection", "alpha", clipboard())
        assertEquals("alpha beta", text())

        sci(Sci.SCI_SETSEL, 2, 4)
        SystemClock.sleep(700)
        tap("Ctrl")
        onUi {
            val adopted = it.editor.onCreateInputConnection(EditorInfo())!!
            adopted.setComposingRegion(0, 5)
            adopted.setComposingText("alca", 1)
        }
        assertEquals("Ctrl+C on part of a composed word", "ph", clipboard())
        assertEquals("alpha beta", text())

        sci(Sci.SCI_SETSEL, 6, 10)
        SystemClock.sleep(700)
        tap("Ctrl")
        onUi {
            val clearing = it.editor.onCreateInputConnection(EditorInfo())!!
            clearing.commitText("", 1)
            clearing.setComposingText("x", 1)
        }
        assertEquals("Ctrl+X when the keyboard clears the selection first", "beta", clipboard())
        assertEquals("alpha ", text())
        assertEquals(0, held())

        // Other keyboards move the caret and delete the selected text step by step before sending the letter.
        setText("alpha beta")
        sci(Sci.SCI_SETSEL, 6, 10)
        onUi { it.editor.setClipboard("") }
        SystemClock.sleep(700)
        tap("Ctrl")
        onUi {
            val keyboard = it.editor.onCreateInputConnection(EditorInfo())!!
            keyboard.beginBatchEdit()
            keyboard.setSelection(10, 10)
            keyboard.deleteSurroundingText(4, 0)
            keyboard.setComposingText("c", 1)
            keyboard.endBatchEdit()
        }
        assertEquals("Ctrl+C when the keyboard deletes the selection step by step", "beta", clipboard())
        assertEquals("alpha beta" to "6-10", text() to selection())

        sci(Sci.SCI_SETSEL, 0, 5)
        SystemClock.sleep(700)
        tap("Ctrl")
        onUi {
            val keyboard = it.editor.onCreateInputConnection(EditorInfo())!!
            keyboard.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            keyboard.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            keyboard.commitText("c", 1)
        }
        assertEquals("Ctrl+C when the keyboard sends a backspace first", "alpha", clipboard())
        assertEquals("alpha beta", text())
        SystemClock.sleep(700)
        assertEquals("nothing is deleted afterwards either", "alpha beta", text())

        // With Ctrl locked and no key following, a deletion still goes through after the short wait.
        sci(Sci.SCI_GOTOPOS, 10)
        SystemClock.sleep(700)
        tap("Ctrl")
        tap("Ctrl")
        onUi { it.editor.onCreateInputConnection(EditorInfo())!!.deleteSurroundingText(1, 0) }
        SystemClock.sleep(600)
        assertEquals("a lone delete is only delayed, not lost", "alpha bet", text())
        tap("Ctrl")
        assertEquals(0, held())

        // The keyboard log can be copied for a bug report.
        openMenu("Settings", "Keyboard log")
        tapDescribed("Close menu")
        onUi { it.editor.onCreateInputConnection(EditorInfo())!!.commitText("z", 1) }
        device.wait(Until.findObject(By.text(Pattern.compile("Keyboard log \\(tap to copy.*", Pattern.DOTALL))), 4000)!!.click()
        waitUntil("the log to reach the clipboard") { clipboard().contains("commitText(\"z\", 1)") }
        assertTrue("the log includes the shortcut handling", clipboard().contains("shortcut key 'c'"))
        shot("keyboard-log")

        // A hardware keyboard goes through the same table.
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_G, 0, meta))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_G, 0, meta))
        assertTrue("Ctrl+G on a keyboard opens Go to", onScreen("Go to line"))
        tapButton("Cancel")
    }

    @Test
    fun columnSelectionAndColumnEditor() {
        fun held() = onUi { it.editor.softModifiers }
        val original = "abcdefgh\nabcdefgh\nab\nabcdefgh\n"
        setText(original)
        SystemClock.sleep(500)

        // Alt, then a drag, selects a column instead of scrolling.
        tap("Alt")
        val (fromX, fromY) = pointOf(2)
        val (toX, toY) = pointOf(26)
        drag(fromX, fromY, toX, toY)
        device.waitForIdle()
        shot("column-drag")
        assertTrue("dragging with Alt held makes a column selection", sci(Sci.SCI_SELECTIONISRECTANGLE) != 0L)
        assertEquals("one piece per line", 4L, sci(Sci.SCI_GETSELECTIONS))
        assertEquals("columns 2 to 5 of the first line", 2L to 5L,
            sci(Sci.SCI_GETLINESELSTARTPOSITION, 0) to sci(Sci.SCI_GETLINESELENDPOSITION, 0))
        assertEquals("nothing scrolled", 0L, sci(Sci.SCI_GETFIRSTVISIBLELINE))
        assertEquals("Alt lets go after the drag", 0, held())
        assertTrue("the status bar counts what is selected in the column",
            device.wait(Until.hasObject(By.text(Pattern.compile(".*Sel 9 .*4 carets.*"))), 2500) == true)
        onUi { it.editor.typeText("-") }
        assertEquals("typing replaces the column on every line, short lines included",
            "ab-fgh\nab-fgh\nab-\nab-fgh\n", text())
        var undos = 0
        while (text() != original && sci(Sci.SCI_CANUNDO) != 0L && undos++ < 10) sci(Sci.SCI_UNDO)
        assertEquals(original, text())

        // Locked, Alt stays on for the next drag as well.
        tap("Alt")
        tap("Alt")
        drag(fromX, fromY, toX, toY)
        assertTrue(sci(Sci.SCI_SELECTIONISRECTANGLE) != 0L)
        assertEquals(Sci.SCMOD_ALT, held())
        tap("Alt")
        assertEquals(0, held())

        // The Column Editor fills the column with a text...
        openMenu("Edit", "Column Mode...")
        assertTrue(onScreen("Column Mode Tip"))
        shot("column-mode-tip")
        tapButton("OK")
        sci(Sci.SCI_SETRECTANGULARSELECTIONANCHOR, 2)
        sci(Sci.SCI_SETRECTANGULARSELECTIONCARET, 26)
        openMenu("Edit", "Column Editor...")
        assertTrue(onScreen("Column Editor"))
        device.wait(Until.findObject(By.desc("Text to insert")), 4000)!!.text = "XY"
        shot("column-editor-text")
        tapButton("OK")
        assertEquals("abXYfgh\nabXYfgh\nabXY\nabXYfgh\n", text())

        // ...or with numbers; without a selection it runs from the caret's line to the end. Alt+C opens it too.
        sci(Sci.SCI_GOTOPOS, 0)
        tap("Alt")
        onUi { it.editor.onCreateInputConnection(EditorInfo())!!.commitText("c", 1) }
        assertTrue("Alt+C opens the Column Editor", onScreen("Column Editor"))
        tap("Number to Insert")
        device.wait(Until.findObject(By.desc("Initial number")), 4000)!!.text = "9"
        device.findObject(By.clazz("android.widget.ScrollView"))?.scroll(Direction.DOWN, 1f)
        device.waitForIdle()
        tapDescribed("Leading")
        tap("Zeros")
        shot("column-editor-numbers")
        tapButton("OK")
        assertEquals("09abXYfgh\n10abXYfgh\n11abXY\n12abXYfgh\n13", text())
        sci(Sci.SCI_UNDO)
        assertEquals("the Column Editor is one undo step", "abXYfgh\nabXYfgh\nabXY\nabXYfgh\n", text())
    }

    @Test
    fun marksAndIncrementalSearch() {
        fun marks(style: Int) = onUi { Marks.ranges(it.editor, style).size }
        fun clipboard() = onUi { it.editor.getClipboard() }
        setText("cat dog cat\nbird\ncat\n")

        // Mark All puts a highlight on every occurrence of the word at the caret.
        sci(Sci.SCI_GOTOPOS, 1)
        openMenu("Search", "Mark All", "Using 1st Style")
        assertEquals("every cat is marked", 3, marks(1))
        sci(Sci.SCI_SETSEL, 4, 7)
        openMenu("Search", "Mark One", "Using 2nd Style")
        assertEquals(1, marks(2))
        sci(Sci.SCI_GOTOPOS, 0)
        shot("marks")
        openMenu("Search", "Jump Down", "1st Style")
        assertEquals("jump down goes to the next mark", 8L, sci(Sci.SCI_GETCURRENTPOS))
        openMenu("Search", "Jump Up", "1st Style")
        assertEquals("jump up goes back", 0L, sci(Sci.SCI_GETCURRENTPOS))
        openMenu("Search", "Copy Styled Text", "All Styles")
        assertEquals("cat\ndog\ncat\ncat", clipboard())
        openMenu("Search", "Unmark All", "Clear 2nd Style")
        assertEquals(3 to 0, marks(1) to marks(2))
        openMenu("Search", "Unmark All", "Clear all Styles")
        assertEquals(0, marks(1))

        // Search > Mark highlights what a search finds and can bookmark the lines.
        openMenu("Search", "Mark...")
        device.findObjects(By.clazz("android.widget.EditText"))[0].text = "c.t"
        tapDescribed("Regular expression")
        tapDescribed("Bookmark line")
        tapDescribed("Mark all")
        assertTrue(onScreen("Mark: 3 matches."))
        assertEquals(3, marks(Marks.FOUND))
        shot("mark-bar")
        openMenu("Search", "Bookmark", "Copy Bookmarked Lines")
        assertEquals("the lines of the matches were bookmarked", "cat dog cat\ncat\n", clipboard())
        tapDescribed("Clear all marks")
        assertEquals(0, marks(Marks.FOUND))
        tapDescribed("Regular expression")
        tapDescribed("Close")

        // Bookmarked lines as a group.
        sci(Sci.SCI_GOTOPOS, 12)
        openMenu("Search", "Bookmark", "Toggle Bookmark")
        openMenu("Search", "Bookmark", "Inverse Bookmark")
        openMenu("Search", "Bookmark", "Remove Bookmarked Lines")
        assertEquals("only the line that was bookmarked first is left", "bird\n", text())
        sci(Sci.SCI_UNDO)
        assertEquals("cat dog cat\nbird\ncat\n", text())

        // Incremental search follows the typing.
        sci(Sci.SCI_GOTOPOS, 0)
        openMenu("Search", "Incremental Search")
        val field = device.findObjects(By.clazz("android.widget.EditText"))[0]
        field.text = "d"
        waitUntil("the first match") { selection() == "4-5" }
        field.text = "do"
        waitUntil("the longer match at the same place") { selection() == "4-6" }
        field.text = "bi"
        waitUntil("another word") { selection() == "12-14" }
        shot("incremental-search")
        field.text = "zzz"
        assertTrue(onScreen("Can't find the text \"zzz\""))
        tapDescribed("Close")
    }

    @Test
    fun toolsWindowAndOtherCommands() {
        fun clipboard() = onUi { it.editor.getClipboard() }
        fun names() = onUi { activity -> activity.workspace.docs.map { it.name } }
        fun active() = onUi { it.workspace.active.name }

        // Tools: digests of the selection, in a dialog and straight to the clipboard.
        setText("abc")
        sci(Sci.SCI_SELECTALL)
        openMenu("Tools", "MD5", "Generate from selection into clipboard")
        assertEquals("900150983cd24fb0d6963f7d28e17f72", clipboard())
        openMenu("Tools", "SHA-256", "Generate...")
        assertTrue(onScreen("Generate SHA-256 digest"))
        val sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        waitUntil("the digest of the selection") { device.findObject(By.desc("Digest"))?.text == sha }
        shot("hash-dialog")
        tapButton("Copy to Clipboard")
        assertEquals(sha, clipboard())

        // Edit: custom date, redact.
        setText("")
        openMenu("Edit", "Insert", "Date Time (customized)")
        assertTrue("date in the customized format: ${text()}", Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(text()))
        setText("secret")
        sci(Sci.SCI_SETSEL, 0, 3)
        openMenu("Edit", "On Selection", "Redact Selection")
        assertEquals("\u2588\u2588\u2588ret", text())

        // Search: characters in a range of byte values.
        setText("abc \u00e9 x")
        sci(Sci.SCI_GOTOPOS, 0)
        openMenu("Search", "Find characters in range...")
        assertTrue(onScreen("Find Characters in Range"))
        shot("find-characters-in-range")
        tapButton("Find")
        assertEquals("the non-ASCII character is selected", "4-6", selection())

        // View: hide lines, full screen, distraction free.
        setText("a\nb\nc\nd\n")
        sci(Sci.SCI_SETSEL, 2, 5)
        openMenu("View", "Hide Lines")
        assertEquals(0L, sci(Sci.SCI_GETLINEVISIBLE, 1))
        shot("hidden-lines")
        openMenu("View", "Show Hidden Lines")
        assertEquals(1L, sci(Sci.SCI_GETLINEVISIBLE, 1))
        openMenu("View", "Distraction Free Mode")
        tapDescribed("Close menu")
        assertTrue("the toolbar is hidden in distraction free mode", gone("Save"))
        shot("distraction-free")
        // Back closes the keyboard first if it is showing, then leaves the mode. The first time a device goes
        // full screen, Android also explains how to get out, and that notice has to be acknowledged.
        for (attempt in 1..4) {
            device.wait(Until.findObject(By.text("Got it")), 1000)?.click()
            if (onScreen("Save", 800)) break
            device.pressBack()
        }
        assertTrue("Back brings the bars back", onScreen("Save"))

        // Window: sorting and the list of open documents; tab colours and numbered tabs.
        for (name in listOf("b.txt", "a.md", "c.txt")) {
            val file = File(context.cacheDir, name).apply { writeText("file $name\n") }
            onUi { it.openUris(listOf(Uri.fromFile(file))) }
            waitUntil("$name to open") { active() == name }
        }
        assertEquals(listOf("new 1", "b.txt", "a.md", "c.txt"), names())
        openMenu("Window", "Sort By", "Name A to Z")
        assertEquals(listOf("a.md", "b.txt", "c.txt", "new 1"), names())
        openMenu("Window", "Sort By", "Type A to Z")
        assertEquals("by extension, then name", listOf("new 1", "a.md", "b.txt", "c.txt"), names())
        tapDescribed("Tab list")
        shot("windows-list")
        menu("a.md")
        menu("Activate")
        assertEquals("a.md", active())
        openMenu("View", "Tab", "3rd Tab")
        assertEquals("b.txt", active())
        openMenu("View", "Tab", "Apply Colour 2")
        tapDescribed("Close menu")
        assertEquals(2, onUi { it.workspace.active.colour })
        shot("tab-colour")

        // Read-only for every document, including those in the background.
        openMenu("Edit", "Set Read-Only for All Documents")
        assertEquals(1L, sci(Sci.SCI_GETREADONLY))
        tap("c.txt")
        assertEquals("a background tab is read-only too once shown", 1L, sci(Sci.SCI_GETREADONLY))
        openMenu("Edit", "Clear Read-Only for All Documents")
        assertEquals(0L, sci(Sci.SCI_GETREADONLY))

        // The ? menu.
        openMenu("?", "Debug Info...")
        assertTrue(onScreen("Debug Info"))
        shot("debug-info")
        tapButton("Copy to Clipboard")
        assertTrue(clipboard().contains("Scintilla 5.6.9"))
        openMenu("?", "About Pocketpad++...")
        assertTrue(device.wait(Until.hasObject(By.text(Pattern.compile("Pocketpad\\+\\+ 0\\.1\\..*"))), 3000) == true)
        shot("about")
        tapButton("OK")

        // File > Exit closes the app; the session, tab colour included, is there on the next start.
        openMenu("File", "Exit")
        SystemClock.sleep(1500)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // (The untitled tab held text put there by the test without typing, so it had nothing to save.)
        waitUntil("the session to be restored") { names() == listOf("a.md", "b.txt", "c.txt") }
        assertEquals("the tab colour is remembered", 2, onUi { activity -> activity.workspace.docs.first { it.name == "b.txt" }.colour })
    }

    @Test
    fun changeHistoryPanelsAndFileCommands() {
        fun clipboard() = onUi { it.editor.getClipboard() }
        fun names() = onUi { activity -> activity.workspace.docs.map { it.name } }
        fun active() = onUi { it.workspace.active.name }
        fun line() = sci(Sci.SCI_LINEFROMPOSITION, sci(Sci.SCI_GETCURRENTPOS))
        val history = Sci.SC_MASK_HISTORY.toLong()

        // Change history: typed lines get a mark; a loaded file starts without any.
        val file = File(context.cacheDir, "old.txt").apply { writeText("one\ntwo\nthree\nfour\n") }
        onUi { it.openUris(listOf(Uri.fromFile(file))) }
        waitUntil("the file to open") { active() == "old.txt" }
        assertEquals("a loaded file has no changed lines", 0L, (0L..3L).sumOf { sci(Sci.SCI_MARKERGET, it) and history })
        sci(Sci.SCI_GOTOPOS, 4)
        onUi { it.editor.typeText("X") }
        sci(Sci.SCI_GOTOPOS, 15)
        onUi { it.editor.typeText("Y") }
        assertTrue("typing marks the line as changed", sci(Sci.SCI_MARKERGET, 1) and history != 0L)
        sci(Sci.SCI_GOTOPOS, 0)
        shot("change-history")
        openMenu("Search", "Changed Lines", "Go to Next Change")
        assertEquals(1L, line())
        openMenu("Search", "Changed Lines", "Go to Next Change")
        assertEquals(3L, line())
        openMenu("Search", "Changed Lines", "Go to Previous Change")
        assertEquals(1L, line())
        tap("Save")
        waitUntil("the save") { !onUi { it.workspace.isDirty(it.workspace.active) } }
        assertEquals("one\nXtwo\nthree\nYfour\n", file.readText())
        openMenu("Search", "Changed Lines", "Clear Change History")
        assertEquals(0L, (0L..3L).sumOf { sci(Sci.SCI_MARKERGET, it) and history })

        // Rename and delete act on the file in storage.
        openMenu("File", "Rename...")
        device.wait(Until.findObject(By.desc("New name")), 4000)!!.text = "new.md"
        tapButton("OK")
        waitUntil("the rename") { active() == "new.md" }
        val renamed = File(context.cacheDir, "new.md")
        assertTrue("the file was renamed in storage", renamed.exists() && !file.exists())

        // Sessions: the open files as a Notepad++-style file, and back.
        val other = File(context.cacheDir, "other.txt").apply { writeText("other\n") }
        onUi { it.openUris(listOf(Uri.fromFile(other))) }
        waitUntil("the second file") { active() == "other.txt" }
        val session = onUi { it.workspace.exportSession() }
        assertTrue("the session lists both files", session.contains("new.md") && session.contains("other.txt"))
        openMenu("File", "Close All")
        waitUntil("everything to close") { names() == listOf("new 1") }
        onUi { it.loadSessionText(session) }
        waitUntil("the session to load") { names().containsAll(listOf("new.md", "other.txt")) }

        onUi { activity -> activity.workspace.activate(activity.workspace.docs.first { it.name == "new.md" }) }
        openMenu("File", "Delete from Storage")
        assertTrue(onScreen("Delete"))
        shot("delete-dialog")
        tapButton("Delete")
        waitUntil("the tab to close") { "new.md" !in names() }
        assertTrue("the file is gone from storage", !renamed.exists())

        // Character Panel and Clipboard History insert at the caret.
        tap("New")
        openMenu("Edit", "Character Panel")
        shot("character-panel")
        menu(" 65   41   A")
        assertEquals("A", text())
        sci(Sci.SCI_SELECTALL)
        sci(Sci.SCI_COPY)
        sci(Sci.SCI_DOCUMENTEND)
        openMenu("Edit", "Clipboard History")
        menu("A")
        assertEquals("AA", text())

        // The search engine choice is kept.
        openMenu("Edit", "On Selection", "Change Search Engine...")
        menu("DuckDuckGo")
        shot("search-engine")
        tapDescribed("Close menu")
        assertTrue(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("searchEngine", "")!!.contains("duckduckgo"))

        // Non-printing characters get a label.
        setText("a\u00a0b\u200bc")
        val plainWidth = sci(Sci.SCI_POINTXFROMPOSITION, 0, sci(Sci.SCI_GETLENGTH)) - sci(Sci.SCI_POINTXFROMPOSITION, 0, 0)
        openMenu("View", "Show Symbol", "Show Non-Printing Characters")
        tapDescribed("Close menu")
        val labelledWidth = sci(Sci.SCI_POINTXFROMPOSITION, 0, sci(Sci.SCI_GETLENGTH)) - sci(Sci.SCI_POINTXFROMPOSITION, 0, 0)
        assertTrue("the labels take their own room in the line ($plainWidth -> $labelledWidth)", labelledWidth > plainWidth * 2)
        shot("non-printing-characters")
        assertTrue(context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("showNpc", false))
    }

    @Test
    fun findAllAndFindInFiles() {
        fun active() = onUi { it.workspace.active.name }
        fun heading(pattern: String) = device.wait(Until.hasObject(By.text(Pattern.compile(pattern))), 6000) == true
        val files = listOf(
            "a.txt" to "alpha\nbeta alpha\n",
            "b.md" to "gamma\nALPHA\n",
            "c.bin" to "alpha\u0000binary\n",
        ).map { (name, content) -> File(context.cacheDir, name).apply { writeText(content) } }
        for (file in files.take(2)) {
            onUi { it.openUris(listOf(Uri.fromFile(file))) }
            waitUntil("${file.name} to open") { active() == file.name }
        }

        // Find All in All Opened Documents lists every hit; a tap goes there.
        tap("Find")
        device.findObjects(By.clazz("android.widget.EditText"))[0].text = "alpha"
        openMenu("Search", "Find All in All Opened Documents")
        assertTrue("the heading sums up the search", heading("Search \"alpha\" \\(3 hits in 2 of 2 documents\\)"))
        assertTrue(onScreen("a.txt (2 hits)"))
        shot("find-all-results")
        tap("Line 2: beta alpha")
        waitUntil("the hit to be shown") { active() == "a.txt" && selection() == "11-16" }
        openMenu("Search", "Next Search Result")
        waitUntil("the next hit, in the other document") { active() == "b.md" && selection() == "6-11" }
        openMenu("Search", "Previous Search Result")
        waitUntil("back to the hit before") { active() == "a.txt" && selection() == "11-16" }

        // The switches of the Find bar apply.
        tapDescribed("Match case")
        openMenu("Search", "Find All in Current Document")
        assertTrue(heading("Search \"alpha\" \\(2 hits in 1 of 1 document\\)"))
        tapDescribed("Match case")
        tapDescribed("Close")
        tapDescribed("Close search results")
        assertTrue("the panel closes", gone("a.txt (2 hits)"))

        // Find in Files reads files from storage, passes over what is not text, and opens a file for a hit.
        openMenu("File", "Close All")
        waitUntil("everything to close") { onUi { it.workspace.docs.size } == 1 && active() == "new 1" }
        onUi { activity -> activity.findInFiles(files.map { Uri.fromFile(it) to it.name }, "alpha", SearchOptions()) }
        assertTrue("all three files were looked at, two had hits", heading("Search \"alpha\" \\(3 hits in 2 of 3 files\\)"))
        shot("find-in-files-results")
        tap("Line 2: ALPHA")
        waitUntil("the file to open at the hit") { active() == "b.md" && selection() == "6-11" }
        shot("find-in-files-opened")
    }

    @Test
    fun macros() {
        fun soft(text: String) = onUi { it.editor.onCreateInputConnection(EditorInfo())!!.commitText(text, 1) }
        setText("a\nb\nc")

        // Record: type a dash, go down a line and to its start.
        openMenu("Macro", "Start Recording")
        assertTrue("the status bar shows that recording is on", device.wait(Until.hasObject(By.text(Pattern.compile(".*REC.*"))), 3000) == true)
        soft("- ")
        tap("\u2193")
        tap("Home")
        openMenu("Macro", "Stop Recording")
        assertEquals("- a\nb\nc", text())
        soft("x")
        sci(Sci.SCI_UNDO)

        openMenu("Macro", "Playback")
        assertEquals("playback repeats the recording at the caret", "- a\n- b\nc", text())
        openMenu("Macro", "Run a Macro Multiple Times...")
        assertTrue(onScreen("Run a Macro Multiple Times"))
        tap("Run until the end of file")
        shot("macro-run-multiple")
        tapButton("Run")
        assertEquals("run until the end of file finishes the job", "- a\n- b\n- c", text())

        // A saved macro is listed in the menu and kept for the next start.
        openMenu("Macro", "Save Current Recorded Macro...")
        device.wait(Until.findObject(By.desc("Macro name")), 4000)!!.text = "Dash"
        tapButton("OK")
        setText("x\ny")
        openMenu("Macro", "Dash")
        assertEquals("- x\ny", text())
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.waitForIdle()
        tapDescribed("Menu")
        menu("Macro")
        assertTrue("the saved macro is still there after a restart", onScreen("Dash"))
        shot("macro-menu")
        tapDescribed("Close menu")
    }

    @Test
    fun pinnedTabsMonitoringRunAndPrint() {
        fun names() = onUi { activity -> activity.workspace.docs.map { it.name } }
        fun pinned() = onUi { activity -> activity.workspace.docs.filter { it.pinned }.map { it.name } }
        val first = File(context.cacheDir, "first.txt").apply { writeText("one\n") }
        val log = File(context.cacheDir, "app.log").apply { writeText("started\n") }
        onUi { it.openUris(listOf(Uri.fromFile(first), Uri.fromFile(log))) }
        waitUntil("the files to open") { onUi { it.workspace.active.name } == "app.log" }
        tap("New")
        val untitled = onUi { it.workspace.active.name }

        // Pinning moves a tab to the front and marks it.
        find("app.log").click()
        device.waitForIdle()
        openMenu("View", "Tab", "Pin Tab")
        tapDescribed("Close menu")
        assertEquals("app.log", names().first())
        assertEquals(listOf("app.log"), pinned())
        val tab = device.wait(Until.findObject(By.textEndsWith("app.log")), 3000)
        assertTrue("the pinned tab shows a pin: ${tab?.text}", tab != null && tab.text.startsWith("\uD83D\uDCCC"))
        shot("tab-pinned")
        openMenu("File", "Close Multiple Documents", "Close All but Pinned Documents")
        assertEquals("only the pinned tab is left", listOf("app.log"), names())
        assertTrue(untitled !in names())

        // Monitoring: the file is read-only here and follows what another program writes to it.
        openMenu("View", "Monitoring (tail -f)")
        tapDescribed("Close menu")
        assertEquals("read-only while monitoring", 1L, sci(Sci.SCI_GETREADONLY))
        SystemClock.sleep(1100)
        log.appendText("request 1\nrequest 2\n")
        waitUntil("the new lines to show up", 12000) { text() == "started\nrequest 1\nrequest 2\n" }
        assertEquals("the caret follows the end of the file", sci(Sci.SCI_GETLENGTH), sci(Sci.SCI_GETCURRENTPOS))
        assertEquals(1L, sci(Sci.SCI_GETREADONLY))
        shot("monitoring")
        openMenu("View", "Monitoring (tail -f)")
        tapDescribed("Close menu")
        assertEquals("editable again", 0L, sci(Sci.SCI_GETREADONLY))

        // Pinned tabs are remembered.
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.waitForIdle()
        waitUntil("the session to come back") { names().contains("app.log") }
        assertEquals(listOf("app.log"), pinned())

        // Binary copy and paste keep NUL characters.
        onUi {
            it.editor.send(Sci.SCI_CLEARALL)
            it.editor.sendBytes(Sci.SCI_ADDTEXT, 3, byteArrayOf(65, 0, 66))
            it.editor.send(Sci.SCI_SELECTALL)
        }
        openMenu("Edit", "Paste Special", "Copy Binary Content")
        sci(Sci.SCI_DOCUMENTEND)
        openMenu("Edit", "Paste Special", "Paste Binary Content")
        assertEquals(6L, sci(Sci.SCI_GETLENGTH))
        assertEquals("the NUL character came along", 0L, sci(Sci.SCI_GETCHARAT, 4))
        assertEquals(66L, sci(Sci.SCI_GETCHARAT, 5))
        sci(Sci.SCI_SETSAVEPOINT)

        // Run: an address with the word at the caret filled in.
        openMenu("Run", "Run...")
        assertTrue(device.wait(Until.hasObject(By.desc("Address to open")), 4000) == true)
        device.findObject(By.desc("Address to open")).text = "https://example.org/?q=\$(CURRENT_WORD)"
        shot("run-dialog")
        tapButton("Cancel")

        // Print hands the document to Android's print screen.
        openMenu("File", "Print...")
        val printing = device.wait(Until.hasObject(By.pkg("com.android.printspooler")), 15000) == true
        shot("print")
        assertTrue("Android's print screen opened", printing)
        device.pressBack()
        device.wait(Until.hasObject(By.pkg(context.packageName)), 8000)
        if (device.hasObject(By.pkg("com.android.printspooler"))) {
            device.pressBack()
            device.wait(Until.hasObject(By.pkg(context.packageName)), 8000)
        }
    }

    @Test
    fun preferencesAndShortcutMapper() {
        fun soft(text: String) = onUi { it.editor.onCreateInputConnection(EditorInfo())!!.commitText(text, 1) }
        fun binding(id: String) = onUi { it.shortcutTable.binding(id) }
        fun press(keyCode: Int, meta: Int) {
            val now = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
            instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
            device.waitForIdle()
        }
        assertEquals("every built-in shortcut belongs to a command in the menus", emptyList<String>(), onUi { it.unknownShortcutCommands() })

        // Preferences: editing options reach the editor at once.
        setText("\tint x;", "cpp")
        openMenu("Settings", "Preferences...")
        menu("Editing")
        menu("Tab Size")
        menu("8")
        assertEquals(8L, sci(Sci.SCI_GETTABWIDTH))
        tapDescribed("Back")
        menu("Replace Tabs by Spaces")
        assertEquals(0L, sci(Sci.SCI_GETUSETABS))
        menu("Highlight Current Line")
        assertEquals(0L, sci(Sci.SCI_GETCARETLINEVISIBLE))
        menu("Vertical Edge")
        menu("Column 80")
        assertEquals(80L, sci(Sci.SCI_GETEDGECOLUMN))
        assertEquals(Sci.EDGE_LINE.toLong(), sci(Sci.SCI_GETEDGEMODE))
        tapDescribed("Back")
        menu("Caret")
        menu("Block")
        assertEquals(Sci.CARETSTYLE_BLOCK.toLong(), sci(Sci.SCI_GETCARETSTYLE))
        tapDescribed("Back")
        shot("preferences-editing")
        tapDescribed("Back")
        menu("New Document")
        menu("Line Ending")
        menu("Windows (CR LF)")
        tapDescribed("Back")
        menu("Encoding")
        menu("UTF-8-BOM")
        tapDescribed("Close menu")
        tap("New")
        assertEquals("new documents use the chosen line ending", Eol.CRLF.sciMode.toLong(), sci(Sci.SCI_GETEOLMODE))
        assertTrue(onScreen("CRLF"))
        assertTrue("and the chosen encoding", onScreen("UTF-8-BOM"))
        assertEquals("the tab size holds for every tab", 8L, sci(Sci.SCI_GETTABWIDTH))
        tap("Tab")
        assertEquals("the Tab key gives spaces", "        ", text())
        shot("preferences-new-document")

        // Shortcut Mapper: give UPPERCASE a shortcut by pressing it on a keyboard.
        openMenu("Settings", "Shortcut Mapper...")
        menu("Edit")
        menu("Convert Case to")
        menu("UPPERCASE")
        assertTrue("the shortcut dialog opened", device.wait(Until.hasObject(By.desc("Key")), 4000) == true)
        press(KeyEvent.KEYCODE_U, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        shot("shortcut-dialog")
        tapButton("OK")
        assertEquals(KeyBinding(Sci.SCMOD_CTRL or Sci.SCMOD_ALT, 'U'.code), binding("Edit > Convert Case to > UPPERCASE"))
        assertTrue("the mapper lists the new shortcut", onScreen("Ctrl+Alt+U"))
        shot("shortcut-mapper")

        // A shortcut that is taken can be moved over.
        menu("lowercase")
        device.wait(Until.hasObject(By.desc("Key")), 4000)
        press(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)
        tapButton("OK")
        assertTrue("the clash is pointed out", onScreen("Ctrl+S is taken"))
        shot("shortcut-taken")
        tapButton("Reassign")
        assertEquals(KeyBinding(Sci.SCMOD_CTRL, 'S'.code), binding("Edit > Convert Case to > lowercase"))
        assertNull("Save gave it up", binding("File > Save"))
        tapDescribed("Close menu")

        // The new shortcuts work from the on-screen Ctrl and Alt keys.
        setText("Mixed Case")
        sci(Sci.SCI_SELECTALL)
        tap("Ctrl")
        tap("Alt")
        soft("u")
        assertEquals("MIXED CASE", text())
        sci(Sci.SCI_SELECTALL)
        tap("Ctrl")
        soft("s")
        assertEquals("mixed case", text())

        // Everything is still there after a restart.
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.waitForIdle()
        assertEquals(8L, sci(Sci.SCI_GETTABWIDTH))
        assertEquals(0L, sci(Sci.SCI_GETUSETABS))
        assertEquals(80L, sci(Sci.SCI_GETEDGECOLUMN))
        assertEquals(KeyBinding(Sci.SCMOD_CTRL or Sci.SCMOD_ALT, 'U'.code), binding("Edit > Convert Case to > UPPERCASE"))
        assertNull(binding("File > Save"))

        // The menus show the shortcuts, and the mapper can put them all back.
        tapDescribed("Menu")
        menu("File")
        assertTrue("menus show their shortcuts", onScreen("Ctrl+N"))
        shot("menu-shortcut-hints")
        tapDescribed("Close menu")
        openMenu("Settings", "Shortcut Mapper...")
        menu("Reset All Shortcuts")
        tapDescribed("Close menu")
        assertEquals(KeyBinding(Sci.SCMOD_CTRL, 'S'.code), binding("File > Save"))
        assertNull(binding("Edit > Convert Case to > UPPERCASE"))
        onUi { it.editor.send(Sci.SCI_SETSAVEPOINT) }
    }

    @Test
    fun styleConfiguratorThemesAndSelectionMenu() {
        setText("int main() { return 0; }", "cpp")
        val keyword = 5L
        val original = sci(Sci.SCI_STYLEGETFORE, keyword)

        // Style Configurator: change one colour of the current language.
        openMenu("Settings", "Style Configurator...")
        shot("style-configurator")
        menu("C++")
        menu("INSTRUCTION WORD")
        device.wait(Until.findObject(By.desc("Foreground colour")), 4000)!!.text = "FF0000"
        device.waitForIdle()
        shot("style-dialog")
        tapButton("OK")
        assertEquals("keywords are red now (Scintilla keeps colours as BGR)", 0x0000FFL, sci(Sci.SCI_STYLEGETFORE, keyword))
        shot("style-changed")
        tapDescribed("Close menu")

        // The change belongs to the theme and is kept.
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.waitForIdle()
        setText("int main() { return 0; }", "cpp")
        assertEquals(0x0000FFL, sci(Sci.SCI_STYLEGETFORE, keyword))
        openMenu("Settings", "Style Configurator...")
        menu("C++")
        menu("INSTRUCTION WORD")
        tapButton("Reset")
        assertEquals("reset brings the theme's colour back", original, sci(Sci.SCI_STYLEGETFORE, keyword))
        tapDescribed("Back")
        menu("Font")
        menu("Serif")
        shot("font-serif")
        menu("Monospace")
        tapDescribed("Close menu")

        // A Notepad++ theme file can be imported and then picked like the built-in ones.
        val zenburn = context.assets.open("npp/themes/Zenburn.xml").use { it.readBytes() }
        try {
            assertEquals("My Theme.xml", onUi { it.importTheme("My Theme.xml", zenburn) })
            assertNull("other files are turned down", onUi { it.importTheme("notes.xml", "<notes/>".toByteArray()) })
            openMenu("Settings", "Theme", "My Theme")
            assertTrue("the imported theme is in use", onUi { it.workspace.theme.isDark })
            shot("imported-theme")
            tapDescribed("Back")
            menu("Theme")
            menu("Default (stylers.xml)")
            tapDescribed("Close menu")
        } finally {
            File(context.filesDir, "themes").deleteRecursively()
        }

        // Extra commands for the selection toolbar.
        openMenu("Settings", "Selection Menu Items...")
        tap("UPPERCASE")
        tap("Comment")
        shot("selection-menu-items")
        tapButton("OK")
        assertEquals(listOf("UPPERCASE", "Comment"), onUi { activity -> activity.editor.extraActions.map { it.first } })
        setText("word here", "cpp")
        sci(Sci.SCI_SETSEL, 0, 4)
        onUi { it.editor.extraActions[0].second() }
        assertEquals("WORD here", text())
        onUi { it.editor.extraActions[1].second() }
        assertEquals("// WORD here", text())
        val (x, y) = pointOf(3)
        longPressAt(x, y)
        shot("selection-toolbar")
        onUi { it.editor.send(Sci.SCI_SETSAVEPOINT) }
    }

    @Test
    fun documentListAndFolderAsWorkspace() {
        fun active() = onUi { it.workspace.active.name }
        val project = File(context.cacheDir, "project").apply {
            deleteRecursively()
            File(this, "src").mkdirs()
            File(this, "readme.txt").writeText("About this project\n")
            File(this, "src/tool.py").writeText("def run():\n    return 1\n")
        }
        try {
            // Document List: the open documents, to switch between.
            tap("New")
            tap("New")
            openMenu("View", "Document List")
            panelRow("new 1")
            shot("document-list")
            panelClick("new 1")
            device.waitForIdle()
            assertEquals("new 1", active())
            assertTrue("the panel closes after a choice", device.wait(Until.gone(By.desc("Close panel")), 3000) == true)

            // Folder as Workspace: a tree of the folder, whose files open with a tap.
            onUi { it.addWorkspaceFolder(Uri.fromFile(project)) }
            panelRow("readme.txt")
            panelClick("src")
            panelRow("tool.py")
            shot("folder-as-workspace")
            panelClick("tool.py")
            waitUntil("the file from the tree to open") { active() == "tool.py" }
            assertEquals("def run():\n    return 1\n", text())
            assertTrue("with its language", onScreen("Python"))

            // The folder is remembered, both pages share the panel, and a long press removes the folder.
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            device.waitForIdle()
            assertEquals(listOf(Uri.fromFile(project)), onUi { it.workspaceFolders() })
            openMenu("View", "Folder as Workspace")
            panelRow("project")
            tap("Documents")
            panelRow("tool.py")
            tap("Folders")
            panelLongClick("project")
            device.waitForIdle()
            tap("Remove from Workspace")
            assertEquals(emptyList<Uri>(), onUi { it.workspaceFolders() })
            shot("folder-removed")
            tapDescribed("Close panel")
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun functionList() {
        // Notepad++'s rules are written for another regular expression engine; this device's must take them all.
        // (The one exception has a bracket missing in Notepad++'s own file.)
        assertEquals(listOf("fortran77.xml: 1 skipped"), onUi { it.functionListProblems() })

        val python = "import os\n\n\ndef first(a, b):\n    return a\n\n\nclass Shop:\n    def open(self):\n        pass\n\n" +
            "    def close(self):\n        pass\n" + "\n".repeat(70) + "def last():\n    pass\n"
        setText(python, "python")
        openMenu("View", "Function List")
        panelRow("first(a, b)")
        panelRow("Shop")
        panelRow("close(self)")
        shot("function-list-python")
        panelClick("last()")
        device.waitForIdle()
        val line = python.substring(0, python.indexOf("def last")).count { it == '\n' }.toLong()
        waitUntil("the caret to go to the function") { sci(Sci.SCI_LINEFROMPOSITION, sci(Sci.SCI_GETCURRENTPOS)) == line }
        assertTrue("the function was scrolled into view", sci(Sci.SCI_GETFIRSTVISIBLELINE) > 20)

        val c = "#include <stdio.h>\n\n/* int hidden(void) { } */\nstatic int add(int a, int b)\n{\n    return a + b;\n}\n\nint main(void)\n{\n    return add(1, 2);\n}\n"
        setText(c, "c")
        openMenu("View", "Function List")
        panelRow("add(int a, int b)")
        panelRow("main(void)")
        assertTrue("a function inside a comment is not listed", device.findObject(By.textStartsWith("hidden")) == null)
        tap("Sort: as written")
        assertTrue(onScreen("Sort: A\u2013Z"))
        shot("function-list-c")
        tap("Documents")
        tap("Functions")
        panelClick("main(void)")
        device.waitForIdle()
        assertEquals(8L, sci(Sci.SCI_LINEFROMPOSITION, sci(Sci.SCI_GETCURRENTPOS)))

        setText("just words", "normal")
        openMenu("View", "Function List")
        assertTrue(device.wait(Until.hasObject(By.text(Pattern.compile("Notepad\\+\\+ has no function list rules.*"))), 5000) == true)
        tapDescribed("Close panel")
    }

    @Test
    fun autoCompletionAndParameterHints() {
        fun soft(text: String) = onUi { it.editor.onCreateInputConnection(EditorInfo())!!.commitText(text, 1) }
        fun offered() = onUi { it.completionWords }
        fun hint() = onUi { it.callHint }
        setText("total = 1\ntotals = [total]\n", "python")
        sci(Sci.SCI_DOCUMENTEND)

        // Words of the document are offered from the second letter on, above the keyboard.
        soft("t")
        assertEquals("one letter is too little", emptyList<String>(), offered())
        soft("o")
        assertTrue("the document's words are offered: ${offered()}", offered().containsAll(listOf("total", "totals")))
        shot("auto-completion")
        tap("totals")
        assertEquals("total = 1\ntotals = [total]\ntotals", text())
        assertEquals("the list goes once a word was taken", emptyList<String>(), offered())

        // So are the language's own functions, with a hint for their parameters once the bracket is typed.
        soft("\n")
        soft("r")
        soft("a")
        soft("n")
        assertTrue("python's functions are offered: ${offered()}", "range" in offered())
        tap("range")
        soft("(")
        waitUntil("the parameter hint") { hint().contains("range") }
        shot("parameter-hint")
        soft(")")
        assertEquals("the hint goes when the call is closed", "", hint())
        // Asked for by command, inside the brackets.
        sci(Sci.SCI_CHARLEFT)
        openMenu("Edit", "Auto-Completion", "Function Parameters Hint")
        assertTrue(hint().contains("range"))
        sci(Sci.SCI_DOCUMENTSTART)
        waitUntil("the hint to go when the caret leaves the line") { hint().isEmpty() }

        // Word Completion with one candidate completes straight away.
        setText("alphabet\nalp")
        sci(Sci.SCI_DOCUMENTEND)
        openMenu("Edit", "Auto-Completion", "Word Completion")
        assertEquals("alphabet\nalphabet", text())

        // Path Completion lists the folder being typed.
        val folder = File(context.cacheDir, "paths").apply {
            deleteRecursively()
            mkdirs()
            File(this, "report.txt").writeText("x")
            File(this, "readme.md").writeText("x")
            File(this, "other.txt").writeText("x")
        }
        try {
            setText("open ${folder.path}/re")
            sci(Sci.SCI_DOCUMENTEND)
            openMenu("Edit", "Auto-Completion", "Path Completion")
            assertEquals(listOf("readme.md", "report.txt"), offered())
            shot("path-completion")
            tap("report.txt")
            assertEquals("open ${folder.path}/report.txt", text())
        } finally {
            folder.deleteRecursively()
        }

        // It can be turned off in the preferences.
        openMenu("Settings", "Preferences...")
        menu("Auto-Completion")
        menu("Enable Auto-Completion on Each Input")
        shot("preferences-auto-completion")
        tapDescribed("Close menu")
        setText("total = 1\n")
        sci(Sci.SCI_DOCUMENTEND)
        soft("t")
        soft("o")
        assertEquals(emptyList<String>(), offered())
        onUi { it.editor.send(Sci.SCI_SETSAVEPOINT) }
    }

    @Test
    fun documentMap() {
        fun mapLine() = onUi { it.documentMap!!.view.send(Sci.SCI_GETFIRSTVISIBLELINE) }
        val text = (1..400).joinToString("\n") { "line_$it = value(${it * 3})  # note $it" }
        setText(text, "python")
        openMenu("View", "Document Map")
        tapDescribed("Close menu")
        assertNotNull(onUi { it.documentMap })
        assertTrue("the map shows the editor's document", onUi { it.documentMap!!.view.send(Sci.SCI_GETDOCPOINTER) == it.editor.send(Sci.SCI_GETDOCPOINTER) })
        assertTrue("in much smaller print", onUi { it.documentMap!!.view.send(Sci.SCI_LINESONSCREEN) > 2 * it.editor.send(Sci.SCI_LINESONSCREEN) })
        shot("document-map")

        // The map follows the editor, and its mark shows which part is on screen.
        assertEquals(0L, mapLine())
        sci(Sci.SCI_SETFIRSTVISIBLELINE, 300)
        waitUntil("the map to follow the editor") { mapLine() > 0 }
        val (top, bottom) = onUi { it.documentMap!!.mark }
        assertTrue("the mark is on the map: $top to $bottom", top > 0 && bottom > top)
        shot("document-map-scrolled")

        // A touch on the map takes the editor there.
        val bounds = device.findObject(By.desc("Document map")).visibleBounds
        device.click(bounds.centerX(), bounds.top + 12)
        waitUntil("the editor to go where the map was touched") { sci(Sci.SCI_GETFIRSTVISIBLELINE) < 250 }

        // What is typed shows in the map, and the map goes with the tab.
        sci(Sci.SCI_DOCUMENTSTART)
        onUi { it.editor.typeText("NEW ") }
        assertTrue(onUi { it.documentMap!!.view.text.startsWith("NEW line_1") })
        tap("New")
        assertTrue("the map shows the new tab", onUi { it.documentMap!!.view.send(Sci.SCI_GETDOCPOINTER) == it.editor.send(Sci.SCI_GETDOCPOINTER) })
        assertEquals("", onUi { it.documentMap!!.view.text })

        // It is still there after a restart, and can be closed again.
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.waitForIdle()
        assertNotNull("the map is remembered", onUi { it.documentMap })
        openMenu("View", "Document Map")
        tapDescribed("Close menu")
        assertNull(onUi { it.documentMap })
        assertTrue(device.findObject(By.desc("Document map")) == null)
        onUi { it.editor.send(Sci.SCI_SETSAVEPOINT) }
    }

    @Test
    fun projectPanels() {
        fun active() = onUi { it.workspace.active.name }
        fun name(description: String, value: String) {
            device.wait(Until.findObject(By.desc(description)), 4000)!!.text = value
            tapButton("OK")
        }
        val projects = File(context.filesDir, "projects")
        projects.deleteRecursively()
        val chapter = File(context.cacheDir, "chapter.txt").apply { writeText("Once upon a time\n") }
        try {
            onUi { it.openUris(listOf(Uri.fromFile(chapter))) }
            waitUntil("the file to open") { active() == "chapter.txt" }

            // A project with a folder, and the open file put into it.
            openMenu("View", "Project Panels", "Project Panel 1")
            tap("New Project")
            name("Project name", "Book")
            panelLongClick("Book")
            tap("Add Folder...")
            name("Folder name", "Drafts")
            panelLongClick("Drafts")
            tap("Add Current File")
            panelRow("chapter.txt")
            panelLongClick("Book")
            tap("Rename...")
            name("New name", "Novel")
            panelRow("Novel")
            shot("project-panel")
            tapDescribed("Close panel")

            // The file opens from the project after its tab was closed.
            tapDescribed("Close chapter.txt")
            assertTrue(active() != "chapter.txt")
            openMenu("View", "Project Panels", "Project Panel 1")
            panelClick("chapter.txt")
            waitUntil("the file to open from the project") { active() == "chapter.txt" }

            // Projects are kept, each panel on its own, and a panel that holds something has its tab.
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            device.waitForIdle()
            assertEquals(listOf("Novel"), onUi { activity -> activity.projectPanel(0).map { it.name } })
            assertEquals(listOf("chapter.txt"), onUi { activity -> activity.projectPanel(0)[0].files().map { it.name } })
            assertTrue(onUi { it.projectPanel(1).isEmpty() })
            openMenu("View", "Document List")
            tap("Project 1")
            panelLongClick("Novel")
            tap("Remove")
            assertTrue(onUi { it.projectPanel(0).isEmpty() })
            tapDescribed("Close panel")
        } finally {
            projects.deleteRecursively()
        }
    }

    @Test
    fun userDefinedLanguages() {
        fun active() = onUi { it.workspace.active.name }
        fun names() = onUi { activity -> activity.workspace.languages.userLanguages.map { it.name } }
        val folder = File(context.filesDir, "userDefineLangs")
        val notes = File(context.cacheDir, "notes.md").apply { writeText("# Title\n\nplain words\n") }
        val tiny = """<NotepadPlus>
    <UserLang name="Tiny" ext="tiny" udlVersion="2.1">
        <Settings>
            <Global caseIgnored="no" allowFoldOfComments="no" foldCompact="no" forcePureLC="0" decimalSeparator="0" />
            <Prefix Keywords1="no" Keywords2="no" Keywords3="no" Keywords4="no" Keywords5="no" Keywords6="no" Keywords7="no" Keywords8="no" />
        </Settings>
        <KeywordLists>
            <Keywords name="Comments">00// 01 02 03/* 04*/</Keywords>
            <Keywords name="Keywords1">let print</Keywords>
        </KeywordLists>
        <Styles>
            <WordsStyle name="DEFAULT" fgColor="000000" bgColor="FFFFFF" fontStyle="0" nesting="0" />
            <WordsStyle name="LINE COMMENTS" fgColor="008000" bgColor="FFFFFF" fontStyle="2" nesting="0" />
            <WordsStyle name="KEYWORDS1" fgColor="0000FF" bgColor="FFFFFF" fontStyle="1" nesting="0" />
        </Styles>
    </UserLang>
</NotepadPlus>"""
        try {
            // Notepad++'s own Markdown definition comes with the app and answers for .md files.
            onUi { it.openUris(listOf(Uri.fromFile(notes))) }
            waitUntil("the file to open") { active() == "notes.md" }
            assertTrue("Markdown is recognised: ${onUi { it.workspace.active.language.name }}", onScreen("Markdown (preinstalled)"))
            sci(Sci.SCI_COLOURISE, 0, -1)
            assertTrue("the heading is styled apart from the text", sci(Sci.SCI_GETSTYLEAT, 0) != sci(Sci.SCI_GETSTYLEAT, 10))
            shot("udl-markdown")

            // A definition file of one's own.
            assertEquals(listOf("Tiny"), onUi { it.importUserLanguages("tiny.xml", tiny.toByteArray()) })
            assertEquals("other files are turned down", emptyList<String>(), onUi { it.importUserLanguages("x.xml", "<a/>".toByteArray()) })
            tap("New")
            setText("let x = 1 // note", "Tiny")
            sci(Sci.SCI_COLOURISE, 0, -1)
            assertEquals("a keyword of the language", 4L, sci(Sci.SCI_GETSTYLEAT, 0))
            assertEquals("its line comment", 2L, sci(Sci.SCI_GETSTYLEAT, 12))
            assertEquals("the keyword in the definition's colour (blue, as BGR)", 0xFF0000L, sci(Sci.SCI_STYLEGETFORE, 4))
            openMenu("Edit", "Comment/Uncomment", "Toggle Single Line Comment")
            assertEquals("commenting uses the language's own mark", "// let x = 1 // note", text())
            sci(Sci.SCI_UNDO)
            tapDescribed("Menu")
            menu("Language")
            menu("Tiny")
            shot("udl-language-menu")
            tapDescribed("Close menu")

            // Editing the definition: it is a file like any other, and saving it brings the change in.
            openMenu("Language", "User Defined Language", "Define your language...")
            shot("udl-manager")
            menu("Tiny")
            menu("Edit Definition")
            waitUntil("the definition to open") { active() == "tiny.xml" }
            onUi { it.editor.text = it.editor.text.replace("let print", "let print fn") }
            tap("Save")
            waitUntil("the definition to be saved") { !onUi { it.workspace.isDirty(it.workspace.active) } }
            tap("New")
            setText("fn y", "Tiny")
            sci(Sci.SCI_COLOURISE, 0, -1)
            assertEquals("the new keyword is known", 4L, sci(Sci.SCI_GETSTYLEAT, 0))

            // Removing it leaves its documents as normal text.
            openMenu("Language", "User Defined Language", "Define your language...")
            menu("Tiny")
            menu("Remove")
            assertTrue("Tiny" !in names())
            assertEquals("normal", onUi { it.workspace.active.language.name })
            onUi { it.editor.send(Sci.SCI_SETSAVEPOINT) }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun splitViewAndSecondWindow() {
        fun active() = onUi { it.workspace.active.name }
        fun otherLine() = onUi { it.otherView!!.send(Sci.SCI_GETFIRSTVISIBLELINE) }
        fun otherName() = onUi { it.otherDoc?.name }
        setText((1..300).joinToString("\n") { "row $it" })

        // Clone to Other View: the same document twice, the second time for reading.
        openMenu("View", "Move/Clone Current Document", "Clone to Other View")
        assertNotNull(onUi { it.otherView })
        assertTrue("both views show one document", onUi { it.otherView!!.send(Sci.SCI_GETDOCPOINTER) == it.editor.send(Sci.SCI_GETDOCPOINTER) })
        assertTrue(device.wait(Until.hasObject(By.desc("Other view")), 3000) == true)
        sci(Sci.SCI_DOCUMENTSTART)
        onUi { it.editor.typeText("TOP ") }
        assertTrue("what is typed above shows below", onUi { it.otherView!!.text.startsWith("TOP row 1") })
        onUi { it.otherView!!.typeText("x") }
        assertTrue("the second view takes no typing", text().startsWith("TOP row 1\n"))
        shot("split-clone")

        // Synchronized scrolling works both ways, and the zoom is shared.
        openMenu("View", "Synchronize Vertical Scrolling")
        tapDescribed("Close menu")
        sci(Sci.SCI_SETFIRSTVISIBLELINE, 100)
        waitUntil("the second view to scroll along") { otherLine() == 100L }
        onUi { it.otherView!!.send(Sci.SCI_LINESCROLL, 0, 20) }
        waitUntil("the editor to follow the second view") { sci(Sci.SCI_GETFIRSTVISIBLELINE) == 120L }
        shot("split-scrolled-together")
        openMenu("View", "Synchronize Vertical Scrolling")
        tapDescribed("Close menu")
        openMenu("View", "Zoom", "Zoom In")
        assertEquals("the second view zooms along", sci(Sci.SCI_GETZOOM), onUi { it.otherView!!.send(Sci.SCI_GETZOOM) })
        openMenu("View", "Zoom", "Restore Default Zoom")

        // Move to Other View puts the document below and leaves the editor on its neighbour.
        tap("New")
        setText("second document")
        openMenu("View", "Move/Clone Current Document", "Move to Other View")
        assertEquals("new 2", otherName())
        assertEquals("new 1", active())
        assertEquals("second document", onUi { it.otherView!!.text })
        shot("split-move")

        // Focus on Another View swaps the two, which is how the other document gets edited.
        tapDescribed("Focus on Another View")
        assertEquals("new 2", active())
        assertEquals("new 1", otherName())
        assertEquals("second document", text())
        assertTrue(onUi { it.otherView!!.text.startsWith("TOP row 1") })
        onUi { it.editor.typeText("edited ") }
        assertTrue(text().contains("edited "))
        tapDescribed("Close other view")
        assertNull(onUi { it.otherView })
        assertTrue(device.findObject(By.desc("Other view")) == null)

        // The second view goes when its document is closed.
        onUi { it.editor.send(Sci.SCI_SETSAVEPOINT) }
        openMenu("View", "Move/Clone Current Document", "Clone to Other View")
        assertEquals("new 2", otherName())
        tapDescribed("Close new 2")
        assertNull("closing the tab closes its second view", onUi { it.otherView })

        // Open in New Instance: the file in a window of its own, while it stays open here.
        val file = File(context.cacheDir, "window.txt").apply { writeText("in a window of its own\n") }
        onUi { it.openUris(listOf(Uri.fromFile(file))) }
        waitUntil("the file to open") { active() == "window.txt" }
        val monitor = instrumentation.addMonitor(SecondWindowActivity::class.java.name, null, false)
        try {
            openMenu("View", "Move/Clone Current Document", "Open in New Instance")
            val second = monitor.waitForActivityWithTimeout(15000) as? MainActivity
            assertNotNull("a second window opened", second)
            fun inSecond(): String {
                var name = ""
                instrumentation.runOnMainSync { name = second!!.workspace.active.name }
                return name
            }
            waitUntil("the file to show in the second window") { inSecond() == "window.txt" }
            device.waitForIdle()
            shot("second-window")
            var tabs = 0
            instrumentation.runOnMainSync { tabs = second!!.workspace.docs.size }
            assertEquals("the second window has just that file", 1, tabs)
            instrumentation.runOnMainSync { second!!.finish() }
            device.waitForIdle()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        assertEquals("the file is still open in the first window", "window.txt", active())
        onUi { it.editor.send(Sci.SCI_SETSAVEPOINT) }
    }

    @Test
    fun textDirection() {
        // Where the dark pixels of the first lines are on average, as a fraction of the editor's width.
        fun inkCentre(): Double {
            device.waitForIdle()
            SystemClock.sleep(300)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()!!.copy(Bitmap.Config.ARGB_8888, false)
            val bounds = editorBounds()
            val bottom = minOf(bounds.top + 3 * sci(Sci.SCI_TEXTHEIGHT).toInt(), bitmap.height)
            var sum = 0.0
            var count = 0
            for (y in bounds.top until bottom) {
                for (x in bounds.left until minOf(bounds.right, bitmap.width)) {
                    val pixel = bitmap.getPixel(x, y)
                    val light = 0.299 * ((pixel shr 16) and 0xFF) + 0.587 * ((pixel shr 8) and 0xFF) + 0.114 * (pixel and 0xFF)
                    if (light < 90) {
                        sum += x - bounds.left
                        count++
                    }
                }
            }
            return if (count == 0) -1.0 else sum / count / bounds.width()
        }
        val first = "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD\n"
        setText(first + "hello world\n")
        // Measured until it holds: the scroll thumb, which shows for a moment after a scroll, counts as ink too.
        var measured = -1.0
        waitUntil("the text to be at the left when written from the left", 10000) {
            measured = inkCentre()
            measured in 0.0..0.45
        }

        openMenu("View", "Text Direction RTL")
        tapDescribed("Close menu")
        assertTrue(onUi { it.editor.rightToLeft })
        waitUntil("the text to be at the right when written from the right", 10000) {
            measured = inkCentre()
            measured > 0.55
        }
        shot("text-direction-rtl")

        // A touch still lands on the character under the finger.
        val target = first.toByteArray().size + 6L
        val bounds = editorBounds()
        val x = bounds.right - (sci(Sci.SCI_POINTXFROMPOSITION, 0, target) + 4)
        val y = bounds.top + sci(Sci.SCI_POINTYFROMPOSITION, 0, target) + sci(Sci.SCI_TEXTHEIGHT) / 2
        tapAt(x.toFloat(), y.toFloat())
        waitUntil("the caret to go where the mirrored text was touched") { sci(Sci.SCI_GETCURRENTPOS) in (target - 1)..(target + 1) }

        // The direction is remembered, and goes back with the other command.
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        device.waitForIdle()
        assertTrue("right to left after a restart", onUi { it.editor.rightToLeft })
        openMenu("View", "Text Direction LTR")
        tapDescribed("Close menu")
        assertTrue(!onUi { it.editor.rightToLeft })
        shot("text-direction-ltr-again")
        waitUntil("the text to be back at the left", 10000) {
            measured = inkCentre()
            measured in 0.0..0.45
        }
        onUi { it.editor.send(Sci.SCI_SETSAVEPOINT) }
    }

    /** Screens for the README: a realistic file in a few looks, without the keyboard covering half of it. */
    @Test
    fun readmeScreens() {
        val python = """
            # Inventory report for the corner shop.
            import csv
            from dataclasses import dataclass

            LOW_STOCK = 5


            @dataclass
            class Item:
                name: str
                price: float
                count: int

                @property
                def value(self) -> float:
                    return self.price * self.count


            def load(path: str) -> list[Item]:
                with open(path, newline="") as f:
                    rows = csv.DictReader(f)
                    return [Item(r["name"],
                                 float(r["price"]),
                                 int(r["count"]))
                            for r in rows]


            def report(items: list[Item]) -> None:
                for item in sorted(items, key=str):
                    low = item.count < LOW_STOCK
                    mark = "!" if low else " "
                    print(mark, item.name, item.value)
                # TODO: totals per category


            if __name__ == "__main__":
                report(load("stock.csv"))
        """.trimIndent() + "\n"
        val files = listOf(
            "notes.md" to "# Shop notes\n\n- order **flour** on Monday\n- call the *baker*\n",
            "style.css" to "body {\n  margin: 0;\n  color: #202020;\n}\n",
            "inventory.py" to python,
        )
        for ((name, content) in files) {
            val file = File(context.cacheDir, name).apply { writeText(content) }
            onUi { it.openUris(listOf(Uri.fromFile(file))) }
            waitUntil("$name to open") { onUi { it.workspace.active.name } == name }
        }
        fun hideKeyboard() {
            onUi { activity ->
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(activity.editor.windowToken, 0)
            }
            SystemClock.sleep(900)
            sci(Sci.SCI_COLOURISE, 0, -1)
        }
        hideKeyboard()
        shot("readme-light")

        // A column selection, with the keys row showing Alt locked.
        tap("Alt")
        tap("Alt")
        sci(Sci.SCI_SETRECTANGULARSELECTIONANCHOR, sci(Sci.SCI_FINDCOLUMN, 9, 4))
        sci(Sci.SCI_SETRECTANGULARSELECTIONCARET, sci(Sci.SCI_FINDCOLUMN, 11, 9))
        shot("readme-column")
        tap("Alt")
        sci(Sci.SCI_GOTOPOS, 0)

        tapDescribed("Menu")
        menu("Edit")
        menu("Line Operations")
        shot("readme-menu")
        tapDescribed("Close menu")

        tap("Find")
        device.findObjects(By.clazz("android.widget.EditText"))[0].text = "item"
        tapDescribed("Find next")
        tapDescribed("Count matches")
        shot("readme-find")
        tapDescribed("Close")

        openMenu("Settings", "Dark mode")
        waitUntil("dark mode") { brightness(sci(Sci.SCI_STYLEGETBACK, Sci.STYLE_DEFAULT.toLong())) < 128 }
        tapDescribed("Close menu")
        hideKeyboard()
        sci(Sci.SCI_GOTOPOS, 0)
        shot("readme-dark")

        openMenu("Settings", "Theme", "Monokai")
        tapDescribed("Close menu")
        sci(Sci.SCI_FOLDLINE, 19, Sci.SC_FOLDACTION_CONTRACT.toLong())
        hideKeyboard()
        shot("readme-monokai")
    }

    @Test
    fun comparePlugin() {
        fun differences() = onUi { it.comparisonDifferences }
        fun line() = sci(Sci.SCI_LINEFROMPOSITION, sci(Sci.SCI_GETCURRENTPOS))
        fun marked(view: ScintillaView, line: Long, marker: Int) = view.send(Sci.SCI_MARKERGET, line) and (1L shl marker) != 0L
        fun result() = device.wait(Until.findObject(By.desc("Compare result")), 4000)!!.text
        val older = "one\ntwo\nthree\nfour\nfive\n"
        setText(older)
        tap("New")
        setText("one\n2\ntwo and a half\nthree\nfive\nsix\nseven\n")

        // Compare: this document above as the new text, its neighbour below as the old one.
        openMenu("Plugins", "Compare", "Compare")
        waitUntil("the comparison to finish") { differences() == 3 }
        assertEquals("new 1", onUi { it.otherDoc?.name })
        assertTrue("3 differences", result().startsWith("3 differences"))
        onUi { activity ->
            val other = activity.otherView!!
            assertTrue("a line only in the new text is marked as added", marked(activity.editor, 1, 10))
            assertTrue("a line that differs in part is marked as changed", marked(activity.editor, 2, 12))
            assertTrue("and so is its counterpart", marked(other, 1, 12))
            assertTrue("a line only in the old text is marked as removed", marked(other, 3, 11))
            // Markers 10 to 12 tint the lines; 13 only says that a blank row follows.
            assertTrue("lines that are the same are not tinted", activity.editor.send(Sci.SCI_MARKERGET, 3) and 0x1C00L == 0L)
            assertEquals("a blank row stands in above for the line only below", 1L, activity.editor.send(Sci.SCI_ANNOTATIONGETLINES, 3))
            assertEquals("and below for the two lines only above", 2L, other.send(Sci.SCI_ANNOTATIONGETLINES, 4))
        }
        assertEquals("the view goes to the first difference", 1L, line())
        assertTrue("added lines are tinted green", countPixels(0xD7F5CF, 14) > 5000)
        assertTrue("the changed line is tinted yellow", countPixels(0xFFF1B8, 14) > 2000)
        shot("compare")

        // The marks stay through a change of theme, in colours for the dark.
        openMenu("Settings", "Dark mode")
        waitUntil("the screen to turn dark") { brightness(sci(Sci.SCI_STYLEGETBACK, Sci.STYLE_DEFAULT.toLong())) < 128 }
        tapDescribed("Close menu")
        assertEquals(3, differences())
        assertTrue(onUi { marked(it.editor, 1, 10) && marked(it.otherView!!, 3, 11) })
        shot("compare-dark")
        openMenu("Settings", "Dark mode")
        waitUntil("the screen to turn light") { brightness(sci(Sci.SCI_STYLEGETBACK, Sci.STYLE_DEFAULT.toLong())) > 128 }
        tapDescribed("Close menu")

        // From one difference to the next and back.
        tapDescribed("Next difference")
        assertEquals(3L, line())
        tapDescribed("Next difference")
        assertEquals(5L, line())
        tapDescribed("Previous difference")
        assertEquals(3L, line())
        tapDescribed("First difference")
        assertEquals(1L, line())
        tapDescribed("Last difference")
        assertEquals(5L, line())

        // The options compare again at once.
        setText("ONE\nTwo\nthree\nfour\nfive\n")
        waitUntil("the comparison to follow the typing") { differences() == 1 }
        openMenu("Plugins", "Compare", "Ignore Case")
        waitUntil("capitals to stop counting") { differences() == 0 }
        tapDescribed("Close menu")
        assertTrue(result().startsWith("No differences"))
        openMenu("Plugins", "Compare", "Ignore Case")
        tapDescribed("Close menu")
        waitUntil("capitals to count again") { differences() == 1 }

        // Clearing takes the marks and the second view away.
        tapDescribed("Clear compare")
        assertEquals(-1, differences())
        assertNull(onUi { it.otherView })
        assertEquals("no marks are left", -1L, sci(Sci.SCI_MARKERNEXT, 0, 0x3C00))
        assertEquals("no blank rows are left", 0L, sci(Sci.SCI_ANNOTATIONGETLINES, 0))

        // Compare to Clipboard: the clipboard's text in a tab that lasts as long as the comparison.
        setText(older)
        onUi { it.editor.setClipboard("one\ntwo\n") }
        openMenu("Plugins", "Compare", "Compare to Clipboard")
        waitUntil("the comparison with the clipboard") { differences() == 1 }
        assertEquals("Clipboard", onUi { it.otherDoc?.name })
        assertEquals(3, onUi { it.workspace.docs.size })
        shot("compare-clipboard")
        openMenu("Plugins", "Compare", "Clear Active Compare")
        assertEquals(-1, differences())
        assertEquals("the clipboard's tab goes with the comparison", listOf("new 1", "new 2"), onUi { it.workspace.docs.map { doc -> doc.name } })

        // Diff since Last Save: the text against the file it came from.
        val file = File(context.cacheDir, "saved.txt").apply { writeText("alpha\nbeta\n") }
        try {
            onUi { it.openUris(listOf(Uri.fromFile(file))) }
            waitUntil("the file to open") { onUi { it.workspace.active.name } == "saved.txt" }
            sci(Sci.SCI_DOCUMENTEND)
            onUi { it.editor.typeText("gamma") }
            openMenu("Plugins", "Compare", "Diff since Last Save")
            waitUntil("the comparison with the saved file") { differences() == 1 }
            assertEquals("saved.txt (saved)", onUi { it.otherDoc?.name })
            assertEquals("alpha\nbeta\n", onUi { it.otherView!!.text })
            assertEquals(2L, line())
            shot("compare-saved")
            tapDescribed("Clear compare")
            assertNull(onUi { it.otherView })
        } finally {
            file.delete()
        }
    }

    @Test
    fun hexEditor() {
        fun shown() = onUi { it.hexViewShown }
        fun bytes() = onUi { it.hexView.buffer.toByteArray().toList() }
        fun cursor() = onUi { it.hexView.cursor }
        fun status() = device.wait(Until.findObject(By.desc("Hex status")), 4000)!!.text
        fun tapByte(offset: Int, text: Boolean = false) {
            val (x, y) = onUi { activity ->
                val at = IntArray(2)
                activity.hexView.getLocationOnScreen(at)
                val (dx, dy) = activity.hexView.pointOf(offset, text)
                (at[0] + dx) to (at[1] + dy)
            }
            tapAt(x, y)
            device.waitForIdle()
        }
        fun answer(description: String, value: String) {
            device.wait(Until.findObject(By.desc(description)), 4000)!!.text = value
            tapButton("OK")
        }
        // Bytes no text encoding would bring back whole: a zero, 0xFF, a lone 0x80.
        val original = byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte(), 0x50, 0x4B, 0x0D, 0x0A, 0xC3.toByte()) + ByteArray(592) { (it * 7).toByte() }
        "END".toByteArray().copyInto(original, 0x200)
        val file = File(context.cacheDir, "blob.bin").apply { writeBytes(original) }
        try {
            onUi { it.openUris(listOf(Uri.fromFile(file))) }
            waitUntil("the file to open") { onUi { it.workspace.active.name } == "blob.bin" }

            // View in HEX shows a saved file's own bytes.
            openMenu("Plugins", "Hex Editor", "View in HEX")
            waitUntil("the bytes to show") { shown() }
            tapDescribed("Close menu")
            assertEquals("the bytes are the file's, one for one", original.toList(), bytes())
            assertTrue(status().contains("600 bytes"))
            shot("hex-view")

            // Typing two hex digits over a byte.
            tapByte(0)
            assertEquals(0, cursor())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_4)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_A)
            waitUntil("the typed byte") { bytes()[0] == 0x4A.toByte() && cursor() == 1 }
            assertTrue("the status says so", status().contains("modified"))
            assertTrue("and so does the tab", onUi { it.hexView.buffer.modified })

            // Typing a character in the text column.
            tapByte(4, text = true)
            assertEquals(4, cursor())
            assertTrue(onUi { it.hexView.inText })
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_Z)
            waitUntil("the typed character") { bytes()[4] == 'z'.code.toByte() && cursor() == 5 }

            // Insert, delete, and Undo for each of them.
            tapDescribed("Insert byte")
            assertEquals(601, bytes().size)
            assertEquals(0.toByte(), bytes()[5])
            assertEquals(original[5], bytes()[6])
            tapDescribed("Delete byte")
            assertEquals(600, bytes().size)
            tap("Undo")
            assertEquals(601, bytes().size)
            tap("Undo")
            assertEquals(600, bytes().size)
            tap("Undo")
            assertEquals(original[4], bytes()[4])
            tap("Redo")
            assertEquals('z'.code.toByte(), bytes()[4])

            // Go to an offset; find bytes, and a text.
            tapDescribed("Go to offset")
            answer("Offset", "0x200")
            assertEquals(0x200, cursor())
            tap("Find")
            answer("Bytes to find", "FF 80")
            assertEquals(1, cursor())
            tap("Find")
            answer("Bytes to find", "\"END\"")
            assertEquals(0x200, cursor())
            waitUntil("the byte found to be on screen") { onUi { it.hexView.pointOf(0x200).second.let { y -> y > 0 && y < it.hexView.height } } }
            shot("hex-found")

            // Bytes per Row.
            openMenu("Plugins", "Hex Editor", "Bytes per Row", "4")
            tapDescribed("Close menu")
            assertEquals(4, onUi { it.hexView.bytesPerRow })
            openMenu("Plugins", "Hex Editor", "Bytes per Row", "Fit Width")
            tapDescribed("Close menu")

            // Save writes exactly these bytes.
            tap("Save")
            waitUntil("the save to finish") { !onUi { it.hexView.buffer.modified } }
            val expected = original.copyOf().also {
                it[0] = 0x4A
                it[4] = 'z'.code.toByte()
            }
            assertEquals("the file holds the bytes as edited, the odd ones untouched", expected.toList(), file.readBytes().toList())

            // Another tab shows its text; coming back, the bytes and the place are as they were.
            tap("New")
            assertTrue("a text tab shows the editor", !shown())
            onUi { activity -> activity.workspace.activate(activity.workspace.docs.first { it.name == "blob.bin" }) }
            waitUntil("the bytes to show again") { shown() }
            assertEquals(0x200, cursor())

            // Leaving with changed bytes asks what to do with them.
            onUi { it.hexView.setCursor(0, false) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_0)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_0)
            waitUntil("the typed byte") { bytes()[0] == 0.toByte() }
            tapDescribed("Leave HEX view")
            tapButton("No")
            waitUntil("the text to show") { !shown() }
            assertEquals("nothing was written", expected.toList(), file.readBytes().toList())

            // A document that is not a saved file: its text as bytes, and the bytes typed go back into the text.
            onUi { activity -> activity.workspace.activate(activity.workspace.docs.first { it.uri == null }) }
            setText("AB")
            onUi { it.setHexView(true) }
            assertTrue(shown())
            assertEquals(listOf<Byte>(0x41, 0x42), bytes())
            onUi { it.hexView.setCursor(0, false) }
            onUi { it.hexView.type("43") }
            shot("hex-text")
            tapDescribed("Leave HEX view")
            waitUntil("the text to show") { !shown() }
            assertEquals("CB", text())
            sci(Sci.SCI_UNDO)
            assertEquals("AB", text())
        } finally {
            file.delete()
        }
    }

    @Test
    fun luaScripting() {
        fun console() = onUi { it.luaConsoleText }
        fun type(code: String) {
            device.wait(Until.findObject(By.desc("Lua input")), 4000)!!.text = code
            tapDescribed("Run Lua")
        }
        fun press(key: Int, meta: Int) {
            val now = SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, meta))
            instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, meta))
        }
        val startup = onUi { it.startupScript() }
        startup.delete()
        val file = File(context.cacheDir, "spaces.txt").apply { writeText("a  \nb\t\nc\n") }
        try {
            // The console: values, the editor, the app, and mistakes.
            setText("hello")
            openMenu("Plugins", "Lua Script", "Show Console")
            tapDescribed("Close menu")
            assertTrue(onUi { it.luaConsoleShown })
            type("1 + 2")
            waitUntil("the console to answer") { console().endsWith("> 1 + 2\n3\n") }
            type("editor:AppendText(' world')")
            waitUntil("the script to change the text") { text() == "hello world" }
            type("editor.Length")
            waitUntil("a property of the editor") { console().endsWith("\n11\n") }
            type("npp:GetFileName()")
            waitUntil("a question to the app") { console().endsWith("\nnew 1\n") }
            type("editor:NoSuchThing()")
            waitUntil("a mistake to be named") { console().contains("no function or property called 'NoSuchThing'") }
            shot("lua-console")

            // A Notepad++ menu command by its id.
            setText("one\ntwo\nthree")
            sci(Sci.SCI_SELECTALL)
            type("npp:MenuCommand(IDM_EDIT_JOIN_LINES)")
            waitUntil("the menu command to run") { text() == "one two three" }
            // Which of Notepad++'s commands have no counterpart, for the record.
            val unmatched = onUi { it.unknownNppCommands() }
            for (path in unmatched) Log.i(TAG, "npp command without a match: $path")
            Log.i(TAG, "npp commands without a match: ${unmatched.size}")

            // Execute Current File: a script that adds a command and tidies files before they are saved.
            tap("New")
            setText(
                "npp.AddShortcut('Shout', 'Ctrl+Alt+U', function() editor:SelectAll() editor:UpperCase() end)\n" +
                    "npp.AddEventHandler('OnBeforeSave', function(name) for m in editor:match('[ \\t]+$', SCFIND_REGEXP) do m:replace('') end end)\n" +
                    "npp.AddEventHandler('OnSwitchFile', function(name, id) print('now at ' .. name) end)\n" +
                    "print('script loaded')\n",
            )
            openMenu("Plugins", "Lua Script", "Execute Current File")
            waitUntil("the script to run") { console().contains("script loaded") }

            // Its command is in the menu and has its shortcut.
            onUi { activity -> activity.workspace.activate(activity.workspace.docs.first()) }
            waitUntil("the script to hear of the other tab") { console().endsWith("now at new 1\n") }
            setText("quiet")
            openMenu("Plugins", "Lua Script")
            assertTrue("the shortcut shows beside the command", onScreen("Ctrl+Alt+U"))
            shot("lua-menu")
            menu("Shout")
            waitUntil("the script's command to run") { text() == "QUIET" }
            setText("again")
            onUi { it.editor.requestFocus() }
            press(KeyEvent.KEYCODE_U, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
            waitUntil("the script's shortcut to run it") { text() == "AGAIN" }

            // Its handler runs before a file is saved.
            onUi { it.openUris(listOf(Uri.fromFile(file))) }
            waitUntil("the file to open") { onUi { it.workspace.active.name } == "spaces.txt" }
            tap("Save")
            waitUntil("the saved file to be tidied") { file.readText() == "a\nb\nc\n" }
            assertEquals("a\nb\nc\n", text())

            // A script that never ends is stopped: after five seconds, or sooner here.
            onUi { it.luaTimeLimit(1500) }
            type("while true do end")
            waitUntil("the endless loop to be stopped", 15000) { console().contains("was stopped") }
            type("'still ' .. 'here'")
            waitUntil("Lua to go on working") { console().endsWith("\nstill here\n") }

            // The startup script: saved, it takes effect, and what earlier scripts set up is gone.
            openMenu("Plugins", "Lua Script", "Edit Startup Script")
            waitUntil("the startup script to open") { onUi { it.workspace.active.name } == "startup.lua" }
            assertTrue("it starts with examples", text().contains("npp.AddShortcut"))
            assertEquals("Lua", onUi { it.workspace.active.language.display })
            onUi { it.editor.text = "npp.AddShortcut('From Startup', '', function() editor:AddText('S') end)\nprint('started')\n" }
            tap("Save")
            waitUntil("the startup script to run") { console().contains("started") }
            assertNull("the earlier script's command is gone", onUi { it.commandFor("Plugins > Lua Script > Shout") })
            shot("lua-startup")

            // It runs when the app starts.
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            device.waitForIdle()
            waitUntil("the startup script to run at start-up") { console().contains("started") }
            tap("New")
            openMenu("Plugins", "Lua Script", "From Startup")
            waitUntil("the startup script's command to run") { text() == "S" }
        } finally {
            startup.delete()
            file.delete()
        }
    }

    private companion object {
        const val TAG = "PocketpadTest"
        var shots = 0
    }
}
