#!/usr/bin/env python3
"""Build docs/FEATURES.md: every Notepad++ menu command and where Pocketpad++ stands on it.

Usage: python3 tools/feature_map.py /path/to/notepad-plus-plus

The command list comes from Notepad++'s own menu resource (PowerEditor/src/Notepad_plus.rc), so
nothing is left out by accident. The judgement is in RULES below: the first pattern that matches
a command id decides its status, phase and note. Update DONE as commands are implemented.
"""
import collections
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

DONE = "Done"
ENGINE = "Engine"   # Scintilla already does it; needs a menu entry or gesture
BUILD = "Build"     # application feature to write
ADAPT = "Adapt"     # the idea carries over but needs an Android-specific design
NO = "No"           # tied to Windows; not portable

PHASES = {
    1: "Core editor: tabs, files, encodings, languages, find and replace, sessions",
    2: "Editing power tools: remaining line, case, blank and selection commands, multi-select, column mode, recent files",
    3: "Search power tools: find in files, mark, styled tokens, incremental search, results panel",
    4: "Panels and views: document list, function list, document map, folder as workspace, split view, auto-completion",
    5: "Customisation: preferences, style configurator and themes, shortcut mapper, user-defined languages",
    6: "Macros, run, tools, printing",
}

# Commands available in the current build.
DONE_IDS = set("""
IDM_FILE_NEW IDM_FILE_OPEN IDM_FILE_RELOAD IDM_FILE_SAVE IDM_FILE_SAVEAS IDM_FILE_SAVECOPYAS IDM_FILE_SAVEALL
IDM_FILE_CLOSE IDM_FILE_CLOSEALL IDM_FILE_CLOSEALL_BUT_CURRENT IDM_FILE_CLOSEALL_TOLEFT IDM_FILE_CLOSEALL_TORIGHT
IDM_FILE_CLOSEALL_UNCHANGED
IDM_EDIT_UNDO IDM_EDIT_REDO IDM_EDIT_CUT IDM_EDIT_COPY IDM_EDIT_PASTE IDM_EDIT_DELETE IDM_EDIT_SELECTALL
IDM_EDIT_INS_TAB IDM_EDIT_RMV_TAB IDM_EDIT_UPPERCASE IDM_EDIT_LOWERCASE IDM_EDIT_DUP_LINE
IDM_EDIT_REMOVE_ANY_DUP_LINES IDM_EDIT_JOIN_LINES IDM_EDIT_LINE_UP IDM_EDIT_LINE_DOWN IDM_EDIT_REMOVEEMPTYLINES
IDM_EDIT_REMOVEEMPTYLINESWITHBLANK IDM_EDIT_SORTLINES_REVERSE_ORDER IDM_EDIT_SORTLINES_LEXICOGRAPHIC_ASCENDING
IDM_EDIT_SORTLINES_LEXICOGRAPHIC_DESCENDING IDM_EDIT_BLOCK_COMMENT IDM_EDIT_TRIMTRAILING IDM_EDIT_TOGGLEREADONLY
IDM_FORMAT_TODOS IDM_FORMAT_TOUNIX IDM_FORMAT_TOMAC
IDM_SEARCH_FIND IDM_SEARCH_FINDNEXT IDM_SEARCH_FINDPREV IDM_SEARCH_REPLACE IDM_SEARCH_GOTOLINE
IDM_SEARCH_GOTOMATCHINGBRACE IDM_SEARCH_TOGGLE_BOOKMARK IDM_SEARCH_NEXT_BOOKMARK IDM_SEARCH_PREV_BOOKMARK
IDM_SEARCH_CLEAR_BOOKMARKS
IDM_VIEW_WRAP IDM_VIEW_TAB_SPACE IDM_VIEW_EOL IDM_VIEW_ALL_CHARACTERS IDM_VIEW_INDENT_GUIDE IDM_VIEW_WRAP_SYMBOL
IDM_VIEW_ZOOMIN IDM_VIEW_ZOOMOUT IDM_VIEW_ZOOMRESTORE IDM_VIEW_FOLDALL IDM_VIEW_UNFOLDALL IDM_VIEW_SUMMARY
IDM_VIEW_TAB_MOVEFORWARD IDM_VIEW_TAB_MOVEBACKWARD
IDM_FILE_RESTORELASTCLOSEDFILE IDM_OPEN_ALL_RECENT_FILE IDM_CLEAN_RECENT_FILE_LIST
IDM_EDIT_BEGINENDSELECT IDM_EDIT_BEGINENDSELECT_COLUMNMODE IDM_EDIT_INSERT_DATETIME_SHORT IDM_EDIT_INSERT_DATETIME_LONG
IDM_EDIT_FULLPATHTOCLIP IDM_EDIT_FILENAMETOCLIP IDM_EDIT_COPY_ALL_NAMES IDM_EDIT_COPY_ALL_PATHS
IDM_EDIT_PROPERCASE_FORCE IDM_EDIT_PROPERCASE_BLEND IDM_EDIT_SENTENCECASE_FORCE IDM_EDIT_SENTENCECASE_BLEND
IDM_EDIT_INVERTCASE IDM_EDIT_RANDOMCASE IDM_EDIT_REMOVE_CONSECUTIVE_DUP_LINES IDM_EDIT_SPLIT_LINES
IDM_EDIT_BLANKLINEABOVECURRENT IDM_EDIT_BLANKLINEBELOWCURRENT IDM_EDIT_SORTLINES_RANDOMLY
IDM_EDIT_SORTLINES_LEXICO_CASE_INSENS_ASCENDING IDM_EDIT_SORTLINES_LOCALE_ASCENDING IDM_EDIT_SORTLINES_INTEGER_ASCENDING
IDM_EDIT_SORTLINES_DECIMALCOMMA_ASCENDING IDM_EDIT_SORTLINES_DECIMALDOT_ASCENDING IDM_EDIT_SORTLINES_LENGTH_ASCENDING
IDM_EDIT_SORTLINES_LEXICO_CASE_INSENS_DESCENDING IDM_EDIT_SORTLINES_LOCALE_DESCENDING IDM_EDIT_SORTLINES_INTEGER_DESCENDING
IDM_EDIT_SORTLINES_DECIMALCOMMA_DESCENDING IDM_EDIT_SORTLINES_DECIMALDOT_DESCENDING IDM_EDIT_SORTLINES_LENGTH_DESCENDING
IDM_EDIT_BLOCK_COMMENT_SET IDM_EDIT_BLOCK_UNCOMMENT IDM_EDIT_STREAM_COMMENT IDM_EDIT_STREAM_UNCOMMENT
IDM_EDIT_TRIMLINEHEAD IDM_EDIT_TRIM_BOTH IDM_EDIT_EOL2WS IDM_EDIT_TRIMALL IDM_EDIT_TAB2SW IDM_EDIT_SW2TAB_ALL
IDM_EDIT_SW2TAB_LEADING IDM_EDIT_SEARCHONINTERNET
IDM_EDIT_MULTISELECTALL IDM_EDIT_MULTISELECTALLMATCHCASE IDM_EDIT_MULTISELECTALLWHOLEWORD
IDM_EDIT_MULTISELECTALLMATCHCASEWHOLEWORD IDM_EDIT_MULTISELECTNEXT IDM_EDIT_MULTISELECTNEXTMATCHCASE
IDM_EDIT_MULTISELECTNEXTWHOLEWORD IDM_EDIT_MULTISELECTNEXTMATCHCASEWHOLEWORD IDM_EDIT_MULTISELECTUNDO
IDM_EDIT_MULTISELECTSSKIP
IDM_SEARCH_SETANDFINDNEXT IDM_SEARCH_SETANDFINDPREV IDM_SEARCH_VOLATILE_FINDNEXT IDM_SEARCH_VOLATILE_FINDPREV
IDM_SEARCH_SELECTMATCHINGBRACES
IDM_VIEW_TAB_START IDM_VIEW_TAB_END IDM_VIEW_TAB_NEXT IDM_VIEW_TAB_PREV IDM_VIEW_GOTO_START IDM_VIEW_GOTO_END
IDM_VIEW_FOLD_CURRENT IDM_VIEW_UNFOLD_CURRENT
""".split()) | {f"IDM_VIEW_{kind}_{level}" for kind in ("FOLD", "UNFOLD") for level in range(1, 9)}

