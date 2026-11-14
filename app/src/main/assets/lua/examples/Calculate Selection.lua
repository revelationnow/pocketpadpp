-- Calculate Selection: select a sum such as 12 * (3 + 4.5) and it becomes 12 * (3 + 4.5) = 90.0
--
-- Try it with Plugins > Lua Script > Execute Current File, then find "Calculate Selection" in the same menu.
-- The sum is Lua, so math.sqrt(2), 2^10 and 7 // 2 work too.

npp.AddShortcut("Calculate Selection", "Ctrl+Alt+E", function()
    local sum = editor:GetSelText()
    -- Only arithmetic is let in: the sum sees the math library and nothing else.
    local calculation = load("return " .. sum, "=selection", "t", { math = math })
    local fine, result = pcall(calculation or error)
    if fine and type(result) == "number" then
        editor:ReplaceSel(sum .. " = " .. tostring(result))
    else
        npp:Toast("Select a calculation first, such as 12 * (3 + 4.5)")
    end
end)
