// Pocketpad++ - Scintilla platform layer for Android.
// JNI entry points for io.github.revelationnow.pocketpadpp.scintilla.ScintillaNative.

#include "SciHeaders.h"
#include "Bridge.h"
#include "PlatAndroid.h"
#include "ScintillaAndroid.h"

using namespace Scintilla;
using namespace Scintilla::Internal;
using namespace pocketpad;

namespace {

#define PKG "io/github/revelationnow/pocketpadpp/scintilla/"

ScintillaAndroid *Sci(jlong ptr) noexcept {
	return reinterpret_cast<ScintillaAndroid *>(ptr);
}

/** C++ exceptions must not cross the JNI boundary. */
template <typename Fn>
void Guard(const char *where, Fn &&fn) noexcept {
	try {
		fn();
	} catch (const std::exception &e) {
		Log("Scintilla exception in %s: %s", where, e.what());
	} catch (...) {
		Log("Scintilla exception in %s", where);
	}
}

std::string BytesFromArray(JNIEnv *env, jbyteArray arr) {
	std::string out;
	if (arr) {
		const jsize len = env->GetArrayLength(arr);
		out.resize(static_cast<size_t>(len));
		if (len > 0) {
			env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(out.data()));
		}
	}
	return out;
}

void JNICALL NativeInit(JNIEnv *env, jclass, jobject platform, jfloat pixelsPerPoint, jfloat doubleTapSlop) {
	if (g_platform) {
		env->DeleteGlobalRef(g_platform);
		g_platform = nullptr;
	}
	if (platform) {
		g_platform = env->NewGlobalRef(platform);
	}
	g_pixelsPerPoint = (pixelsPerPoint > 0) ? pixelsPerPoint : 1.0f;
	g_doubleTapSlop = (doubleTapSlop > 0) ? doubleTapSlop : 24.0f;
}

jlong JNICALL NativeCreate(JNIEnv *env, jclass, jobject host) {
	ScintillaAndroid *sci = nullptr;
	Guard("create", [&] { sci = new ScintillaAndroid(env, host); });
	return reinterpret_cast<jlong>(sci);
}

void JNICALL NativeDestroy(JNIEnv *, jclass, jlong ptr) {
	Guard("destroy", [&] { delete Sci(ptr); });
}

jlong JNICALL NativeSend(JNIEnv *, jclass, jlong ptr, jint msg, jlong wParam, jlong lParam) {
	return static_cast<jlong>(Sci(ptr)->Send(static_cast<unsigned int>(msg), static_cast<uptr_t>(wParam), static_cast<sptr_t>(lParam)));
}

jlong JNICALL NativeSendString(JNIEnv *env, jclass, jlong ptr, jint msg, jlong wParam, jbyteArray text) {
	const std::string s = BytesFromArray(env, text);
	return static_cast<jlong>(Sci(ptr)->Send(static_cast<unsigned int>(msg), static_cast<uptr_t>(wParam),
		reinterpret_cast<sptr_t>(s.c_str())));
}

jlong JNICALL NativeSendStrings(JNIEnv *env, jclass, jlong ptr, jint msg, jbyteArray w, jbyteArray l) {
	const std::string sw = BytesFromArray(env, w);
	const std::string sl = BytesFromArray(env, l);
	return static_cast<jlong>(Sci(ptr)->Send(static_cast<unsigned int>(msg), reinterpret_cast<uptr_t>(sw.c_str()),
		reinterpret_cast<sptr_t>(sl.c_str())));
}