# (pattern on the command id, status, phase or None, note)
RULES = [
    # File
    (r"IDM_FILE_OPEN_(CMD|POWERSHELL)$", NO, None, "Opens a Windows shell"),
    (r"IDM_FILE_OPEN_FOLDER$", ADAPT, 4, "Show the file in the system file manager"),
    (r"IDM_FILE_OPEN_DEFAULT_VIEWER$", ADAPT, 2, "Hand the file to another app with a view intent"),
    (r"IDM_FILE_(CONTAININGFOLDERASWORKSPACE|OPENFOLDERASWORKSPACE)$", BUILD, 4, "Needs folder access through the Storage Access Framework"),
    (r"IDM_FILE_RENAME$", ADAPT, 2, "Through the document provider, where it allows renaming"),
    (r"IDM_FILE_DELETE$", ADAPT, 2, "Delete through the document provider; Android has no recycle bin"),
    (r"IDM_FILE_CLOSEALL_BUT_PINNED$", BUILD, 4, "Needs pinned tabs"),
    (r"IDM_FILE_(LOADSESSION|SAVESESSION)$", BUILD, 4, "The session file already uses Notepad++'s layout"),
    (r"IDM_FILE_PRINT", ADAPT, 6, "Android print framework"),
    (r"IDM_FILE_EXIT$", ADAPT, None, "Android apps are left with Back or Home; the session is saved either way"),
    (r"IDM_FILE_RESTORELASTCLOSEDFILE|IDM_OPEN_ALL_RECENT_FILE|IDM_CLEAN_RECENT_FILE_LIST|IDM_FILEMENU", BUILD, 2, "Recent files"),
    # Edit
    (r"IDM_EDIT_TOGGLESYSTEMREADONLY$", NO, None, "Windows file attribute"),
    (r"IDM_EDIT_(BEGINENDSELECT|BEGINENDSELECT_COLUMNMODE)$", ENGINE, 2, "Useful on touch, where shift-click does not exist"),
    (r"IDM_EDIT_SPLIT_LINES$", ENGINE, 2, ""),
    (r"IDM_EDIT_MULTISELECT", ENGINE, 2, "Scintilla's multiple selection commands"),
    (r"IDM_EDIT_COLUMNMODETIP$", ADAPT, 2, "Column selection needs a touch gesture instead of Alt+drag"),
    (r"IDM_EDIT_COLUMNMODE$", BUILD, 2, "Column editor dialog"),
    (r"IDM_EDIT_(AUTOCOMPLETE|FUNCCALLTIP)", BUILD, 4, "Needs list and call tip windows in the platform layer"),
    (r"IDM_EDIT_PASTE_AS_(HTML|RTF)$", ADAPT, 2, "From Android's HTML clipboard format"),
    (r"IDM_EDIT_(COPY|CUT|PASTE)_BINARY$", BUILD, 2, ""),
    (r"IDM_EDIT_OPENSELECTEDFILEFOLDERINEXPLORER$", ADAPT, 4, "Show in the system file manager"),
    (r"IDM_EDIT_(CHAR_PANEL|CLIPBOARDHISTORY_PANEL)$", BUILD, 4, "Panels"),
    (r"IDM_EDIT_(RTL|LTR)$", BUILD, 4, "Needs bidirectional text layout in the platform layer"),
    (r"IDM_EDIT_", BUILD, 2, ""),
    # Search
    (r"IDM_SEARCH_(FINDINFILES)$", BUILD, 3, "Needs folder access through the Storage Access Framework"),
    (r"IDM_SEARCH_SELECTMATCHINGBRACES$", ENGINE, 2, ""),
    (r"IDM_SEARCH_(CHANGED_NEXT|CHANGED_PREV|CLEAR_CHANGE_HISTORY)$", ENGINE, 2, "Scintilla's change history"),
    (r"IDM_SEARCH_(SETANDFINDNEXT|SETANDFINDPREV|VOLATILE_FINDNEXT|VOLATILE_FINDPREV)$", BUILD, 2, ""),
    (r"IDM_SEARCH_(MARKALLEXT|MARKONEEXT|UNMARKALLEXT|CLEARALLMARKS|GOPREVMARKER|GONEXTMARKER|STYLE\dTOCLIP|ALLSTYLESTOCLIP|MARKEDTOCLIP)", BUILD, 3, "On Scintilla indicators"),
    (r"IDM_SEARCH_(CUTMARKEDLINES|COPYMARKEDLINES|PASTEMARKEDLINES|DELETEMARKEDLINES|DELETEUNMARKEDLINES|INVERSEMARKS)$", BUILD, 3, "Bookmarked-line operations"),
    (r"IDM_SEARCH_|IDM_FOCUS_ON_FOUND_RESULTS", BUILD, 3, ""),
    # View
    (r"IDM_VIEW_(ALWAYSONTOP|POSTIT)$", NO, None, "Desktop window management"),
    (r"IDM_VIEW_IN_(FIREFOX|CHROME|EDGE|IE)$", ADAPT, 2, "One 'open in browser' command using a view intent"),
    (r"IDM_VIEW_(FULLSCREENTOGGLE|DISTRACTIONFREE)$", ADAPT, 4, "Immersive mode"),
    (r"IDM_VIEW_(GOTO_NEW_INSTANCE|LOAD_IN_NEW_INSTANCE)$", ADAPT, 4, "A second window on tablets and foldables"),
    (r"IDM_VIEW_(GOTO_ANOTHER_VIEW|CLONE_TO_ANOTHER_VIEW|SWITCHTO_OTHER_VIEW|SYNSCROLLV|SYNSCROLLH|ZOOM_SYNC)$", ADAPT, 4, "Split view, for larger screens"),
    (r"IDM_VIEW_(FOLD|UNFOLD)_(\d|CURRENT)$", ENGINE, 2, ""),
    (r"IDM_VIEW_HIDELINES$", ENGINE, 2, ""),
    (r"IDM_VIEW_(NPC|NPC_CCUNIEOL)$", ENGINE, 2, "Scintilla character representations"),
    (r"IDM_VIEW_TAB(\d|_START|_END|_NEXT|_PREV)$", BUILD, 2, "Keyboard shortcuts; touch uses the tab strip"),
    (r"IDM_VIEW_GOTO_(START|END)$", BUILD, 2, "Move tab to start or end"),
    (r"IDM_VIEW_TAB_COLOUR", BUILD, 4, ""),
    (r"IDM_VIEW_", BUILD, 4, ""),
    # Encoding
    (r"IDM_FORMAT_CONV2_", DONE, 1, "Text is always Unicode in memory, so this is the same as choosing the encoding"),
    (r"IDM_FORMAT_", DONE, 1, ""),
    # Language
    (r"IDM_LANG_(USER_DLG|OPENUDLDIR|UDLCOLLECTION_PROJECT_SITE|USER)$", BUILD, 5, "The user-defined-language lexer is built in; the editor for it is not"),
    (r"IDM_LANG_", DONE, 1, ""),
    # Settings, tools, macro, run, plugins, window, help
    (r"IDM_SETTING_(IMPORTPLUGIN|OPENPLUGINSDIR)$", NO, None, "Notepad++ plugins are Windows DLLs"),
    (r"IDM_SETTING_|IDM_LANGSTYLE_CONFIG_DLG", BUILD, 5, ""),
    (r"IDM_TOOL_", BUILD, 6, ""),
    (r"IDM_MACRO_", BUILD, 6, "On Scintilla's macro recording notifications"),
    (r"IDM_EXECUTE_VALIDATE_SHORTCUTSXML$", NO, None, ""),
    (r"IDM_EXECUTE$", ADAPT, 6, "Launching other apps with intents instead of command lines"),
    (r"IDM_WINDOW_|IDM_DROPLIST_LIST", BUILD, 4, "Document list"),
    (r"IDM_(UPDATE_NPP|CONFUPDATERPROXY|CMDLINEARGUMENTS)$", NO, None, "Updates come from the app store or F-Droid"),
    (r"IDM_(ABOUT|HOMESWEETHOME|PROJECTPAGE|ONLINEDOCUMENT|FORUM|DEBUGINFO)$", BUILD, 5, "About screen and links for Pocketpad++"),
]


