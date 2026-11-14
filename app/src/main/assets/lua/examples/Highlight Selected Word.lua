-- Highlight Selected Word: select a word and every other place it occurs on screen is marked.
--
-- Try it with Plugins > Lua Script > Execute Current File; it then holds until Lua is restarted.
-- To have it always, copy it into the startup script (Plugins > Lua Script > Edit Startup Script).

-- Indicators 9 to 19 are free for scripts; the app uses the ones above.
local INDICATOR = 12
editor.IndicStyle[INDICATOR] = INDIC_ROUNDBOX
editor.IndicFore[INDICATOR] = 0x00A5FF -- orange: colours are written blue, green, red
editor.IndicAlpha[INDICATOR] = 70
editor.IndicOutlineAlpha[INDICATOR] = 160

if HighlightSelectedWord then npp.RemoveEventHandler("OnUpdateUI", HighlightSelectedWord) end

function HighlightSelectedWord()
    -- Only the lines on screen are looked at, so that long documents stay quick.
    local first = editor.FirstVisibleLine
    local from = editor:PositionFromLine(editor:DocLineFromVisible(first))
    local to = editor.LineEndPosition[editor:DocLineFromVisible(first + editor.LinesOnScreen)]
    editor.IndicatorCurrent = INDICATOR
    editor:IndicatorClearRange(from, to - from)

    local word = editor:GetSelText()
    if #word < 2 or word:find("%s") then return false end
    local flags = SCFIND_MATCHCASE | SCFIND_WHOLEWORD
    local start, finish = editor:findtext(word, flags, from, to)
    while start do
        if start ~= editor.SelectionStart then editor:IndicatorFillRange(start, finish - start) end
        start, finish = editor:findtext(word, flags, finish, to)
    end
    return false
end

npp.AddEventHandler("OnUpdateUI", HighlightSelectedWord)
