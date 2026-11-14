# Lua scripting

Pocketpad++ has a Lua 5.4 interpreter built in, with the objects Notepad++'s
[LuaScript](https://github.com/dail8859/LuaScript) plugin gives its scripts. A script can do anything to
the text that Scintilla can, add commands with shortcuts to the menu, and react when files are opened,
switched or saved. Scripts written for LuaScript mostly run as they are; what differs is listed at the end.

Everything is under **Plugins > Lua Script**:

| Command | What it does |
|---|---|
| Show Console | A panel under the editor: what scripts print, their errors in red, and a line to type Lua into |
| Execute Current File | Runs the document on screen as a script |
| Edit Startup Script | Opens `startup.lua`, which runs when the app starts and again each time you save it |
| Restart Lua | Starts the interpreter afresh: commands, handlers and timers of earlier scripts are gone, then the startup script runs |
| (your commands) | Whatever scripts added with `npp.AddShortcut` |

## A first script

Open the console and type a line; an expression shows its value:

```lua
editor.Length                      -- the size of the document
editor:AppendText("\nfin")         -- adds a line at the end
npp:GetFileName()                  -- the name of the tab
```

A command for the menu, with a shortcut, goes in the startup script:

```lua
npp.AddShortcut("Insert Date", "Ctrl+Alt+D", function()
    editor:ReplaceSel(os.date("%Y-%m-%d"))
end)

-- Take the spaces off line ends whenever a file is saved.
npp.AddEventHandler("OnBeforeSave", function(filename)
    for m in editor:match("[ \t]+$", SCFIND_REGEXP) do m:replace("") end
end)
```

The shortcut works from a keyboard and from the Ctrl / Alt / Shift keys above the on-screen keyboard, and can
be changed in Settings > Shortcut Mapper like any other.

## The editor

`editor` is the editor you type in. `editor1` is the same; `editor2` is the second view when the screen is split.

Every function of Scintilla is there under its name from
[Scintilla's documentation](https://www.scintilla.org/ScintillaDoc.html), without the `SCI_` and in mixed
case: `SCI_GOTOLINE` is `editor:GotoLine(line)`. Positions and lines count from 0.

- Text goes in as a string, and its length is worked out for you: `editor:AddText("abc")`.
- Text comes back as a string: `local text = editor:GetText()`, `editor:GetLine(3)`, `editor:GetSelText()`.
- A getter and setter pair is a property: `editor.TabWidth = 4`, `if editor.ReadOnly then`,
  `editor.CurrentPos`, `editor.Length`, `editor.LineCount`.
- Where the pair takes a number first, the property takes an index: `editor.StyleBold[5] = true`,
  `editor.LineEndPosition[0]`, `editor.Property["fold"]`.
- Yes/no values are `true` and `false`. Unlike in plain Lua, `0` counts as no here.

A few helpers come on top, the same as in LuaScript and SciTE:

| Helper | |
|---|---|
| `editor:textrange(from, to)` | The text between two positions |
| `editor:findtext(text, flags, from, to)` | Start and end of the first match, or nothing. `flags` are `SCFIND_` constants |
| `editor:match(text, flags, from)` | For loops: each match has `pos`, `len`, `text`, `style` and `m:replace(new)` |
| `editor:append(text)`, `editor:insert(pos, text)`, `editor:remove(from, to)` | |

All of Scintilla's and its lexers' constants are there by name: `SCFIND_REGEXP`, `SC_EOL_LF`, `INDIC_ROUNDBOX`,
`SCE_P_COMMENTLINE`, `SCI_ADDTEXT` and so on.

## The application

Functions of `npp` can be called with a dot or a colon.

| | |
|---|---|
| `npp:GetFileName()`, `GetFullCurrentPath()`, `GetCurrentDirectory()`, `GetNamePart()`, `GetExtPart()` | About the document on screen. A file picked through Android's file picker has an address, not a path |
| `npp:GetCurrentWord()` | The selection, or the word at the caret |
| `npp:GetNbOpenFiles()`, `GetCurrentDocIndex(view)`, `GetBufferIDFromPos(index, view)`, `GetFullPathFromBufferID(id)` | The open tabs |
| `npp:DoOpen(path)`, `SwitchToFile(nameOrPath)`, `ActivateDoc(view, index)` | |
| `npp:SaveCurrentFile()`, `SaveAllFiles()` | |
| `npp:MenuCommand(command)` | Runs a menu command: a Notepad++ `IDM_` constant such as `IDM_EDIT_JOIN_LINES`, or its place in the menus, `"Edit > Line Operations > Join Lines"` |
| `npp.AddShortcut(name, shortcut, f)` | Adds a command to Plugins > Lua Script. The shortcut may be `""` |
| `npp.AddEventHandler(event, f)`, `RemoveEventHandler(event, f)`, `RemoveAllEventHandlers(event)` | See the events below |
| `npp.StartTimer(milliseconds, f)`, `npp.StopTimer(timer)` | Calls `f(timer)` again and again until stopped |
| `npp.SendEditor(SCI_xxx, w, l)` | Scintilla by message number |
| `npp.ConstantName(number, prefix)` | The name of a constant |
| `npp.WriteError(...)`, `npp.ClearConsole()`, `print(...)` | The console |
| `npp.Version`, `npp.CurrentBufferID`, `npp.CurrentLine`, `npp.CurrentColumn`, `npp.CurrentView` | |
| `npp.BufferLangType[id]`, `npp.LanguageName[L_xxx]`, `npp.LanguageDescription[L_xxx]` | Languages by Notepad++'s `L_` constants |
| `npp.DefaultForegroundColor`, `npp.DefaultBackgroundColor` | |
| `npp:GetLanguage()`, `npp:SetLanguage(name)`, `npp:Toast(text)` | Additions of Pocketpad++: the language by name, and a short message on screen |

Events a handler can be registered for:

| Event | Arguments |
|---|---|
| `OnReady` | none; after the startup script has run |
| `OnOpen`, `OnSwitchFile`, `OnBeforeSave`, `OnSave`, `OnFileRenamed` | file path (or tab name), buffer id |
| `OnClose`, `OnLangChange` | none |
| `OnChar` | the character typed |
| `OnUpdateUI` | `SC_UPDATE_` flags; after the caret, selection, text or scroll position changed |
| `OnDoubleClick` | position, line, modifiers |
| `OnMarginClick` | position, margin, modifiers |
| `OnSavePointReached`, `OnSavePointLeft`, `OnModifyAttemptRO`, `OnZoom` | none |

Scripts can keep modules beside the startup script: `require "name"` looks for `name.lua` in the same folder
(`npp:GetPluginsConfigDir()` says where that is).

## Limits

- A script that runs for more than five seconds in one go is stopped with an error, and so is one that takes
  more than about 190 MB of memory. While a script runs, the app waits for it.
- `os.exit` is removed. `io` and `os` reach what Android lets the app reach: its own storage.
- Compiled Lua (bytecode) is not run as a script.
- Functions that hand out raw memory (`GetCharacterPointer`, `GetDocPointer`, `CreateDocument` and the like)
  are not available.

## Differences from LuaScript

- The console is a text panel, not an editor of its own, so there is no `console` object.
- These events are accepted but never sent: `OnStyle`, `OnModification`, the hotspot, indicator, autocompletion,
  dwell and focus events, `OnBeforeOpen`, `OnBeforeClose`, the rename and delete steps other than `OnFileRenamed`,
  and the shutdown events. Registering for one prints a note in the console.
- `npp` functions about Notepad++'s own window (`HideMenu`, `HideTabBar`, docking, sessions by file,
  `ReloadFile`, `SaveCurrentFileAs`, `StatusBar`) are not there, and say so when used.
- `npp:MenuCommand` with an `IDM_` constant only works for commands this app has; the dozen that are tied to
  Windows raise an error.
- `npp.AddShortcut` works at any time, not only during startup. Keys this app has no shortcuts for
  (`;`, `[`, `~` and so on) leave the command without a shortcut.
- `OnUpdateUI` and the click, zoom and save-point events arrive a moment after the fact rather than in the
  middle of it.
- `editor` is always the first view: the second view is for reading here.
- Lua is 5.4 where LuaScript has 5.3; the languages differ in small ways only.