def parse_menu(rc_text):
    """Yield (path, label, id) for every item of Notepad++'s main menu."""
    lines = rc_text[rc_text.index("IDR_M30_MENU MENU"):].splitlines()[1:]
    stack, pending = [], None
    for line in lines:
        text = line.strip()
        popup = re.match(r'POPUP\s+"((?:[^"]|"")*)"', text)
        if popup:
            pending = popup.group(1)
        elif text == "BEGIN":
            stack.append(pending)
            pending = None
        elif text == "END":
            stack.pop()
            if not stack:
                return
        else:
            item = re.match(r'MENUITEM\s+"((?:[^"]|"")*)"\s*,\s*(\w+)', text)
            if item:
                path = [clean(s) for s in stack if s]
                yield path, clean(item.group(1)), item.group(2)


def clean(label):
    return label.split("\\t")[0].replace("&&", "\0").replace("&", "").replace("\0", "&").replace('""', '"').strip()


def classify(command_id):
    if command_id in DONE_IDS:
        # Count a finished command under the phase it was planned for.
        for pattern, _, phase, _ in RULES:
            if re.match(pattern, command_id):
                return DONE, phase or 1, ""
        return DONE, 1, ""
    for pattern, status, phase, note in RULES:
        if re.match(pattern, command_id):
            return status, phase, note
    return BUILD, None, "Unclassified"


