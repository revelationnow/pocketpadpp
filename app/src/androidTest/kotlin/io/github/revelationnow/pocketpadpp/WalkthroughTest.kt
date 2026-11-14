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
import io.github.revelationnow.pocketpadpp.core.Marks
import io.github.revelationnow.pocketpadpp.core.SearchOptions
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

    private companion object {
        const val TAG = "PocketpadTest"
        var shots = 0
    }
}
