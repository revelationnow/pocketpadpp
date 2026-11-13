# Testing on a device

Scintilla has no Android port, so Pocketpad++ carries its own platform layer. The native side and the
application logic are covered by desktop tests (`hosttest/run.sh`, about 200 checks). What those cannot
cover is the Android view itself: real keyboards, touch, the file picker. That is what this checklist is for.

`Menu > Settings > Keyboard log` prints what the keyboard asks the editor to do, which is the most useful
thing to include when reporting that typing misbehaves.

## The editor

1. **Typing with suggestions off** (the default): letters, symbols, Enter, Backspace held down, emoji.
   Nothing should be autocorrected. A new line should keep the indentation of the line above.
2. **Typing with suggestions on** (`Settings > Keyboard suggestions and autocorrect`): suggestions,
   autocorrect, swipe typing, tapping a suggestion, tapping into the middle of a word and continuing.
3. **Undo / redo** after each of the above.
4. **Caret and selection**: tap between two characters, drag the caret handle, double tap, long press and
   drag, pull both selection handles, cross them, drag to the top and bottom edge.
5. **Cut, copy, paste** from the floating toolbar and from the keyboard's clipboard.
6. **Scrolling**: slow drag, fling, fling then touch to stop, sideways with word wrap off. The view must
   stay where you leave it, also when the caret is off screen.
7. **Keyboard opening and closing**: the caret should stay visible.
8. **Folding**: tap the boxes in the left margin.
9. **Rotation** and, if available, a Bluetooth or USB keyboard (arrows, Ctrl+Z/X/C/V/A).

## The application

10. **Open** a few files of different types at once; check the language shown in the status bar and the
    colours against Notepad++.
11. **Tabs**: switch, close with the cross, long press for the tab menu. Caret position and scroll
    position should come back when you return to a tab. Edit a file: its tab turns red with a dot.
12. **Save, Save As, Save a Copy As**, then reopen the file in another app to check the content.
13. **Close a modified tab**: Yes / No / Cancel.
14. **Session**: with a saved file, an unsaved "new" tab with text and a modified file open, swipe the
    app away and start it again. All three should come back, with the unsaved text.
15. **Open with**: pick Pocketpad++ for a text file from a file manager.
16. **Encoding**: open a UTF-16 or Windows-1252 file; the status bar should say so and saving should
    keep it. Change the encoding from the status bar and save.
17. **Line endings**: tap LF/CRLF in the status bar and convert; `View > Show Symbol > Show End of Line`.
18. **Find and replace**: next, previous, wrap, match case, whole word, a regular expression with a
    group (`(\w+)@(\w+)` to `$2 at $1`), Replace, Replace All, then a single Undo.
19. **Edit menu**: comment toggle, sort, duplicate, move line, trim trailing space, case conversion.
20. **Go to line**, **bookmarks**, **zoom**, **word wrap**, **Summary**.

## Known gaps

Editor:
- Composing text goes to the main caret only when several carets exist (committed text reaches all of
  them). With suggestions off this does not apply.
- No fast-scroll thumb, pinch zoom or magnifier yet.
- Right-to-left and bidirectional text are not laid out correctly.
- Autocompletion lists and call tips are stubbed out in the platform layer.
- No TalkBack / accessibility support.
- 64-bit ABIs only.

Application:
- Regular expressions use ECMAScript syntax, not Notepad++'s Boost syntax (no lookbehind).
- Folds are forgotten when switching tabs.
- No warning when a file changes on disk behind the editor's back.
- Files larger than 64 MB are refused; loading is not incremental.
- "ANSI" always means Windows-1252.
- A file opened through "Open with" may not reopen in the next session, because Android only lends
  that permission for a while. Files opened with Open do reopen.
- Light theme only; no preferences screen yet.

See [FEATURES.md](FEATURES.md) for every Notepad++ command and its status.
