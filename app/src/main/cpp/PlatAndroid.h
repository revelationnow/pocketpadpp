// Pocketpad++ - Scintilla platform layer for Android.
#ifndef POCKETPAD_PLATANDROID_H
#define POCKETPAD_PLATANDROID_H

#include <jni.h>

namespace Scintilla::Internal {

/**
 * What Window needs from the object behind a WindowID.
 * Only the main editor window exists on Android; call tips and lists are not native windows.
 */
class HostWindow {
public:
	virtual PRectangle HostBounds() const noexcept = 0;
	virtual void HostInvalidate() noexcept = 0;
protected:
	~HostWindow() = default;
};

}

#endif
