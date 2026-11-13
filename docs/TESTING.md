# Testing

Scintilla has no Android port, so Pocketpad++ carries its own platform layer. Three sets of tests cover it;
all of them run on every push (see `.github/workflows/android.yml`).

| Tests | Where they run | What they cover |
|---|---|---|
| Native layer (`hosttest/run.sh`) | Desktop JVM | The C++ platform layer: drawing calls, keyboard composition, undo grouping, big files |
| Core (`hosttest/kotlin/CoreTests.kt`) | Desktop JVM | Encodings, line endings, Notepad++ language and theme import, search, every edit command, sessions |
| Walkthrough (`app/src/androidTest`) | Android emulator | The real app, driven like a person would: see below |

## The emulator walkthrough

`WalkthroughTest` starts the app on an emulator, taps through it, checks what the editor ends up holding
and saves a screenshot at every step. It covers:

- typing through the keyboard connection (commit, composing, delete, emoji), hardware keys, undo and redo
- every top-level menu, going into submenus and back with the arrow and with the Back key
- the Show Symbol, word wrap, zoom, fold, line number, text size and read-only settings, and that they
  survive a restart
- themes and dark mode, including that unsaved text, the undo history and the open menu survive the switch
- tabs: new, switch, move, close, the tab's own menu, the save prompt, session restore
- files: opening, encodings and line endings kept on save, converting both
- find, count, replace all with a regular expression, single undo
- touch: drag scrolling, fling, tap, double tap, long press, selection handles, pinch zoom, the scroll thumb
- the on-screen Ctrl, Alt and Shift keys: one-shot and locked, Notepad++'s shortcuts, Alt + letter for menus,
  shortcuts while a suggestion keyboard is composing a word or typing over a selection, and a shortcut
  from a hardware keyboard
- column selection by dragging with Alt held and with Alt+Shift+arrows, and the Column Editor with text and numbers
- highlighting for C++, Python, HTML and JSON

To run it yourself, start an emulator or plug in a phone with USB debugging and run `tools/run_ui_tests.sh`.
Screenshots, the test report and the log end up in `ui-test-output/`. CI uploads the same folder as the
`ui-test-output` artifact and as the rolling `ui-test-latest` pre-release.

## What still needs a person and a phone

The emulator cannot judge these, so they are worth trying by hand on real devices:

1. **Real keyboards** (Gboard, Samsung, SwiftKey): suggestions on and off, autocorrect, swipe typing,
   tapping into the middle of a word and continuing. `Menu > Settings > Keyboard log` prints what the
   keyboard asks the editor to do, which is the most useful thing to include in a report.
2. **How it feels**: scrolling and flinging a large file, dragging handles, pinch zoom.
3. **The system file picker**: Open, Save As, Save a Copy As, then reopening the file in another app.
4. **Open with**: picking Pocketpad++ for a text file from a file manager.
5. **Rotation** and, if available, a Bluetooth or USB keyboard (arrows, Ctrl+Z/X/C/V/A).

## Known gaps

Editor:
- Composing text goes to the main caret only when several carets exist (committed text reaches all of
  them). With suggestions off this does not apply.
- No magnifier while dragging the caret.
- Right-to-left and bidirectional text are not laid out correctly.
- Autocompletion lists and call tips are stubbed out in the platform layer.
- No TalkBack / accessibility support beyond labelled buttons.
- 64-bit ABIs only.

Application:
- Regular expressions use ECMAScript syntax, not Notepad++'s Boost syntax (no lookbehind).
- Folds are kept per tab while the app runs, but not yet across restarts.
- No warning when a file changes on disk behind the editor's back.
- Files larger than 64 MB are refused; loading is not incremental.
- "ANSI" always means Windows-1252.
- A file opened through "Open with" may not reopen in the next session, because Android only lends
  that permission for a while. Files opened with Open do reopen.
- Themes are chosen as a whole; there is no style configurator or preferences screen yet.

See [FEATURES.md](FEATURES.md) for every Notepad++ command and its status.
