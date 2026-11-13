// Pocketpad++ - Scintilla platform layer for Android.
// JNI plumbing shared by the platform classes and the editor class.

#include "Bridge.h"

#include <cstdarg>
#include <cstdio>

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace pocketpad {

JavaVM *g_vm = nullptr;
JniIds g_ids;
jobject g_platform = nullptr;
float g_pixelsPerPoint = 1.0f;
float g_doubleTapSlop = 24.0f;

JNIEnv *Env() noexcept {
	JNIEnv *env = nullptr;
	if (g_vm) {
		g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
	}
	return env;
}

bool ClearException(JNIEnv *env) noexcept {
	if (env && env->ExceptionCheck()) {
		env->ExceptionDescribe();
		env->ExceptionClear();
		return true;
	}
	return false;
}

void Log(const char *format, ...) noexcept {
	va_list args;
	va_start(args, format);
#if defined(__ANDROID__)
	__android_log_vprint(ANDROID_LOG_INFO, "PocketpadSci", format, args);
#else
	vfprintf(stderr, format, args);
	fputc('\n', stderr);
#endif
	va_end(args);
}

namespace {

constexpr char32_t replacementChar = 0xFFFD;

/** Decode one code point from UTF-8, advancing i. Malformed input yields U+FFFD and consumes one byte. */
char32_t DecodeUtf8(std::string_view s, size_t &i) noexcept {
	const unsigned char lead = static_cast<unsigned char>(s[i]);
	if (lead < 0x80) {
		i++;
		return lead;
	}
	int extra = 0;
	char32_t cp = 0;
	char32_t minimum = 0;
	if ((lead & 0xE0) == 0xC0) {
		extra = 1; cp = lead & 0x1F; minimum = 0x80;
	} else if ((lead & 0xF0) == 0xE0) {
		extra = 2; cp = lead & 0x0F; minimum = 0x800;
	} else if ((lead & 0xF8) == 0xF0) {
		extra = 3; cp = lead & 0x07; minimum = 0x10000;
	} else {
		i++;
		return replacementChar;
	}
	if (i + extra >= s.size()) {
		// Not enough bytes left for the whole sequence.
		i++;
		return replacementChar;
	}
	for (int k = 1; k <= extra; k++) {
		const unsigned char trail = static_cast<unsigned char>(s[i + k]);
		if ((trail & 0xC0) != 0x80) {
			i++;
			return replacementChar;
		}
		cp = (cp << 6) | (trail & 0x3F);
	}
	if (cp < minimum || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
		i++;
		return replacementChar;
	}
	i += extra + 1;
	return cp;
}

}

std::u16string Utf16FromUtf8(std::string_view utf8) {
	std::u16string out;
	out.reserve(utf8.size());
	size_t i = 0;
	while (i < utf8.size()) {
		const char32_t cp = DecodeUtf8(utf8, i);
		if (cp >= 0x10000) {
			const char32_t v = cp - 0x10000;
			out.push_back(static_cast<char16_t>(0xD800 + (v >> 10)));
			out.push_back(static_cast<char16_t>(0xDC00 + (v & 0x3FF)));
		} else {
			out.push_back(static_cast<char16_t>(cp));
		}
	}
	return out;
}

std::string Utf8FromUtf16(std::u16string_view utf16) {
	std::string out;
	out.reserve(utf16.size() * 3);
	for (size_t i = 0; i < utf16.size(); i++) {
		char32_t cp = utf16[i];
		if (cp >= 0xD800 && cp <= 0xDBFF) {
			if (i + 1 < utf16.size() && utf16[i + 1] >= 0xDC00 && utf16[i + 1] <= 0xDFFF) {
				cp = 0x10000 + ((cp - 0xD800) << 10) + (utf16[i + 1] - 0xDC00);
				i++;
			} else {
				cp = replacementChar;
			}
		} else if (cp >= 0xDC00 && cp <= 0xDFFF) {
			cp = replacementChar;
		}
		if (cp < 0x80) {
			out.push_back(static_cast<char>(cp));
		} else if (cp < 0x800) {
			out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
			out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
		} else if (cp < 0x10000) {
			out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
			out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
			out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
		} else {
			out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
			out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
			out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
			out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
		}
	}
	return out;
}

jstring NewJString(JNIEnv *env, std::string_view utf8) {
	const std::u16string u16 = Utf16FromUtf8(utf8);
	return env->NewString(reinterpret_cast<const jchar *>(u16.data()), static_cast<jsize>(u16.size()));
}

std::string Utf8FromJString(JNIEnv *env, jstring s) {
	if (!s) {
		return {};
	}
	const jsize len = env->GetStringLength(s);
	std::u16string u16(static_cast<size_t>(len), u'\0');
	if (len > 0) {
		env->GetStringRegion(s, 0, len, reinterpret_cast<jchar *>(u16.data()));
	}
	return Utf8FromUtf16(u16);
}

}
