-- Pocketpad++ scripting layer: what a script sees. Run once in every new Lua interpreter.
--
-- The objects follow Notepad++'s LuaScript plugin, so that scripts written for it carry over:
--   editor, editor1, editor2   the Scintilla editors, with every function and property of Scintilla.iface
--   npp                        the application: files, menu commands, shortcuts, events, timers
--   print                      writes to the Lua console
--   SCI_..., SC_..., IDM_...   constants, by name
-- Native code supplies only the table "host" (see LuaBridge.cpp); everything else is built here.

local host = host
_G.host = nil
local send, fetch, range, find, call = host.send, host.fetch, host.range, host.find, host.call

local unpack, pack = table.unpack, table.pack

-- A script can end the app's process with this; nothing good comes of it.
os.exit = nil

local scripts = call("scripts")
if scripts and scripts ~= "" then
    package.path = scripts .. "/?.lua;" .. scripts .. "/?/init.lua"
end
package.cpath = ""

function print(...)
    local parts = pack(...)
    for i = 1, parts.n do parts[i] = tostring(parts[i]) end
    call("print", table.concat(parts, "\t", 1, parts.n), false)
end

-- Constants are looked up by name when a script uses one, from a table read on first use.
local constants
local function constant(name)
    if not constants then constants = assert(load(call("source", "constants"), "=constants", "t"))() end
    return constants[name]
end
setmetatable(_G, { __index = function(_, name) return constant(name) end })

MAIN_VIEW, SUB_VIEW = 0, 1
ALL_OPEN_FILES, PRIMARY_VIEW, SECOND_VIEW = 0, 1, 2

-- Scintilla's interface --------------------------------------------------------------------------

local functions, properties, blocked = {}, {}, {}
assert(load(call("source", "iface"), "=iface", "t"))(
    function(name, message, returns, first, second) functions[name] = { message, returns, first, second } end,
    function(name, getter, setter, value, index, flags) properties[name] = { getter, setter, value, index, flags } end,
    function(name) blocked[name] = true end)

local function whole(value, position, name)
    local kind = type(value)
    if kind == "number" then
        local n = math.tointeger(value)
        if n then return n end
    elseif kind == "boolean" then
        return value and 1 or 0
    elseif kind == "string" then
        local n = math.tointeger(tonumber(value))
        if n then return n end
    end
    error(("%s: argument %d must be a whole number, not %s"):format(name, position, kind == "number" and "a fraction" or kind), 3)
end

-- Unlike plain Lua, the number 0 counts as false here: editor.ViewEOL = 0 turns it off.
local function truth(value)
    return (value and value ~= 0) and 1 or 0
end

local function text(value, position, name)
    local kind = type(value)
    if kind == "string" then return value end
    if kind == "number" then return tostring(value) end
    error(("%s: argument %d must be a string, not %s"):format(name, position, kind), 3)
end

local function answer(kind, result)
    if kind == "b" then return result ~= 0 end
    if kind == "i" then return result end
end

-- One parameter of a message from the arguments a script gave. Returns the value and how many it used.
local function parameter(kind, args, at, name)
    if kind == "v" then return 0, 0 end
    if kind == "b" then return truth(args[at]), 1 end
    if kind == "s" then return text(args[at], at, name), 1 end
    if kind == "k" then
        -- A key and its modifiers travel as one number.
        return (whole(args[at], at, name) & 0xFFFF) | ((whole(args[at + 1], at + 1, name) & 7) << 16), 2
    end
    return whole(args[at], at, name), 1
end

