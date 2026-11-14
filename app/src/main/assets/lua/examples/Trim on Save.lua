-- Trim on Save: takes the spaces and tabs off the ends of lines whenever a file is saved.
--
-- Try it with Plugins > Lua Script > Execute Current File; it then holds until Lua is restarted.
-- To have it always, copy it into the startup script (Plugins > Lua Script > Edit Startup Script).

-- Kept under a name, so that running the script again replaces the handler instead of adding a second one.
if TrimOnSave then npp.RemoveEventHandler("OnBeforeSave", TrimOnSave) end

function TrimOnSave(filename)
    editor:BeginUndoAction()
    for m in editor:match("[ \t]+$", SCFIND_REGEXP) do
        m:replace("")
    end
    editor:EndUndoAction()
    return false
end

npp.AddEventHandler("OnBeforeSave", TrimOnSave)
