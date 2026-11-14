-- Auto Close Brackets: type ( [ or { and the closing one appears, with the caret between the two.
--
-- Try it with Plugins > Lua Script > Execute Current File; it then holds until Lua is restarted.
-- To have it always, copy it into the startup script (Plugins > Lua Script > Edit Startup Script).

local closing = { ["("] = ")", ["["] = "]", ["{"] = "}" }

if AutoCloseBrackets then npp.RemoveEventHandler("OnChar", AutoCloseBrackets) end

function AutoCloseBrackets(character)
    local close = closing[character]
    if close and editor.SelectionEmpty then
        local caret = editor.CurrentPos
        -- Not in front of a word: there the bracket is meant to go around what follows.
        if not editor:textrange(caret, caret + 1):match("[%w_]") then
            editor:InsertText(caret, close)
        end
    end
    return false
end

npp.AddEventHandler("OnChar", AutoCloseBrackets)