jbyteArray JNICALL NativeGetRange(JNIEnv *env, jclass, jlong ptr, jlong start, jlong end) {
	std::string text;
	Guard("getRange", [&] { text = Sci(ptr)->Range(static_cast<Sci::Position>(start), static_cast<Sci::Position>(end)); });
	jbyteArray arr = env->NewByteArray(static_cast<jsize>(text.size()));
	if (arr && !text.empty()) {
		env->SetByteArrayRegion(arr, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
	}
	return arr;
}

jboolean JNICALL NativeSetLexer(JNIEnv *env, jclass, jlong ptr, jstring name) {
	const std::string lexerName = Utf8FromJString(env, name);
	bool found = false;
	Guard("setLexer", [&] { found = Sci(ptr)->SetLexerByName(lexerName.c_str()); });
	return static_cast<jboolean>(found);
}

void JNICALL NativeResize(JNIEnv *, jclass, jlong ptr, jint width, jint height) {
	Guard("resize", [&] { Sci(ptr)->Resize(width, height); });
}

void JNICALL NativePaint(JNIEnv *, jclass, jlong ptr, jobject surface) {
	Guard("paint", [&] { Sci(ptr)->PaintTo(surface); });
}

void JNICALL NativeSetFocus(JNIEnv *, jclass, jlong ptr, jboolean focus) {
	Guard("setFocus", [&] { Sci(ptr)->Focus(focus != JNI_FALSE); });
}

void JNICALL NativeMouseDown(JNIEnv *, jclass, jlong ptr, jfloat x, jfloat y, jlong timeMs, jint modifiers) {
	Guard("mouseDown", [&] { Sci(ptr)->MouseDown(Point(x, y), static_cast<unsigned int>(timeMs), modifiers); });
}

void JNICALL NativeMouseMove(JNIEnv *, jclass, jlong ptr, jfloat x, jfloat y, jlong timeMs, jint modifiers) {
	Guard("mouseMove", [&] { Sci(ptr)->MouseMove(Point(x, y), static_cast<unsigned int>(timeMs), modifiers); });
}

void JNICALL NativeMouseUp(JNIEnv *, jclass, jlong ptr, jfloat x, jfloat y, jlong timeMs, jint modifiers) {
	Guard("mouseUp", [&] { Sci(ptr)->MouseUp(Point(x, y), static_cast<unsigned int>(timeMs), modifiers); });
}

jboolean JNICALL NativeKeyDown(JNIEnv *, jclass, jlong ptr, jint key, jint modifiers) {
	bool consumed = false;
	Guard("keyDown", [&] { consumed = Sci(ptr)->Key(key, modifiers); });
	return static_cast<jboolean>(consumed);
}

void JNICALL NativeInsertText(JNIEnv *env, jclass, jlong ptr, jstring text) {
	const std::string utf8 = Utf8FromJString(env, text);
	Guard("insertText", [&] { Sci(ptr)->TypeText(utf8); });
}

void JNICALL NativeTick(JNIEnv *, jclass, jlong ptr, jint reason) {
	Guard("tick", [&] { Sci(ptr)->Tick(reason); });
}

jboolean JNICALL NativeIdle(JNIEnv *, jclass, jlong ptr) {
	bool more = false;
	Guard("idle", [&] { more = Sci(ptr)->DoIdle(); });
	return static_cast<jboolean>(more);
}

void JNICALL NativeIdleWork(JNIEnv *, jclass, jlong ptr) {
	Guard("idleWork", [&] { Sci(ptr)->DoIdleWork(); });
}

void JNICALL NativeImeSetComposingText(JNIEnv *env, jclass, jlong ptr, jstring text, jint newCursorPosition) {
	const std::string utf8 = Utf8FromJString(env, text);
	Guard("imeSetComposingText", [&] { Sci(ptr)->ImeSetComposingText(utf8, newCursorPosition); });
}

void JNICALL NativeImeCommitText(JNIEnv *env, jclass, jlong ptr, jstring text, jint newCursorPosition) {
	const std::string utf8 = Utf8FromJString(env, text);
	Guard("imeCommitText", [&] { Sci(ptr)->ImeCommitText(utf8, newCursorPosition); });
}

void JNICALL NativeImeFinishComposing(JNIEnv *, jclass, jlong ptr) {
	Guard("imeFinishComposing", [&] { Sci(ptr)->ImeFinishComposing(); });
}

void JNICALL NativeImeSetComposingRegion(JNIEnv *, jclass, jlong ptr, jint start, jint end) {
	Guard("imeSetComposingRegion", [&] { Sci(ptr)->ImeSetComposingRegion(start, end); });
}

void JNICALL NativeImeDeleteSurrounding(JNIEnv *, jclass, jlong ptr, jint before, jint after) {
	Guard("imeDeleteSurrounding", [&] { Sci(ptr)->ImeDeleteSurrounding(before, after); });
}

void JNICALL NativeImeSetSelection(JNIEnv *, jclass, jlong ptr, jint start, jint end) {
	Guard("imeSetSelection", [&] { Sci(ptr)->ImeSetSelection(start, end); });
}

jstring JNICALL NativeImeTextBefore(JNIEnv *env, jclass, jlong ptr, jint count) {
	std::string text;
	Guard("imeTextBefore", [&] { text = Sci(ptr)->ImeTextBefore(count); });
	return NewJString(env, text);
}

jstring JNICALL NativeImeTextAfter(JNIEnv *env, jclass, jlong ptr, jint count) {
	std::string text;
	Guard("imeTextAfter", [&] { text = Sci(ptr)->ImeTextAfter(count); });
	return NewJString(env, text);
}

jstring JNICALL NativeImeSelectedText(JNIEnv *env, jclass, jlong ptr) {
	std::string text;
	Guard("imeSelectedText", [&] { text = Sci(ptr)->ImeSelectedText(); });
	return NewJString(env, text);
}

void JNICALL NativeImeState(JNIEnv *env, jclass, jlong ptr, jintArray out) {
	if (!out || env->GetArrayLength(out) < 4) {
		return;
	}
	int state[4] = { 0, 0, -1, -1 };
	Guard("imeState", [&] { Sci(ptr)->ImeState(state); });
	const jint values[4] = { state[0], state[1], state[2], state[3] };
	env->SetIntArrayRegion(out, 0, 4, values);
}

const JNINativeMethod nativeMethods[] = {
	{ const_cast<char *>("init"), const_cast<char *>("(L" PKG "SciPlatform;FF)V"), reinterpret_cast<void *>(NativeInit) },
	{ const_cast<char *>("create"), const_cast<char *>("(L" PKG "SciHost;)J"), reinterpret_cast<void *>(NativeCreate) },
	{ const_cast<char *>("destroy"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(NativeDestroy) },
	{ const_cast<char *>("send"), const_cast<char *>("(JIJJ)J"), reinterpret_cast<void *>(NativeSend) },
	{ const_cast<char *>("sendString"), const_cast<char *>("(JIJ[B)J"), reinterpret_cast<void *>(NativeSendString) },
	{ const_cast<char *>("sendStrings"), const_cast<char *>("(JI[B[B)J"), reinterpret_cast<void *>(NativeSendStrings) },
	{ const_cast<char *>("getRange"), const_cast<char *>("(JJJ)[B"), reinterpret_cast<void *>(NativeGetRange) },
	{ const_cast<char *>("setLexer"), const_cast<char *>("(JLjava/lang/String;)Z"), reinterpret_cast<void *>(NativeSetLexer) },
	{ const_cast<char *>("resize"), const_cast<char *>("(JII)V"), reinterpret_cast<void *>(NativeResize) },
	{ const_cast<char *>("paint"), const_cast<char *>("(JL" PKG "SciSurface;)V"), reinterpret_cast<void *>(NativePaint) },
	{ const_cast<char *>("setFocus"), const_cast<char *>("(JZ)V"), reinterpret_cast<void *>(NativeSetFocus) },
	{ const_cast<char *>("mouseDown"), const_cast<char *>("(JFFJI)V"), reinterpret_cast<void *>(NativeMouseDown) },
	{ const_cast<char *>("mouseMove"), const_cast<char *>("(JFFJI)V"), reinterpret_cast<void *>(NativeMouseMove) },
	{ const_cast<char *>("mouseUp"), const_cast<char *>("(JFFJI)V"), reinterpret_cast<void *>(NativeMouseUp) },
	{ const_cast<char *>("keyDown"), const_cast<char *>("(JII)Z"), reinterpret_cast<void *>(NativeKeyDown) },
	{ const_cast<char *>("insertText"), const_cast<char *>("(JLjava/lang/String;)V"), reinterpret_cast<void *>(NativeInsertText) },
	{ const_cast<char *>("tick"), const_cast<char *>("(JI)V"), reinterpret_cast<void *>(NativeTick) },
	{ const_cast<char *>("idle"), const_cast<char *>("(J)Z"), reinterpret_cast<void *>(NativeIdle) },
	{ const_cast<char *>("idleWork"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(NativeIdleWork) },
	{ const_cast<char *>("imeSetComposingText"), const_cast<char *>("(JLjava/lang/String;I)V"), reinterpret_cast<void *>(NativeImeSetComposingText) },
	{ const_cast<char *>("imeCommitText"), const_cast<char *>("(JLjava/lang/String;I)V"), reinterpret_cast<void *>(NativeImeCommitText) },
	{ const_cast<char *>("imeFinishComposing"), const_cast<char *>("(J)V"), reinterpret_cast<void *>(NativeImeFinishComposing) },
	{ const_cast<char *>("imeSetComposingRegion"), const_cast<char *>("(JII)V"), reinterpret_cast<void *>(NativeImeSetComposingRegion) },
	{ const_cast<char *>("imeDeleteSurrounding"), const_cast<char *>("(JII)V"), reinterpret_cast<void *>(NativeImeDeleteSurrounding) },
	{ const_cast<char *>("imeSetSelection"), const_cast<char *>("(JII)V"), reinterpret_cast<void *>(NativeImeSetSelection) },
	{ const_cast<char *>("imeTextBefore"), const_cast<char *>("(JI)Ljava/lang/String;"), reinterpret_cast<void *>(NativeImeTextBefore) },
	{ const_cast<char *>("imeTextAfter"), const_cast<char *>("(JI)Ljava/lang/String;"), reinterpret_cast<void *>(NativeImeTextAfter) },
	{ const_cast<char *>("imeSelectedText"), const_cast<char *>("(J)Ljava/lang/String;"), reinterpret_cast<void *>(NativeImeSelectedText) },
	{ const_cast<char *>("imeState"), const_cast<char *>("(J[I)V"), reinterpret_cast<void *>(NativeImeState) },
};

/** Looks up an interface method, logging the first one that is missing. */
jmethodID Method(JNIEnv *env, jclass cls, const char *name, const char *signature, bool &ok) {
	const jmethodID id = cls ? env->GetMethodID(cls, name, signature) : nullptr;
	if (!id) {
		ClearException(env);
		Log("Missing Java method %s %s", name, signature);
		ok = false;
	}
	return id;
}

}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
	g_vm = vm;
	JNIEnv *env = nullptr;
	if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
		return JNI_ERR;
	}
	bool ok = true;

	const jclass font = env->FindClass(PKG "SciFont");
	g_ids.fontAscent = Method(env, font, "ascent", "()F", ok);
	g_ids.fontDescent = Method(env, font, "descent", "()F", ok);
	g_ids.fontInternalLeading = Method(env, font, "internalLeading", "()F", ok);
	g_ids.fontAverageCharWidth = Method(env, font, "averageCharWidth", "()F", ok);
	g_ids.fontWidth = Method(env, font, "width", "(Ljava/lang/String;)F", ok);
	g_ids.fontAdvances = Method(env, font, "advances", "(Ljava/lang/String;[F)V", ok);

	const jclass surface = env->FindClass(PKG "SciSurface");
	g_ids.surfRelease = Method(env, surface, "release", "()V", ok);
	g_ids.surfFillRect = Method(env, surface, "fillRect", "(FFFFI)V", ok);
	g_ids.surfStrokeRect = Method(env, surface, "strokeRect", "(FFFFIF)V", ok);
	g_ids.surfLine = Method(env, surface, "line", "(FFFFIF)V", ok);
	g_ids.surfPolygon = Method(env, surface, "polygon", "([FIIIFZ)V", ok);
	g_ids.surfRoundRect = Method(env, surface, "roundRect", "(FFFFFIIF)V", ok);
	g_ids.surfEllipse = Method(env, surface, "ellipse", "(FFFFIIF)V", ok);
	g_ids.surfGradientRect = Method(env, surface, "gradientRect", "(FFFF[I[FZ)V", ok);
	g_ids.surfImage = Method(env, surface, "image", "(FFFFII[I)V", ok);
	g_ids.surfCopy = Method(env, surface, "copy", "(FFFFFFL" PKG "SciSurface;)V", ok);
	g_ids.surfFillPattern = Method(env, surface, "fillPattern", "(FFFFL" PKG "SciSurface;)V", ok);
	g_ids.surfText = Method(env, surface, "text", "(L" PKG "SciFont;FFLjava/lang/String;I)V", ok);
	g_ids.surfPushClip = Method(env, surface, "pushClip", "(FFFF)V", ok);
	g_ids.surfPopClip = Method(env, surface, "popClip", "()V", ok);

	const jclass platform = env->FindClass(PKG "SciPlatform");
	g_ids.platCreateFont = Method(env, platform, "createFont", "(Ljava/lang/String;FIZ)L" PKG "SciFont;", ok);
	g_ids.platCreatePixmap = Method(env, platform, "createPixmap", "(II)L" PKG "SciSurface;", ok);

	const jclass host = env->FindClass(PKG "SciHost");
	g_ids.hostInvalidate = Method(env, host, "invalidate", "()V", ok);
	g_ids.hostNotify = Method(env, host, "notify", "(IJIIIJJJII)V", ok);
	g_ids.hostStartTimer = Method(env, host, "startTimer", "(II)V", ok);
	g_ids.hostCancelTimer = Method(env, host, "cancelTimer", "(I)V", ok);
	g_ids.hostSetIdle = Method(env, host, "setIdle", "(Z)V", ok);
	g_ids.hostQueueWork = Method(env, host, "queueWork", "()V", ok);
	g_ids.hostMacroRecord = Method(env, host, "macroRecord", "(IJJ[B)V", ok);
	g_ids.hostSetClipboard = Method(env, host, "setClipboard", "(Ljava/lang/String;)V", ok);
	g_ids.hostGetClipboard = Method(env, host, "getClipboard", "()Ljava/lang/String;", ok);

	const jclass natives = env->FindClass(PKG "ScintillaNative");
	if (!natives || env->RegisterNatives(natives, nativeMethods, static_cast<jint>(std::size(nativeMethods))) != JNI_OK) {
		ClearException(env);
		Log("Failed to register ScintillaNative methods");
		ok = false;
	}
	ClearException(env);
	return ok ? JNI_VERSION_1_6 : JNI_ERR;
}
