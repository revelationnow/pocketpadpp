// Pocketpad++ - Scintilla platform layer for Android.
#ifndef POCKETPAD_SCINTILLAANDROID_H
#define POCKETPAD_SCINTILLAANDROID_H

namespace Scintilla::Internal {

/**
 * One Scintilla editor instance bound to a Java SciHost.
 * Adds the Android-specific pieces the portable core leaves to the platform:
 * painting, timers, clipboard and soft keyboard (IME) editing in UTF-16 offsets.
 */
class ScintillaAndroid final : public ScintillaBase, public HostWindow {
	jobject host = nullptr;	// Global reference to the SciHost.
	int width = 0;
	int height = 0;
	bool capturedMouse = false;
	bool timers[static_cast<size_t>(TickReason::platform) + 1] {};
	bool workQueued = false;
	Sci::Line vMax = 0;
	Sci::Line vPage = 0;
	int hMax = 0;
	int hPage = 0;
	// Text the soft keyboard is still composing, as document positions. -1 when none.
	Sci::Position compStart = -1;
	Sci::Position compEnd = -1;

public:
	ScintillaAndroid(JNIEnv *env, jobject host_);
	~ScintillaAndroid() override;

	// HostWindow
	PRectangle HostBounds() const noexcept override;
	void HostInvalidate() noexcept override;

	// Called from JNI
	sptr_t Send(unsigned int msg, uptr_t wParam, sptr_t lParam);
	std::string Range(Sci::Position start, Sci::Position end);
	bool SetLexerByName(const char *name);
	void Resize(int width_, int height_);
	void PaintTo(jobject jsurface);
	void Focus(bool focus);
	void MouseDown(Point pt, unsigned int time, int modifiers);
	void MouseMove(Point pt, unsigned int time, int modifiers);
	void MouseUp(Point pt, unsigned int time, int modifiers);
	bool Key(int key, int modifiers);
	void TypeText(std::string_view text);
	void Tick(int reason);
	bool DoIdle();
	void DoIdleWork();

	void ImeSetComposingText(std::string_view text, int newCursorPosition);
	void ImeCommitText(std::string_view text, int newCursorPosition);
	void ImeFinishComposing();
	void ImeSetComposingRegion(int startUtf16, int endUtf16);
	void ImeDeleteSurrounding(int beforeUtf16, int afterUtf16);
	void ImeSetSelection(int startUtf16, int endUtf16);
	std::string ImeTextBefore(int countUtf16);
	std::string ImeTextAfter(int countUtf16);
	std::string ImeSelectedText();
	void ImeState(int out[4]);

private:
	template <typename... Args>
	void CallHost(jmethodID method, Args... args) const noexcept;

	Sci::Position PositionFromUtf16(Sci::Position index) const noexcept;
	Sci::Position Utf16FromPosition(Sci::Position pos) const noexcept;
	Sci::Position RelativeUtf16(Sci::Position pos, Sci::Position units) const noexcept;
	void MarkComposing(Sci::Position start, Sci::Position end, bool on);
	void ImeReplaceRange(Sci::Position start, Sci::Position end, std::string_view text);
	void ImePlaceCaret(Sci::Position start, Sci::Position end, int newCursorPosition);

	// Editor / ScintillaBase
	void Initialise() override;
	void Finalise() override;
	void SetVerticalScrollPos() override;
	void SetHorizontalScrollPos() override;
	bool ModifyScrollBars(Sci::Line nMax, Sci::Line nPage) override;
	void Copy() override;
	void Paste() override;
	void ClaimSelection() override;
	void NotifyChange() override;
	void NotifyParent(NotificationData scn) override;
	void NotifyModified(Document *document, DocModification mh, void *userData) override;
	void CopyToClipboard(const SelectionText &selectedText) override;
	bool FineTickerRunning(TickReason reason) override;
	void FineTickerStart(TickReason reason, int millis, int tolerance) override;
	void FineTickerCancel(TickReason reason) override;
	bool SetIdle(bool on) override;
	void QueueIdleWork(WorkItems items, Sci::Position upTo) override;
	void SetMouseCapture(bool on) override;
	bool HaveMouseCapture() override;
	void SetDocPointer(Document *document) override;
	std::string UTF8FromEncoded(std::string_view encoded) const override;
	std::string EncodedFromUTF8(std::string_view utf8) const override;
	std::unique_ptr<CaseFolder> CaseFolderForEncoding() override;
	std::string CaseMapString(const std::string &s, CaseMapping caseMapping) override;
	void CreateCallTipWindow(PRectangle rc) override;
	void AddToPopUp(const char *label, int cmd, bool enabled) override;
	sptr_t WndProc(Message iMessage, uptr_t wParam, sptr_t lParam) override;
	sptr_t DefWndProc(Message iMessage, uptr_t wParam, sptr_t lParam) override;
};

}

#endif
