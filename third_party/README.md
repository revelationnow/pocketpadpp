# Third-party sources

| Directory | What | Version | Licence |
|---|---|---|---|
| `scintilla/` | Scintilla editing component (`include/`, `src/`) | 5.6.9 | Scintilla licence (`scintilla/License.txt`) |
| `lexilla/` | Lexilla lexer library (`include/`, `lexlib/`, `lexers/`, `src/Lexilla.cxx`) | 5.5.5 | Lexilla licence (`lexilla/License.txt`) |

| `lua/src/` | The Lua interpreter and its standard libraries (without the stand-alone `lua.c`) | 5.4.8 | MIT licence (at the end of `lua/src/lua.h`) |

Lua is copied unmodified from https://github.com/lua/lua, tag `v5.4.8`
(commit `6e22fedb74cf0c9b6656e9fce8b7331db847c605`). It is built as C++, see `app/src/main/cpp/CMakeLists.txt`.

Scintilla and Lexilla are copied unmodified from the Notepad++ repository
(https://github.com/notepad-plus-plus/notepad-plus-plus, commit `27f8ea3385a32a17d417d55f298f83c9b96c6b92`),
so they include Notepad++'s own additions: the user-defined-language lexer (`LexUser.cxx`),
the search-result lexer and the Objective-C lexer, which are GPL-2.0-or-later.

Platform directories (`win32/`, `gtk/`, `qt/`, `cocoa/`), documentation and tests are left out.
The Android platform layer lives in `app/src/main/cpp/`.

To update: copy the same directories from a newer Notepad++ checkout, then run
`python3 tools/gen_constants.py` and `hosttest/run.sh`.

## Notepad++ data files

`app/src/main/assets/npp/langs.model.xml` and `stylers.model.xml` are Notepad++'s language
definitions (extensions, keywords, comment markers) and default colour theme, copied unmodified
from the same commit and licensed under the GPL version 3 like the rest of Notepad++.
`languages.tsv` beside them is generated from Notepad++'s source code by
`tools/gen_npp_tables.py`, which also refreshes the two XML files.
