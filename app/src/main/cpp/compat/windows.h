// Pocketpad++: stand-in for <windows.h>.
// Notepad++'s user-defined-language lexer (LexUser.cxx) includes it; the definitions below are all it needs.
#ifndef POCKETPAD_COMPAT_WINDOWS_H
#define POCKETPAD_COMPAT_WINDOWS_H

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string.h>

/** Microsoft's integer-to-string helper. The buffer must hold the digits, a sign and a NUL. */
inline char *_itoa(int value, char *buffer, int radix) {
	if (radix < 2 || radix > 36) {
		buffer[0] = '\0';
		return buffer;
	}
	char reversed[40];
	int length = 0;
	const bool negative = (radix == 10) && (value < 0);
	unsigned int magnitude = negative ? 0u - static_cast<unsigned int>(value) : static_cast<unsigned int>(value);
	do {
		const unsigned int digit = magnitude % static_cast<unsigned int>(radix);
		reversed[length++] = static_cast<char>(digit < 10 ? '0' + digit : 'a' + (digit - 10));
		magnitude /= static_cast<unsigned int>(radix);
	} while (magnitude != 0);
	char *out = buffer;
	if (negative) {
		*out++ = '-';
	}
	while (length > 0) {
		*out++ = reversed[--length];
	}
	*out = '\0';
	return buffer;
}

#endif
