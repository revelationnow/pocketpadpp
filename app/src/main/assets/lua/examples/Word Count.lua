-- Word Count: the lines, words and characters of the selection, or of the whole document.
--
-- Try it with Plugins > Lua Script > Execute Current File, then find "Word Count" in the same menu.
-- To have it always, copy it into the startup script (Plugins > Lua Script > Edit Startup Script).

npp.AddShortcut("Word Count", "Ctrl+Alt+W", function()
    local text, what = editor:GetSelText(), "Selection"
    if #text == 0 then
        text, what = editor:GetText(), "Document"
    end
    local _, words = text:gsub("%S+", "")
    local _, breaks = text:gsub("\n", "")
    -- Characters rather than bytes, where the text is proper UTF-8.
    local characters = utf8.len(text) or #text
    npp:Toast(("%s: %d lines, %d words, %d characters"):format(what, breaks + 1, words, characters))
end)
