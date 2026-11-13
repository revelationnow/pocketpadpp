# Scintilla on Android: the spike

## Why a spike

Notepad++ is a shell around Scintilla. Column mode, multi-caret editing,
folding, markers, macro recording and every lexer come from it, so porting
Scintilla brings most of Notepad++'s editing behaviour for free. Scintilla has
no Android port, though, and the two hard parts on a phone are the soft
keyboard and touch. The spike exists to find out whether those can be made to
feel right before anything else is built on top.

Fallback if they cannot: build on the sora-editor widget instead and
reimplement the Notepad++ features one by one.

## What exists

- Scintilla renders through an Android `Canvas` (fonts, colours, margins, fold
  markers, indentation guides, selections, wrap).
- Lexilla is built in, with all of Notepad++'s lexers including user-defined
  languages.
- Soft keyboard: committed text, composing text (suggestions, autocorrect,
  swipe), composing regions, surrounding-text deletion, selection changes and
  key events, with UTF-16 offsets mapped onto Scintilla's UTF-8 positions.
  Composing edits are applied as minimal changes, so a word typed through the
  keyboard is one undo step and reaches every caret.
- Touch: tap to place the caret, double tap for a word, triple tap for a line,
  long press and drag to select, draggable selection and caret handles with
  edge auto-scroll, floating cut / copy / paste / select all, fold margin taps.
- Pixel-smooth vertical scrolling and flinging on top of Scintilla's
  line-based scrolling, horizontal scrolling when wrap is off.
- Hardware keyboard: navigation keys, Ctrl shortcuts from Scintilla's key map.

## What to test on a phone

Use your everyday keyboard. `More > Show keyboard log` prints what the
keyboard asks the editor to do, which is the most useful thing to report when
something misbehaves.

1. **Typing, code mode** (`Kbd: code`): letters, symbols, Enter, Backspace
   held down, emoji. Nothing should be autocorrected.
2. **Typing, text mode** (`Kbd: text`): suggestions, autocorrect, swipe typing,
   tapping a suggestion, tapping into the middle of a word and continuing.
3. **Undo / redo** after each of the above.
4. **Caret and selection**: tap between two characters, drag the caret handle,
   double tap, long press and drag, pull both selection handles, cross them,
   drag to the top and bottom edge.
5. **Cut, copy, paste** from the floating toolbar and from the keyboard's
   clipboard.
6. **Scrolling**: slow drag, fling, fling then touch to stop, sideways with
   wrap off. Then `More > Load 100,000 lines` and repeat.
7. **Keyboard opening and closing**: the caret should stay visible.
8. **Folding**: tap the boxes in the left margin.
9. **Multi-caret**: `More > Add caret below` a few times, then type.
10. **Rotation** and, if available, a Bluetooth or USB keyboard.

## Known gaps

- Composing text goes to the main caret only when several carets exist
  (committed text reaches all of them). Code mode is not affected.
- No fast-scroll thumb, pinch zoom or magnifier yet.
- Right-to-left and bidirectional text are not laid out correctly.
- Autocompletion lists and call tips are stubbed out.
- The document lives only in memory: no file open or save, and text is lost
  if Android kills the process.
- Documents are UTF-8 in memory; other encodings will be converted on load
  and save by the application, which does not exist yet.
- No TalkBack / accessibility support.
- 64-bit ABIs only.

## How the native layer is tested

`hosttest/run.sh` builds the same C++ for the desktop and drives it through
the same JNI interface as the app, with Java2D implementing the font and
surface interfaces. It covers rendering, typing, composing, UTF-16 offsets
(emoji, CRLF), multi-caret input, taps, clipboard, folding, wrapping and a
14 MB document. It cannot cover the Kotlin view or real keyboards; that is
what the checklist above is for.
