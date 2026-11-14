-- Insert Date: puts today's date where the caret is, or over the selection.
--
-- Try it with Plugins > Lua Script > Execute Current File, then find "Insert Date" in the same menu.
-- The letters after % are those of Lua's os.date: %H:%M is the time, %A the day of the week.

npp.AddShortcut("Insert Date", "Ctrl+Alt+D", function()
    editor:ReplaceSel(os.date("%Y-%m-%d"))
end)
