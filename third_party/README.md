# Third-party sources

| Directory | What | Version | Licence |
|---|---|---|---|
| `scintilla/` | Scintilla editing component (`include/`, `src/`) | 5.6.9 | Scintilla licence (`scintilla/License.txt`) |
| `lexilla/` | Lexilla lexer library (`include/`, `lexlib/`, `lexers/`, `src/Lexilla.cxx`) | 5.5.5 | Lexilla licence (`lexilla/License.txt`) |

Both are copied unmodified from the Notepad++ repository
(https://github.com/notepad-plus-plus/notepad-plus-plus, commit `27f8ea3385a32a17d417d55f298f83c9b96c6b92`),
so they include Notepad++'s own additions: the user-defined-language lexer (`LexUser.cxx`),
the search-result lexer and the Objective-C lexer, which are GPL-2.0-or-later.

Platform directories (`win32/`, `gtk/`, `qt/`, `cocoa/`), documentation and tests are left out.
The Android platform layer lives in `app/src/main/cpp/`.

To update: copy the same directories from a newer Notepad++ checkout, then run
`python3 tools/gen_constants.py` and `hosttest/run.sh`.