local function caller(name, def)
    local message, returns, first, second = def[1], def[2], def[3], def[4]
    if first == "L" and second == "s" then
        -- A text and its length: the script gives the text.
        return function(pane, value)
            value = text(value, 1, name)
            return answer(returns, send(pane.view, message, #value, value))
        end
    end
    if second == "r" then
        -- A text comes back. Scintilla is asked how long it is, then for the text itself.
        return function(pane, value)
            local view, w = pane.view, 0
            if first ~= "L" and first ~= "v" then w = parameter(first, { value }, 1, name) end
            local length = send(view, message, w, 0)
            local result, number = fetch(view, message, first == "L" and length or w, length)
            if returns == "v" then return result end
            return result, answer(returns, number)
        end
    end
    return function(pane, ...)
        local args = { ... }
        local w, used = parameter(first, args, 1, name)
        local l = parameter(second, args, 1 + used, name)
        return answer(returns, send(pane.view, message, w, l))
    end
end

local methods = {}

local function method(name)
    local found = methods[name]
    if not found and functions[name] then
        found = caller(name, functions[name])
        methods[name] = found
    end
    return found
end

local function index(kind, value, name)
    if kind == "s" then return text(value, 1, name) end
    if kind == "b" then return truth(value) end
    return whole(value, 1, name)
end

local function get(view, name, def, at)
    local getter, value, flags = def[1], def[3], def[5]
    if getter == 0 then error(("the property %s can only be set"):format(name), 3) end
    if value == "s" then
        local length = send(view, getter, at, 0)
        return (fetch(view, getter, (flags & 2 ~= 0) and length or at, length))
    end
    local result = send(view, getter, at, 0)
    if value == "b" then return result ~= 0 end
    return result
end

local function set(view, name, def, at, value)
    local setter, kind, flags = def[2], def[3], def[5]
    if setter == 0 then error(("the property %s can only be read"):format(name), 3) end
    if kind == "s" then value = text(value, 2, name) elseif kind == "b" then value = truth(value) else value = whole(value, 2, name) end
    if at ~= nil then
        send(view, setter, at, value)
    elseif flags & 1 ~= 0 then
        send(view, setter, 0, value)
    else
        send(view, setter, value, 0)
    end
end

local Pane = {}

-- editor.StyleBold[style]: an indexed property is a small object that reads and writes through its index.
local function indexed(pane, name, def)
    local bindings = rawget(pane, "bindings")
    local binding = bindings[name]
    if not binding then
        binding = setmetatable({}, {
            __index = function(_, at) return get(pane.view, name, def, index(def[4], at, name)) end,
            __newindex = function(_, at, value) set(pane.view, name, def, index(def[4], at, name), value) end,
        })
        bindings[name] = binding
    end
    return binding
end

function Pane.__index(pane, name)
    local found = method(name)
    if found then return found end
    local def = properties[name]
    if def then
        if def[4] then return indexed(pane, name, def) end
        return get(pane.view, name, def, 0)
    end
    if blocked[name] then error(("%s is not available to scripts"):format(name), 2) end
    error(("the editor has no function or property called '%s'"):format(tostring(name)), 2)
end

function Pane.__newindex(pane, name, value)
    local def = properties[name]
    if not def then error(("the editor has no property called '%s'"):format(tostring(name)), 2) end
    if def[4] then error(("the property %s needs an index: editor.%s[n] = value"):format(name, name), 2) end
    set(pane.view, name, def, nil, value)
end

local function pane(view)
    return setmetatable({ view = view, bindings = {} }, Pane)
end

editor1, editor2 = pane(0), pane(1)
-- The editor with the caret. The second view is for reading, so this is always the first.
editor = editor1

-- Helpers that SciTE and LuaScript add to the Scintilla functions.

function methods.textrange(pane, from, to)
    return range(pane.view, whole(from, 1, "textrange"), whole(to, 2, "textrange"))
end

function methods.findtext(pane, what, flags, from, to)
    local view = pane.view
    from = from and whole(from, 3, "findtext") or 0
    to = to and whole(to, 4, "findtext") or send(view, functions.GetLength[1], 0, 0)
    return find(view, flags and whole(flags, 2, "findtext") or 0, text(what, 1, "findtext"), from, to)
end

function methods.append(pane, value)
    value = text(value, 1, "append")
    send(pane.view, functions.AppendText[1], #value, value)
end

function methods.insert(pane, at, value)
    send(pane.view, functions.InsertText[1], whole(at, 1, "insert"), text(value, 2, "insert"))
end

function methods.remove(pane, from, to)
    from, to = whole(from, 1, "remove"), whole(to, 2, "remove")
    send(pane.view, functions.DeleteRange[1], from, to - from)
end

-- for m in editor:match(text, flags, from) do ... end, with m.pos, m.len, m.text, m.style and m:replace(new).
function methods.match(pane, what, flags, from)
    local view = pane.view
    what = text(what, 1, "match")
    flags = flags and whole(flags, 2, "match") or 0
    local at = from and whole(from, 3, "match") or 0
    local Match = {}
    function Match.__index(m, key)
        if key == "text" then return range(view, m.pos, m.pos + m.len) end
        if key == "style" then return send(view, functions.GetStyleAt[1], m.pos, 0) end
        if key == "replace" then
            return function(self, with)
                with = text(with, 1, "replace")
                send(view, functions.SetTargetRange[1], self.pos, self.pos + self.len)
                send(view, functions.ReplaceTarget[1], #with, with)
                -- The search goes on after what was put in.
                at = self.pos + #with
                self.len = #with
            end
        end
    end
    function Match.__tostring(m)
        return ("match{pos=%d,len=%d}"):format(m.pos, m.len)
    end
    return function()
        local from, to = find(view, flags, what, at, send(view, functions.GetLength[1], 0, 0))
        if not from then return nil end
        -- An empty match must not be found again and again.
        at = to > from and to or to + 1
        return setmetatable({ pos = from, len = to - from }, Match)
    end
end

-- The application -----------------------------------------------------------------------------------

local EVENTS = {
    "OnReady", "OnOpen", "OnBeforeSave", "OnSave", "OnClose", "OnSwitchFile", "OnLangChange", "OnFileRenamed",
    "OnChar", "OnUpdateUI", "OnSavePointReached", "OnSavePointLeft", "OnModifyAttemptRO", "OnDoubleClick",
    "OnMarginClick", "OnZoom",
}
-- Events LuaScript has that are never sent here; registering for one is allowed and says so once.
local QUIET = {
    "OnStyle", "OnModification", "OnNeedShown", "OnPainted", "OnUserListSelection", "OnDwellStart", "OnDwellEnd",
    "OnHotSpotClick", "OnHotSpotDoubleClick", "OnHotSpotReleaseClick", "OnIndicatorClick", "OnIndicatorRelease",
    "OnCallTipClick", "OnAutoCSelection", "OnAutoCCancelled", "OnAutoCCharDeleted", "OnFocusIn", "OnFocusOut",
    "OnToolBarModification", "OnFileBeforeLoad", "OnFileLoadFailed", "OnBeforeOpen", "OnBeforeClose",
    "OnReadOnlyChanged", "OnDocOrderChanged", "OnSnapshotDirtyFileLoaded", "OnFileBeforeRename",
    "OnFileRenameCancel", "OnFileBeforeDelete", "OnFileDeleteFailed", "OnFileDeleted", "OnBeforeShutdown",
    "OnCancelShutdown", "OnShutdown",
}
local known = {}
for _, name in ipairs(EVENTS) do known[name] = true end
for _, name in ipairs(QUIET) do known[name] = false end

local handlers, shortcuts, timers = {}, {}, {}
local nextTimer = 0

local Npp = {}
-- LuaScript documents some functions with a dot and some with a colon; both work for all of them here.
local function entry(f)
    return function(first, ...)
        if first == npp then return f(...) end
        return f(first, ...)
    end
end

local function simple(request, ...)
    local fixed = pack(...)
    return function(...)
        if fixed.n == 0 then return call(request, ...) end
        local args = pack(...)
        local all = { unpack(fixed, 1, fixed.n) }
        for i = 1, args.n do all[fixed.n + i] = args[i] end
        return call(request, unpack(all, 1, fixed.n + args.n))
    end
end

Npp.GetFileName = simple("info", "name")
Npp.GetFullCurrentPath = simple("info", "path")
Npp.GetCurrentDirectory = simple("info", "directory")
Npp.GetNamePart = simple("info", "namePart")
Npp.GetExtPart = simple("info", "extension")
Npp.GetCurrentWord = simple("info", "word")
Npp.GetNppDirectory = simple("info", "appDirectory")
Npp.GetPluginsConfigDir = simple("info", "scripts")
Npp.GetNbOpenFiles = simple("info", "count")
Npp.GetCurrentDocIndex = simple("info", "index")
Npp.GetFullPathFromBufferID = simple("info", "pathOf")
Npp.GetBufferIDFromPos = simple("info", "idAt")
Npp.DoOpen = simple("open")
Npp.SwitchToFile = simple("switch")
Npp.ActivateDoc = simple("activate")
Npp.SaveCurrentFile = simple("save")
Npp.SaveAllFiles = simple("saveAll")
-- Additions of Pocketpad++'s own.
Npp.GetLanguage = simple("info", "language")
Npp.SetLanguage = simple("language")
Npp.Toast = simple("toast")

-- A Notepad++ command id (IDM_EDIT_JOIN_LINES), or the command's place in the menus ("Edit > Line Operations > Join Lines").
function Npp.MenuCommand(command)
    if type(command) ~= "number" and type(command) ~= "string" then
        error("MenuCommand: give an IDM_ constant or a menu path such as \"Edit > Line Operations > Join Lines\"", 3)
    end
    return call("command", command)
end

local function watch(event)
    local list = handlers[event]
    call("watch", event, list ~= nil and #list > 0)
end

local function addHandler(event, callback)
    if type(callback) ~= "function" then error("AddEventHandler: the second argument must be a function", 4) end
    if known[event] == nil then error(("AddEventHandler: unrecognized event name '%s'"):format(tostring(event)), 4) end
    if known[event] == false then
        known[event] = 0
        call("print", ("Note: Pocketpad++ does not send %s; the handler will not be called."):format(event), true)
    end
    local list = handlers[event] or {}
    handlers[event] = list
    list[#list + 1] = callback
    watch(event)
end

function Npp.AddEventHandler(event, callback)
    if type(event) == "table" then
        for _, name in ipairs(event) do addHandler(name, callback) end
    else
        addHandler(event, callback)
    end
    return true
end

function Npp.RemoveEventHandler(event, callback)
    local list = handlers[event]
    if not list then return false end
    for i, registered in ipairs(list) do
        if registered == callback then
            table.remove(list, i)
            watch(event)
            return true
        end
    end
    return false
end

function Npp.RemoveAllEventHandlers(event)
    handlers[event] = nil
    watch(event)
end

function Npp.AddShortcut(name, shortcut, callback)
    if type(name) ~= "string" or name == "" then error("AddShortcut: the first argument is the name shown in the menu", 3) end
    if type(callback) ~= "function" then error("AddShortcut: the third argument must be a function", 3) end
    shortcuts[name] = callback
    call("shortcut", name, type(shortcut) == "string" and shortcut or "")
end

function Npp.StartTimer(milliseconds, callback)
    milliseconds = whole(milliseconds, 1, "StartTimer")
    if milliseconds < 1 then error("StartTimer: the interval must be at least one millisecond", 3) end
    if type(callback) ~= "function" then error("StartTimer: the second argument must be a function", 3) end
    nextTimer = nextTimer + 1
    local timer = { id = nextTimer, callback = callback }
    timers[timer.id] = timer
    call("timer", timer.id, milliseconds)
    return timer
end

function Npp.StopTimer(timer)
    if type(timer) == "table" and timers[timer.id] == timer then
        timers[timer.id] = nil
        call("stopTimer", timer.id)
    end
end

function Npp.WriteError(...)
    local parts = pack(...)
    for i = 1, parts.n do parts[i] = tostring(parts[i]) end
    call("print", table.concat(parts, "\t", 1, parts.n), true)
end

function Npp.ClearConsole()
    call("clear")
end

-- npp.SendEditor(SCI_GETLENGTH): the same functions, for those who know Scintilla by its message names.
local byMessage
function Npp.SendEditor(message, ...)
    message = whole(message, 1, "SendEditor")
    if not byMessage then
        byMessage = {}
        for name, def in pairs(functions) do byMessage[def[1]] = name end
    end
    local name = byMessage[message]
    if not name then
        error("SendEditor: no Scintilla function a script can call has that message number", 3)
    end
    return method(name)(editor, ...)
end

function Npp.ConstantName(value, hint)
    value = whole(value, 1, "ConstantName")
    constant("")
    local best
    for name, number in pairs(constants) do
        if number == value and (not hint or name:sub(1, #hint) == hint) and (not best or name < best) then best = name end
    end
    if not best then error("ConstantName: the number does not match any constant", 3) end
    return best
end

local values = {
    Version = function() return call("info", "version") end,
    CurrentBufferID = function() return call("info", "id") end,
    CurrentLine = function() return send(0, functions.LineFromPosition[1], send(0, functions.GetCurrentPos[1], 0, 0), 0) end,
    CurrentColumn = function() return send(0, functions.GetColumn[1], send(0, functions.GetCurrentPos[1], 0, 0), 0) end,
    CurrentView = function() return MAIN_VIEW end,
    DefaultForegroundColor = function() return send(0, functions.StyleGetFore[1], 32, 0) end,
    DefaultBackgroundColor = function() return send(0, functions.StyleGetBack[1], 32, 0) end,
}

-- npp.BufferLangType[id]: values that are asked for by an index.
local function byIndex(name, read, write)
    return setmetatable({}, {
        __index = function(_, at) return read(at) end,
        __newindex = function(_, at, value)
            if not write then error(("npp.%s can only be read"):format(name), 2) end
            write(at, value)
        end,
    })
end
local tables = {
    BufferLangType = byIndex("BufferLangType",
        function(id) return call("info", "langType", id) end,
        function(id, language) call("langType", id, whole(language, 2, "BufferLangType")) end),
    LanguageName = byIndex("LanguageName", function(language) return call("info", "languageName", language) end),
    LanguageDescription = byIndex("LanguageDescription", function(language) return call("info", "languageDescription", language) end),
}

for name, f in pairs(Npp) do Npp[name] = entry(f) end

npp = setmetatable({}, {
    __index = function(_, name)
        local f = Npp[name]
        if f then return f end
        local value = values[name]
        if value then return value() end
        if tables[name] then return tables[name] end
        error(("npp.%s is not available in Pocketpad++"):format(tostring(name)), 2)
    end,
    __newindex = function(_, name)
        error(("npp.%s cannot be set in Pocketpad++"):format(tostring(name)), 2)
    end,
})

-- Calls from the application ------------------------------------------------------------------------

local function protected(what, f, ...)
    local results = pack(xpcall(f, function(message) return tostring(message) end, ...))
    if not results[1] then
        call("print", what .. ": " .. tostring(results[2]), true)
        return nil
    end
    return unpack(results, 2, results.n)
end

local dispatch = {}

function dispatch.event(name, ...)
    local list = handlers[name]
    if not list then return end
    -- A copy, so that a handler may remove itself.
    for _, handler in ipairs({ unpack(list) }) do
        if protected(name, handler, ...) == true then break end
    end
end

function dispatch.shortcut(name)
    local callback = shortcuts[name]
    if callback then callback() end
end

function dispatch.timer(id)
    local timer = timers[id]
    if timer then protected("Timer", timer.callback, timer) end
end

-- A line typed in the console: a value to show, or a statement to run.
function dispatch.eval(code)
    if code:sub(1, 1) == "=" then code = code:sub(2) end
    local chunk = load("return " .. code, "=console", "t")
    local problem
    if not chunk then chunk, problem = load(code, "=console", "t") end
    if not chunk then error(problem, 0) end
    local results = pack(chunk())
    if results.n > 0 then print(unpack(results, 1, results.n)) end
end

function __dispatch(what, ...)
    local f = dispatch[what]
    if f then return f(...) end
end
