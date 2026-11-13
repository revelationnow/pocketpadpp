// Pocketpad++ - Scintilla platform layer for Android.
// JNI plumbing shared by the platform classes and the editor class.
#ifndef POCKETPAD_BRIDGE_H
#define POCKETPAD_BRIDGE_H

#include <jni.h>

#include <string>
#include <string_view>
#include <vector>

namespace pocketpad {

/** Method ids of the Java interfaces the native layer calls. Filled in JNI_OnLoad. */
struct JniIds {
	// SciFont
	jmethodID fontAscent {};
	jmethodID fontDescent {};
	jmethodID fontInternalLeading {};
	jmethodID fontAverageCharWidth {};
	jmethodID fontWidth {};
	jmethodID fontAdvances {};
	// SciSurface
	jmethodID surfRelease {};
	jmethodID surfFillRect {};
	jmethodID surfStrokeRect {};
	jmethodID surfLine {};
	jmethodID surfPolygon {};
	jmethodID surfRoundRect {};
	jmethodID surfEllipse {};
	jmethodID surfGradientRect {};
	jmethodID surfImage {};
	jmethodID surfCopy {};
	jmethodID surfFillPattern {};
	jmethodID surfText {};
	jmethodID surfPushClip {};
	jmethodID surfPopClip {};
	// SciPlatform
	jmethodID platCreateFont {};
	jmethodID platCreatePixmap {};
	// SciHost
	jmethodID hostInvalidate {};
	jmethodID hostNotify {};
	jmethodID hostStartTimer {};
	jmethodID hostCancelTimer {};
	jmethodID hostSetIdle {};
	jmethodID hostQueueWork {};
	jmethodID hostSetClipboard {};
	jmethodID hostGetClipboard {};
};

extern JavaVM *g_vm;
extern JniIds g_ids;
extern jobject g_platform;      // Global reference to the SciPlatform factory.
extern float g_pixelsPerPoint;  // Scintilla font "points" to device pixels.
extern float g_doubleTapSlop;   // Max distance in pixels between the taps of a double tap.

/** JNIEnv of the calling thread. All Scintilla work happens on the UI thread. */
JNIEnv *Env() noexcept;

/** Log and clear a pending Java exception so that native code can carry on. Returns true if there was one. */
bool ClearException(JNIEnv *env) noexcept;

void Log(const char *format, ...) noexcept;

/** UTF-8 to UTF-16. Invalid bytes become U+FFFD so the result is always well formed. */
std::u16string Utf16FromUtf8(std::string_view utf8);
/** UTF-16 to UTF-8. Unpaired surrogates become U+FFFD. */
std::string Utf8FromUtf16(std::u16string_view utf16);

/** New local reference to a Java String holding the UTF-8 text. */
jstring NewJString(JNIEnv *env, std::string_view utf8);
std::string Utf8FromJString(JNIEnv *env, jstring s);

/** RAII holder for a JNI local reference. */
template <typename T>
class Local {
	JNIEnv *env;
	T ref;
public:
	Local(JNIEnv *env_, T ref_) noexcept : env(env_), ref(ref_) {}
	Local(const Local &) = delete;
	Local &operator=(const Local &) = delete;
	~Local() {
		if (ref) {
			env->DeleteLocalRef(ref);
		}
	}
	T get() const noexcept { return ref; }
	explicit operator bool() const noexcept { return ref != nullptr; }
};

}

#endif
