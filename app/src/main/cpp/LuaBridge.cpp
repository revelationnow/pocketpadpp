// Pocketpad++ - Lua scripting.
// JNI entry points for io.github.revelationnow.pocketpadpp.scintilla.LuaNative, and the handful of native
// functions the scripting layer (assets/lua/prelude.lua) is built from. Lua itself is compiled as C++, so
// its errors are C++ exceptions and unwind this code properly.

#include "SciHeaders.h"
#include "Bridge.h"
#include "PlatAndroid.h"
#include "ScintillaAndroid.h"

#include <chrono>

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

using namespace Scintilla;
using namespace Scintilla::Internal;
using namespace pocketpad;

namespace {

#define PKG "io/github/revelationnow/pocketpadpp/scintilla/"

using Clock = std::chrono::steady_clock;

/** How many Lua instructions pass between two looks at the clock. */
constexpr int HOOK_EVERY = 20000;

struct LuaState {
	lua_State *L = nullptr;
	jobject host = nullptr;
	ScintillaAndroid *views[2] = { nullptr, nullptr };
	// A script that never ends or grows without bound must not take the app with it.
	size_t allocated = 0;
	size_t memoryLimit = static_cast<size_t>(192) * 1024 * 1024;
	int timeLimitMs = 5000;
	Clock::time_point deadline;
	int depth = 0;
	bool destroyWhenIdle = false;
};

struct JavaIds {
	jmethodID hostCall {};
	jclass objectClass {};
	jclass stringClass {};
	jclass numberClass {};
	jclass doubleClass {};
	jclass floatClass {};
	jclass longClass {};
	jclass booleanClass {};
	jclass objectArrayClass {};
	jmethodID numberLong {};
	jmethodID numberDouble {};
	jmethodID booleanValue {};
	jmethodID longOf {};
	jmethodID doubleOf {};
	jmethodID booleanOf {};
	jmethodID throwableMessage {};
	jmethodID throwableString {};
	bool ready = false;
};

JavaIds g_lua;

jclass GlobalClass(JNIEnv *env, const char *name) {
	const Local<jclass> local(env, env->FindClass(name));
	return local ? static_cast<jclass>(env->NewGlobalRef(local.get())) : nullptr;
}

bool PrepareJava(JNIEnv *env) {
	if (g_lua.ready) {
		return true;
	}
	const Local<jclass> host(env, env->FindClass(PKG "LuaHost"));
	const Local<jclass> throwable(env, env->FindClass("java/lang/Throwable"));
	g_lua.objectClass = GlobalClass(env, "java/lang/Object");
	g_lua.stringClass = GlobalClass(env, "java/lang/String");
	g_lua.numberClass = GlobalClass(env, "java/lang/Number");
	g_lua.doubleClass = GlobalClass(env, "java/lang/Double");
	g_lua.floatClass = GlobalClass(env, "java/lang/Float");
	g_lua.longClass = GlobalClass(env, "java/lang/Long");
	g_lua.booleanClass = GlobalClass(env, "java/lang/Boolean");
	g_lua.objectArrayClass = GlobalClass(env, "[Ljava/lang/Object;");
	if (!host || !throwable || !g_lua.objectClass || !g_lua.stringClass || !g_lua.numberClass || !g_lua.doubleClass ||
		!g_lua.floatClass || !g_lua.longClass || !g_lua.booleanClass || !g_lua.objectArrayClass) {
		ClearException(env);
		return false;
	}
	g_lua.hostCall = env->GetMethodID(host.get(), "call", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;");
	g_lua.numberLong = env->GetMethodID(g_lua.numberClass, "longValue", "()J");
	g_lua.numberDouble = env->GetMethodID(g_lua.numberClass, "doubleValue", "()D");
	g_lua.booleanValue = env->GetMethodID(g_lua.booleanClass, "booleanValue", "()Z");
	g_lua.longOf = env->GetStaticMethodID(g_lua.longClass, "valueOf", "(J)Ljava/lang/Long;");
	g_lua.doubleOf = env->GetStaticMethodID(g_lua.doubleClass, "valueOf", "(D)Ljava/lang/Double;");
	g_lua.booleanOf = env->GetStaticMethodID(g_lua.booleanClass, "valueOf", "(Z)Ljava/lang/Boolean;");
	g_lua.throwableMessage = env->GetMethodID(throwable.get(), "getMessage", "()Ljava/lang/String;");
	g_lua.throwableString = env->GetMethodID(throwable.get(), "toString", "()Ljava/lang/String;");
	g_lua.ready = g_lua.hostCall && g_lua.numberLong && g_lua.numberDouble && g_lua.booleanValue && g_lua.longOf &&
		g_lua.doubleOf && g_lua.booleanOf && g_lua.throwableMessage && g_lua.throwableString;
	if (!g_lua.ready) {
		ClearException(env);
	}
	return g_lua.ready;
}

LuaState *StateOf(lua_State *L) noexcept {
	return *static_cast<LuaState **>(lua_getextraspace(L));
}

void *Allocate(void *ud, void *ptr, size_t osize, size_t nsize) {
	LuaState *state = static_cast<LuaState *>(ud);
	const size_t old = ptr ? osize : 0;
	if (nsize == 0) {
		state->allocated -= std::min(old, state->allocated);
		free(ptr);
		return nullptr;
	}
	if (nsize > old && state->allocated + (nsize - old) > state->memoryLimit) {
		return nullptr;
	}
	void *fresh = realloc(ptr, nsize);
	if (fresh) {
		state->allocated = state->allocated - std::min(old, state->allocated) + nsize;
	}
	return fresh;
}

/** Runs every so many instructions; ends a script that has used up its time. */
void TimeHook(lua_State *L, lua_Debug *) {
	LuaState *state = StateOf(L);
	if (state->depth > 0 && state->timeLimitMs > 0 && Clock::now() > state->deadline) {
		// From here on every instruction ends up here, so that a script which catches the error and
		// carries on is stopped again at once, level after level, until it is out.
		lua_sethook(L, TimeHook, LUA_MASKCOUNT, 1);
		// Said without a place in the script: where it happened to be when time ran out means nothing.
		const int seconds = state->timeLimitMs / 1000;
		if (seconds <= 1) {
			lua_pushliteral(L, "the script was stopped: it ran for more than a second");
		} else {
			lua_pushfstring(L, "the script was stopped: it ran for more than %d seconds", seconds);
		}
		lua_error(L);
	}
}

// --- Values between Lua and Java ---

/** New local reference for the Lua value at the index: null, Boolean, Long, Double or String. */
jobject ToJava(JNIEnv *env, lua_State *L, int index) {
	switch (lua_type(L, index)) {
	case LUA_TBOOLEAN:
		return env->CallStaticObjectMethod(g_lua.booleanClass, g_lua.booleanOf, static_cast<jboolean>(lua_toboolean(L, index)));
	case LUA_TNUMBER:
		if (lua_isinteger(L, index)) {
			return env->CallStaticObjectMethod(g_lua.longClass, g_lua.longOf, static_cast<jlong>(lua_tointeger(L, index)));
		}
		return env->CallStaticObjectMethod(g_lua.doubleClass, g_lua.doubleOf, static_cast<jdouble>(lua_tonumber(L, index)));
	case LUA_TSTRING: {
		size_t length = 0;
		const char *text = lua_tolstring(L, index, &length);
		return NewJString(env, std::string_view(text, length));
	}
	default:
		return nullptr;
	}
}

/** Pushes a value that came from Java. Arrays become tables counted from 1. */
void PushJava(JNIEnv *env, lua_State *L, jobject value, int nesting = 0) {
	if (!value) {
		lua_pushnil(L);
	} else if (env->IsInstanceOf(value, g_lua.stringClass)) {
		const std::string text = Utf8FromJString(env, static_cast<jstring>(value));
		lua_pushlstring(L, text.data(), text.size());
	} else if (env->IsInstanceOf(value, g_lua.booleanClass)) {
		lua_pushboolean(L, env->CallBooleanMethod(value, g_lua.booleanValue));
	} else if (env->IsInstanceOf(value, g_lua.doubleClass) || env->IsInstanceOf(value, g_lua.floatClass)) {
		lua_pushnumber(L, static_cast<lua_Number>(env->CallDoubleMethod(value, g_lua.numberDouble)));
	} else if (env->IsInstanceOf(value, g_lua.numberClass)) {
		lua_pushinteger(L, static_cast<lua_Integer>(env->CallLongMethod(value, g_lua.numberLong)));
	} else if (env->IsInstanceOf(value, g_lua.objectArrayClass) && nesting < 4) {
		const jobjectArray array = static_cast<jobjectArray>(value);
		const jsize count = env->GetArrayLength(array);
		lua_createtable(L, count, 0);
		for (jsize i = 0; i < count; i++) {
			const Local<jobject> item(env, env->GetObjectArrayElement(array, i));
			PushJava(env, L, item.get(), nesting + 1);
			lua_rawseti(L, -2, i + 1);
		}
	} else {
		lua_pushnil(L);
	}
}

/** The message of a pending Java exception, which is cleared; empty if there is none. */
std::string TakeException(JNIEnv *env) {
	if (!env->ExceptionCheck()) {
		return {};
	}
	const Local<jthrowable> thrown(env, env->ExceptionOccurred());
	env->ExceptionClear();
	std::string message;
	if (thrown) {
		Local<jstring> text(env, static_cast<jstring>(env->CallObjectMethod(thrown.get(), g_lua.throwableMessage)));
		if (env->ExceptionCheck()) {
			env->ExceptionClear();
		}
		if (text) {
			message = Utf8FromJString(env, text.get());
		} else {
			const Local<jstring> whole(env, static_cast<jstring>(env->CallObjectMethod(thrown.get(), g_lua.throwableString)));
			if (env->ExceptionCheck()) {
				env->ExceptionClear();
			}
			if (whole) {
				message = Utf8FromJString(env, whole.get());
			}
		}
	}
	return message.empty() ? std::string("the application could not do that") : message;
}

// --- The functions Lua sees as the table "host" ---

ScintillaAndroid *View(lua_State *L, int index) {
	const lua_Integer which = luaL_checkinteger(L, index);
	ScintillaAndroid *view = (which == 0 || which == 1) ? StateOf(L)->views[which] : nullptr;
	if (!view) {
		luaL_error(L, which == 1 ? "there is no second view: the screen is not split" : "there is no editor");
	}
	return view;
}

/** A message parameter: a number, a boolean, or a string passed as a pointer to its bytes. */
sptr_t Parameter(lua_State *L, int index) {
	switch (lua_type(L, index)) {
	case LUA_TNONE:
	case LUA_TNIL:
		return 0;
	case LUA_TBOOLEAN:
		return lua_toboolean(L, index);
	case LUA_TSTRING:
		return reinterpret_cast<sptr_t>(lua_tostring(L, index));
	default:
		return static_cast<sptr_t>(luaL_checkinteger(L, index));
	}
}

/** host.send(view, message, wParam, lParam): the number Scintilla answers. */
int HostSend(lua_State *L) {
	ScintillaAndroid *view = View(L, 1);
	const unsigned int message = static_cast<unsigned int>(luaL_checkinteger(L, 2));
	const sptr_t result = view->Send(message, static_cast<uptr_t>(Parameter(L, 3)), Parameter(L, 4));
	lua_pushinteger(L, static_cast<lua_Integer>(result));
	return 1;
}

/**
 * host.fetch(view, message, wParam, length): for messages that write text into a buffer. Returns the text
 * and the number Scintilla answered.
 */
int HostFetch(lua_State *L) {
	ScintillaAndroid *view = View(L, 1);
	const unsigned int message = static_cast<unsigned int>(luaL_checkinteger(L, 2));
	const uptr_t wParam = static_cast<uptr_t>(Parameter(L, 3));
	const lua_Integer wanted = luaL_checkinteger(L, 4);
	if (wanted <= 0) {
		lua_pushliteral(L, "");
		lua_pushinteger(L, 0);
		return 2;
	}
	size_t length = static_cast<size_t>(wanted);
	// Not every message ends its text with a zero, so there is always room for one.
	std::string buffer(length + 1, '\0');
	const sptr_t result = view->Send(message, wParam, reinterpret_cast<sptr_t>(buffer.data()));
	// Some messages count their closing zero in the length they ask for.
	if (buffer[length - 1] == '\0') {
		length--;
	}
	lua_pushlstring(L, buffer.data(), length);
	lua_pushinteger(L, static_cast<lua_Integer>(result));
	return 2;
}

/** host.range(view, start, end): the text between two positions. */
int HostRange(lua_State *L) {
	ScintillaAndroid *view = View(L, 1);
	const std::string text = view->Range(static_cast<Sci::Position>(luaL_checkinteger(L, 2)), static_cast<Sci::Position>(luaL_checkinteger(L, 3)));
	lua_pushlstring(L, text.data(), text.size());
	return 1;
}

/** host.find(view, flags, text, start, end): where the text is found, as start and end, or nothing. */
int HostFind(lua_State *L) {
	ScintillaAndroid *view = View(L, 1);
	Sci_TextToFindFull find {};
	find.chrg.cpMin = static_cast<Sci_Position>(luaL_checkinteger(L, 4));
	find.chrg.cpMax = static_cast<Sci_Position>(luaL_checkinteger(L, 5));
	find.lpstrText = luaL_checkstring(L, 3);
	const sptr_t found = view->Send(SCI_FINDTEXTFULL, static_cast<uptr_t>(luaL_checkinteger(L, 2)), reinterpret_cast<sptr_t>(&find));
	if (found < 0) {
		return 0;
	}
	lua_pushinteger(L, static_cast<lua_Integer>(find.chrgText.cpMin));
	lua_pushinteger(L, static_cast<lua_Integer>(find.chrgText.cpMax));
	return 2;
}

/** host.call(name, ...): asks the application; what it answers is returned. */
int HostCall(lua_State *L) {
	LuaState *state = StateOf(L);
	JNIEnv *env = Env();
	if (!env || !state->host) {
		return luaL_error(L, "the application is not listening");
	}
	size_t nameLength = 0;
	const char *name = luaL_checklstring(L, 1, &nameLength);
	const int count = lua_gettop(L) - 1;
	std::string failure;
	{
		const Local<jstring> jname(env, NewJString(env, std::string_view(name, nameLength)));
		const Local<jobjectArray> args(env, env->NewObjectArray(count, g_lua.objectClass, nullptr));
		if (!jname || !args) {
			failure = TakeException(env);
		} else {
			for (int i = 0; i < count; i++) {
				const Local<jobject> item(env, ToJava(env, L, i + 2));
				env->SetObjectArrayElement(args.get(), i, item.get());
			}
			const Local<jobject> result(env, env->CallObjectMethod(state->host, g_lua.hostCall, jname.get(), args.get()));
			if (env->ExceptionCheck()) {
				failure = TakeException(env);
			} else {
				PushJava(env, L, result.get());
				return 1;
			}
		}
	}
	return luaL_error(L, "%s", failure.c_str());
}

/** host.milliseconds(): a clock that only goes forward, for timing. */
int HostMilliseconds(lua_State *L) {
	const auto now = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now().time_since_epoch()).count();
	lua_pushinteger(L, static_cast<lua_Integer>(now));
	return 1;
}

const luaL_Reg hostFunctions[] = {
	{ "send", HostSend },
	{ "fetch", HostFetch },
	{ "range", HostRange },
	{ "find", HostFind },
	{ "call", HostCall },
	{ "milliseconds", HostMilliseconds },
	{ nullptr, nullptr },
};

// --- Calls into Lua ---

void Destroy(JNIEnv *env, LuaState *state) {
	if (state->L) {
		lua_close(state->L);
	}
	if (state->host) {
		env->DeleteGlobalRef(state->host);
	}
	delete state;
}

/** Turns whatever was raised into a message with the place it happened. */
int MessageHandler(lua_State *L) {
	if (!lua_isstring(L, 1)) {
		luaL_tolstring(L, 1, nullptr);
	}
	return 1;
}

/**
 * Calls the function on the stack below its nargs arguments. Returns null, or the error as a Java string.
 * The time allowed is counted from the outermost call.
 */
jstring Call(JNIEnv *env, LuaState *state, int nargs) {
	lua_State *L = state->L;
	const int base = lua_gettop(L) - nargs;
	lua_pushcfunction(L, MessageHandler);
	lua_insert(L, base);
	if (state->depth == 0) {
		state->deadline = Clock::now() + std::chrono::milliseconds(state->timeLimitMs);
		lua_sethook(L, TimeHook, LUA_MASKCOUNT, HOOK_EVERY);
	}
	state->depth++;
	const int status = lua_pcall(L, nargs, 0, base);
	state->depth--;
	jstring error = nullptr;
	if (status != LUA_OK) {
		size_t length = 0;
		const char *message = lua_tolstring(L, -1, &length);
		error = NewJString(env, message ? std::string_view(message, length) : std::string_view("the script failed"));
		lua_pop(L, 1);
	}
	lua_remove(L, base);
	if (state->depth == 0 && state->destroyWhenIdle) {
		Destroy(env, state);
	}
	return error;
}

LuaState *State(jlong ptr) noexcept {
	return reinterpret_cast<LuaState *>(ptr);
}

jlong JNICALL NativeCreate(JNIEnv *env, jclass, jobject host) {
	if (!PrepareJava(env)) {
		return 0;
	}
	LuaState *state = new LuaState();
	lua_State *L = lua_newstate(Allocate, state);
	if (!L) {
		delete state;
		return 0;
	}
	state->L = L;
	*static_cast<LuaState **>(lua_getextraspace(L)) = state;
	state->host = host ? env->NewGlobalRef(host) : nullptr;
	// Setting up can run out of memory like anything else in Lua, so it happens inside a protected call.
	lua_pushcfunction(L, [](lua_State *inner) -> int {
		luaL_openlibs(inner);
		luaL_newlib(inner, hostFunctions);
		lua_setglobal(inner, "host");
		return 0;
	});
	if (lua_pcall(L, 0, 0, 0) != LUA_OK) {
		Destroy(env, state);
		return 0;
	}
	lua_sethook(L, TimeHook, LUA_MASKCOUNT, HOOK_EVERY);
	return reinterpret_cast<jlong>(state);
}

void JNICALL NativeDestroy(JNIEnv *env, jclass, jlong ptr) {
	LuaState *state = State(ptr);
	if (!state) {
		return;
	}
	if (state->depth > 0) {
		state->destroyWhenIdle = true;
	} else {
		Destroy(env, state);
	}
}

void JNICALL NativeSetEditors(JNIEnv *, jclass, jlong ptr, jlong main, jlong other) {
	if (LuaState *state = State(ptr)) {
		state->views[0] = reinterpret_cast<ScintillaAndroid *>(main);
		state->views[1] = reinterpret_cast<ScintillaAndroid *>(other);
	}
}

void JNICALL NativeSetTimeLimit(JNIEnv *, jclass, jlong ptr, jint millis) {
	if (LuaState *state = State(ptr)) {
		state->timeLimitMs = millis;
	}
}

jstring JNICALL NativeRun(JNIEnv *env, jclass, jlong ptr, jstring chunkName, jbyteArray source) {
	LuaState *state = State(ptr);
	if (!state || !source) {
		return NewJString(env, "Lua is not running");
	}
	const std::string name = Utf8FromJString(env, chunkName);
	const jsize length = env->GetArrayLength(source);
	std::string code(static_cast<size_t>(length), '\0');
	if (length > 0) {
		env->GetByteArrayRegion(source, 0, length, reinterpret_cast<jbyte *>(code.data()));
	}
	lua_State *L = state->L;
	// Text only: compiled Lua is not checked when loaded and could do anything.
	if (luaL_loadbufferx(L, code.data(), code.size(), name.c_str(), "t") != LUA_OK) {
		size_t messageLength = 0;
		const char *message = lua_tolstring(L, -1, &messageLength);
		const jstring error = NewJString(env, message ? std::string_view(message, messageLength) : std::string_view("the script could not be read"));
		lua_pop(L, 1);
		return error;
	}
	return Call(env, state, 0);
}

jstring JNICALL NativeDispatch(JNIEnv *env, jclass, jlong ptr, jstring what, jobjectArray args) {
	LuaState *state = State(ptr);
	if (!state) {
		return NewJString(env, "Lua is not running");
	}
	lua_State *L = state->L;
	const jsize count = args ? env->GetArrayLength(args) : 0;
	if (!lua_checkstack(L, count + 8)) {
		return NewJString(env, "too many values for Lua");
	}
	if (lua_getglobal(L, "__dispatch") != LUA_TFUNCTION) {
		lua_pop(L, 1);
		return NewJString(env, "the scripting layer is not set up");
	}
	// Pushing values can fail for lack of memory, so that too happens inside a protected call.
	struct Pending {
		JNIEnv *env;
		jstring what;
		jobjectArray args;
		jsize count;
	} pending { env, what, args, count };
	lua_pushcfunction(L, [](lua_State *inner) -> int {
		const Pending *p = static_cast<const Pending *>(lua_touserdata(inner, 1));
		const std::string name = Utf8FromJString(p->env, p->what);
		lua_pushlstring(inner, name.data(), name.size());
		for (jsize i = 0; i < p->count; i++) {
			const Local<jobject> item(p->env, p->env->GetObjectArrayElement(p->args, i));
			PushJava(p->env, inner, item.get());
		}
		return static_cast<int>(p->count) + 1;
	});
	lua_pushlightuserdata(L, &pending);
	if (lua_pcall(L, 1, count + 1, 0) != LUA_OK) {
		lua_pop(L, 2);
		return NewJString(env, "not enough memory for Lua");
	}
	return Call(env, state, count + 1);
}

const JNINativeMethod luaMethods[] = {
	{ const_cast<char *>("create"), const_cast<char *>("(L" PKG "LuaHost;)J"), reinterpret_cast<void *>(NativeCreate) },
	{ const_cast<char *>("destroy"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(NativeDestroy) },
	{ const_cast<char *>("setEditors"), const_cast<char *>("(JJJ)V"), reinterpret_cast<void *>(NativeSetEditors) },
	{ const_cast<char *>("setTimeLimit"), const_cast<char *>("(JI)V"), reinterpret_cast<void *>(NativeSetTimeLimit) },
	{ const_cast<char *>("run"), const_cast<char *>("(JLjava/lang/String;[B)Ljava/lang/String;"), reinterpret_cast<void *>(NativeRun) },
	{ const_cast<char *>("dispatch"), const_cast<char *>("(JLjava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"), reinterpret_cast<void *>(NativeDispatch) },
};

}

namespace pocketpad {

/** Called from JNI_OnLoad. */
bool RegisterLua(JNIEnv *env) {
	const Local<jclass> cls(env, env->FindClass(PKG "LuaNative"));
	if (!cls) {
		ClearException(env);
		Log("LuaNative class not found");
		return false;
	}
	return env->RegisterNatives(cls.get(), luaMethods, static_cast<jint>(std::size(luaMethods))) == JNI_OK;
}

}