def main(npp):
    rc = (pathlib.Path(npp) / "PowerEditor/src/Notepad_plus.rc").read_text(encoding="utf-8", errors="replace")
    seen, rows = set(), []
    for path, label, command_id in parse_menu(rc):
        if command_id in seen:
            continue
        seen.add(command_id)
        rows.append((path, label, command_id) + classify(command_id))

    unclassified = [r[2] for r in rows if r[5] == "Unclassified"]
    if unclassified:
        print("unclassified:", unclassified, file=sys.stderr)

    counts = collections.Counter(r[3] for r in rows)
    languages_done = sum(1 for r in rows if r[3] == DONE and r[2].startswith("IDM_LANG_"))
    encodings_done = sum(1 for r in rows if r[3] == DONE and r[2].startswith("IDM_FORMAT_") and not re.match(r"IDM_FORMAT_TO(DOS|UNIX|MAC)$", r[2]))
    total = len(rows)
    out = ["# Notepad++ feature map", "",
           "Every command in Notepad++'s menus, taken from its own menu resource, and where Pocketpad++ stands on it.",
           "Generated by `tools/feature_map.py`; edit the rules there, not this file.", "",
           "| Status | Meaning | Commands |", "|---|---|---|",
           f"| {DONE} | Implemented in the current build | {counts[DONE]} |",
           f"| {ENGINE} | Scintilla already does it; needs a menu entry or gesture | {counts[ENGINE]} |",
           f"| {BUILD} | Application feature to write | {counts[BUILD]} |",
           f"| {ADAPT} | Carries over, but needs an Android-specific design | {counts[ADAPT]} |",
           f"| {NO} | Tied to Windows; not portable | {counts[NO]} |",
           f"| | Total | {total} |", "",
           f"Of the {counts[DONE]} implemented commands, {languages_done} are entries of the Language menu and "
           f"{encodings_done} are encodings; {counts[DONE] - languages_done - encodings_done} are editing, file, search and view commands.",
           "Implemented means built and, where it is logic rather than screen layout, covered by the desktop tests.",
           "It does not yet mean checked by hand on a range of devices.", "",
           "## Phases", ""]
    by_phase = collections.Counter((r[4], r[3]) for r in rows)
    out += ["| Phase | Scope | Done | Remaining |", "|---|---|---|---|"]
    for phase, scope in PHASES.items():
        done = by_phase[(phase, DONE)]
        remaining = sum(n for (p, s), n in by_phase.items() if p == phase and s != DONE)
        out.append(f"| {phase} | {scope} | {done} | {remaining} |")
    out += ["",
            "Not counted above because they are not menu commands: the plugin system (Notepad++ plugins are",
            "Windows DLLs and cannot be loaded; an extension mechanism of our own would be a separate project),",
            "and behaviour that lives in Preferences such as auto-completion settings, backup intervals and",
            "the many editing options, which phase 5 will inventory separately.", "",
            "Regular expressions currently use the C++ standard library's ECMAScript syntax; Notepad++ uses",
            "Boost.Regex (Perl syntax, including lookbehind and `\\K`). Matching that is a phase 3 item.", ""]

    groups = collections.OrderedDict()
    for row in rows:
        groups.setdefault(row[0][0] if row[0] else "Other", []).append(row)
    for menu, items in groups.items():
        out += [f"## {menu}", "", "| Command | Status | Phase | Note |", "|---|---|---|---|"]
        collapsed = collections.OrderedDict()
        for path, label, command_id, status, phase, note in items:
            sub = " > ".join(path[1:])
            # Long uniform lists (languages, character sets) are shown as one row.
            if menu == "Language" and command_id.startswith("IDM_LANG_") and status == DONE:
                key = ("__languages__", status, phase, "From Notepad++'s langs.model.xml and stylers.model.xml")
                collapsed.setdefault(key, []).append(label)
            elif menu == "Encoding" and sub.startswith("Character sets"):
                key = ("__charsets__", status, phase, "Re-reads the file as that character set; a few may be missing on some devices")
                collapsed.setdefault(key, []).append(label)
            else:
                collapsed[((sub + " > " if sub else "") + label, status, phase, note)] = None
        for (name, status, phase, note), members in collapsed.items():
            if name == "__languages__":
                name = f"{len(members)} built-in languages ({members[0]} to {members[-1]})"
            elif name == "__charsets__":
                name = f"Character sets ({len(members)})"
            out.append(f"| {name.replace('|', '/')} | {status} | {phase if phase else ''} | {note} |")
        out.append("")
    (ROOT / "docs/FEATURES.md").write_text("\n".join(out), encoding="utf-8")
    print(f"{total} commands:", dict(counts))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
