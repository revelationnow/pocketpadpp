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
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.github.revelationnow.pocketpadpp.editor.ScintillaView
import io.github.revelationnow.pocketpadpp.scintilla.Sci
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
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
        val replaceFields = device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals("replace mode shows two fields", 2, replaceFields.size)
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
        // (The pauses let the keyboard finish reconnecting, which would otherwise end the composition.)
        fun connection() = onUi { it.editor.onCreateInputConnection(EditorInfo())!! }
        setText("hello ")
        sci(Sci.SCI_GOTOPOS, 6)
        onUi { it.editor.setClipboard("PASTE") }
        SystemClock.sleep(700)
        val composing = connection()
        onUi {
            composing.setComposingText("w", 1)
            composing.setComposingText("wo", 1)
        }
        tap("Ctrl")
        onUi { composing.setComposingText("wov", 1) }
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

        // A hardware keyboard goes through the same table.
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_G, 0, meta))
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_G, 0, meta))
        assertTrue("Ctrl+G on a keyboard opens Go to", onScreen("Go to line"))
        tapButton("Cancel")
    }

    private companion object {
        const val TAG = "PocketpadTest"
        var shots = 0
    }
}
