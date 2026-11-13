// Pocketpad++ - Scintilla platform layer for Android.
// The editor class: binds one Scintilla instance to a Java SciHost.

#include "SciHeaders.h"
#include "Lexilla.h"
#include "Bridge.h"
#include "PlatAndroid.h"
#include "ScintillaAndroid.h"

using namespace Scintilla;
using namespace Scintilla::Internal;
using namespace pocketpad;

namespace {

// Limits on text handed to the soft keyboard in one call, to keep binder transactions small.
constexpr int maxImeContextUnits = 4096;
constexpr size_t maxImeSelectionBytes = 16384;
// Longer direct insertions are treated as a paste rather than as typing.
constexpr size_t maxTypedCharacters = 32;

size_t CountCharacters(std::string_view text) noexcept {
	size_t count = 0;
	for (const char ch : text) {
		if (!UTF8IsTrailByte(static_cast<unsigned char>(ch))) {
			count++;
		}
	}
	return count;
}

}

ScintillaAndroid::ScintillaAndroid(JNIEnv *env, jobject host_) {
	host = env->NewGlobalRef(host_);
	wMain = static_cast<WindowID>(static_cast<HostWindow *>(this));

	// The view is redrawn through a hardware display list, so an extra bitmap buffer only costs memory.
	view.bufferedDraw = false;
	// Fingers are far less precise than a mouse pointer.
	doubleClickCloseThreshold = Point(g_doubleTapSlop, g_doubleTapSlop);
	// Touch selection, drag and context menus are provided by the Android view.
	dragDropEnabled = false;
	displayPopupMenu = PopUp::Never;
	imeInteraction = IMEInteraction::Inline;

	// Underline for text the soft keyboard is still composing.
	vs.indicators[IndicatorInput] = Indicator(IndicatorStyle::CompositionThin, ColourRGBA(0x60, 0x60, 0x60));

	// Documents are always UTF-8 in memory; other encodings are converted on load and save.
	WndProc(Message::SetCodePage, SC_CP_UTF8, 0);
	// InputConnection speaks UTF-16 offsets; this index keeps conversions cheap in large files.
	pdoc->AllocateLineCharacterIndex(LineCharacterIndexType::Utf16);
}

ScintillaAndroid::~ScintillaAndroid() {
	try {
		ScintillaAndroid::Finalise();
	} catch (...) {
	}
	if (host) {
		if (JNIEnv *env = Env()) {
			env->DeleteGlobalRef(host);
		}
		host = nullptr;
	}
}

template <typename... Args>
void ScintillaAndroid::CallHost(jmethodID method, Args... args) const noexcept {
	JNIEnv *env = Env();
	if (env && host) {
		env->CallVoidMethod(host, method, args...);
		ClearException(env);
	}
}

PRectangle ScintillaAndroid::HostBounds() const noexcept {
	return PRectangle(0, 0, width, height);
}

void ScintillaAndroid::HostInvalidate() noexcept {
	CallHost(g_ids.hostInvalidate);
}

void ScintillaAndroid::Initialise() {
}

void ScintillaAndroid::Finalise() {
	for (size_t reason = 0; reason < std::size(timers); reason++) {
		FineTickerCancel(static_cast<TickReason>(reason));
	}
	ScintillaBase::Finalise();
}

// Entry points

sptr_t ScintillaAndroid::Send(unsigned int msg, uptr_t wParam, sptr_t lParam) {
	return WndProc(static_cast<Message>(msg), wParam, lParam);
}

std::string ScintillaAndroid::Range(Sci::Position start, Sci::Position end) {
	const Sci::Position length = pdoc->Length();
	start = std::clamp<Sci::Position>(start, 0, length);
	end = std::clamp<Sci::Position>(end, start, length);
	return RangeText(start, end);
}

bool ScintillaAndroid::SetLexerByName(const char *name) {
	ILexer5 *lexer = (name && *name) ? CreateLexer(name) : nullptr;
	WndProc(Message::SetILexer, 0, reinterpret_cast<sptr_t>(lexer));
	return lexer != nullptr;
}

void ScintillaAndroid::Resize(int width_, int height_) {
	if (width != width_ || height != height_) {
		width = width_;
		height = height_;
		ChangeSize();
	}
}

void ScintillaAndroid::PaintTo(jobject jsurface) {
	if (width <= 0 || height <= 0 || !jsurface) {
		return;
	}
	// The whole client area is always painted, so a paint never needs to be abandoned and redone.
	rcPaint = GetClientRectangle();
	paintState = PaintState::painting;
	paintingAllText = true;
	{
		AutoSurface surfacePaint(static_cast<SurfaceID>(jsurface), this);
		if (surfacePaint) {
			Paint(surfacePaint, rcPaint);
			surfacePaint->Release();
		}
	}
	paintState = PaintState::notPainting;
}

void ScintillaAndroid::Focus(bool focus) {
	SetFocusState(focus);
}

void ScintillaAndroid::MouseDown(Point pt, unsigned int time, int modifiers) {
	ButtonDownWithModifiers(pt, time, static_cast<KeyMod>(modifiers));
}

void ScintillaAndroid::MouseMove(Point pt, unsigned int time, int modifiers) {
	ButtonMoveWithModifiers(pt, time, static_cast<KeyMod>(modifiers));
}

void ScintillaAndroid::MouseUp(Point pt, unsigned int time, int modifiers) {
	ButtonUpWithModifiers(pt, time, static_cast<KeyMod>(modifiers));
}

bool ScintillaAndroid::Key(int key, int modifiers) {
	bool consumed = false;
	KeyDownWithModifiers(static_cast<Keys>(key), static_cast<KeyMod>(modifiers), &consumed);
	return consumed;
}

void ScintillaAndroid::TypeText(std::string_view text) {
	if (text.empty()) {
		return;
	}
	if (text == "\t") {
		// Honours the tab / indentation settings, unlike inserting a literal tab.
		KeyCommand(Message::Tab);
		return;
	}
	const size_t characters = CountCharacters(text);
	if (characters > maxTypedCharacters) {
		const std::string converted = convertPastes ? Document::TransformLineEnds(text, pdoc->eolMode) : std::string(text);
		UndoGroup ug(pdoc);
		ClearSelection(multiPasteMode == MultiPaste::Each);
		InsertPasteShape(converted, PasteShape::stream);
		EnsureCaretVisible();
		return;
	}
	// A single character is left ungrouped so that consecutive typing coalesces into one undo step.
	UndoGroup ug(pdoc, characters > 1);
	size_t i = 0;
	while (i < text.size()) {
		const unsigned char ch = static_cast<unsigned char>(text[i]);
		if (ch == '\r' || ch == '\n') {
			if (ch == '\r' && i + 1 < text.size() && text[i + 1] == '\n') {
				i++;
			}
			i++;
			NewLine();
			continue;
		}
		size_t charWidth = 1;
		if (ch >= 0x80) {
			const int classified = UTF8Classify(text.substr(i));
			if (!(classified & UTF8MaskInvalid)) {
				charWidth = static_cast<size_t>(classified & UTF8MaskWidth);
			}
		}
		InsertCharacter(text.substr(i, charWidth), CharacterSource::DirectInput);
		i += charWidth;
	}
}

void ScintillaAndroid::Tick(int reason) {
	if (reason >= 0 && reason < static_cast<int>(std::size(timers)) && timers[reason]) {
		TickFor(static_cast<TickReason>(reason));
	}
}

bool ScintillaAndroid::DoIdle() {
	const bool more = Idle();
	if (!more) {
		SetIdle(false);
	}
	return more;
}

void ScintillaAndroid::DoIdleWork() {
	workQueued = false;
	Editor::IdleWork();
}

// Soft keyboard support

Sci::Position ScintillaAndroid::RelativeUtf16(Sci::Position pos, Sci::Position units) const noexcept {
	// Steps whole characters and never overshoots: a count that would end inside
	// a surrogate pair stops before the pair, so half a character is never selected or deleted.
	const int direction = (units > 0) ? 1 : -1;
	Sci::Position remaining = std::abs(units);
	while (remaining > 0) {
		const Sci::Position next = pdoc->NextPosition(pos, direction);
		if (next == pos) {
			break;
		}
		const Sci::Position cost = (std::abs(next - pos) > 3) ? 2 : 1;
		if (cost > remaining) {
			break;
		}
		remaining -= cost;
		pos = next;
	}
	return pos;
}

Sci::Position ScintillaAndroid::PositionFromUtf16(Sci::Position index) const noexcept {
	if (index <= 0) {
		return 0;
	}
	const Sci::Line line = pdoc->LineFromPositionIndex(index, LineCharacterIndexType::Utf16);
	const Sci::Position indexLineStart = pdoc->IndexLineStart(line, LineCharacterIndexType::Utf16);
	return RelativeUtf16(pdoc->LineStart(line), index - indexLineStart);
}

Sci::Position ScintillaAndroid::Utf16FromPosition(Sci::Position pos) const noexcept {
	const Sci::Line line = pdoc->SciLineFromPosition(pos);
	return pdoc->IndexLineStart(line, LineCharacterIndexType::Utf16) + pdoc->CountUTF16(pdoc->LineStart(line), pos);
}

void ScintillaAndroid::MarkComposing(Sci::Position start, Sci::Position end, bool on) {
	if (start >= 0 && end > start) {
		pdoc->DecorationSetCurrentIndicator(IndicatorInput);
		pdoc->DecorationFillRange(start, on ? 1 : 0, end - start);
	}
}

void ScintillaAndroid::ImeReplaceRange(Sci::Position start, Sci::Position end, std::string_view text) {
	// Keyboards resend the whole composing word on every keystroke. Changing only the part that
	// differs makes that look like ordinary typing to the undo history and to notifications.
	const std::string old = RangeText(start, end);
	const size_t common = std::min(old.size(), text.size());
	size_t prefix = 0;
	while (prefix < common && old[prefix] == text[prefix]) {
		prefix++;
	}
	while (prefix > 0 && ((prefix < text.size() && UTF8IsTrailByte(static_cast<unsigned char>(text[prefix]))) ||
		(prefix < old.size() && UTF8IsTrailByte(static_cast<unsigned char>(old[prefix]))))) {
		prefix--;
	}
	size_t suffix = 0;
	while (suffix < common - prefix && old[old.size() - 1 - suffix] == text[text.size() - 1 - suffix]) {
		suffix++;
	}
	while (suffix > 0 && (UTF8IsTrailByte(static_cast<unsigned char>(text[text.size() - suffix])) ||
		UTF8IsTrailByte(static_cast<unsigned char>(old[old.size() - suffix])))) {
		suffix--;
	}
	const Sci::Position deleteStart = start + static_cast<Sci::Position>(prefix);
	const Sci::Position deleteLength = static_cast<Sci::Position>(old.size() - prefix - suffix);
	const std::string_view inserted = text.substr(prefix, text.size() - prefix - suffix);
	if (deleteLength == 0 && inserted.empty()) {
		return;
	}
	{
		UndoGroup ug(pdoc, deleteLength > 0 && !inserted.empty());
		if (deleteLength > 0) {
			pdoc->DeleteChars(deleteStart, deleteLength);
		}
		if (!inserted.empty()) {
			pdoc->InsertString(deleteStart, inserted);
		}
	}
	if (deleteLength == 0 && suffix == 0 && CountCharacters(inserted) == 1) {
		// One character appended: report it like a typed character so that
		// features driven by SCN_CHARADDED keep working while composing.
		NotifyChar(UnicodeFromUTF8(inserted), CharacterSource::TentativeInput);
	}
}

void ScintillaAndroid::ImePlaceCaret(Sci::Position start, Sci::Position end, int newCursorPosition) {
	// InputConnection: values above 0 are relative to the end of the text (1 = just after it),
	// values of 0 or below are relative to its start.
	const Sci::Position caret = (newCursorPosition > 0) ?
		RelativeUtf16(end, newCursorPosition - 1) : RelativeUtf16(start, newCursorPosition);
	SetEmptySelection(caret);
	EnsureCaretVisible();
	ShowCaretAtCurrentPosition();
	SetLastXChosen();
}

void ScintillaAndroid::ImeSetComposingText(std::string_view text, int newCursorPosition) {
	Sci::Position start;
	Sci::Position end;
	if (compStart >= 0) {
		start = compStart;
		end = compEnd;
		MarkComposing(start, end, false);
	} else {
		start = SelectionStart().Position();
		end = SelectionEnd().Position();
	}
	compStart = compEnd = -1;
	ImeReplaceRange(start, end, text);
	const Sci::Position newEnd = start + static_cast<Sci::Position>(text.size());
	if (!text.empty()) {
		compStart = start;
		compEnd = newEnd;
		MarkComposing(compStart, compEnd, true);
	}
	ImePlaceCaret(start, newEnd, newCursorPosition);
}

void ScintillaAndroid::ImeCommitText(std::string_view text, int newCursorPosition) {
	if (compStart < 0) {
		if (text.empty()) {
			// Committing nothing over a selection is how some keyboards delete it.
			if (!sel.Empty()) {
				ClearSelection();
				EnsureCaretVisible();
			}
			return;
		}
		const Sci::Position before = SelectionStart().Position();
		TypeText(text);
		if (newCursorPosition != 1) {
			ImePlaceCaret(before, sel.MainCaret(), newCursorPosition);
		}
		return;
	}
	const Sci::Position start = compStart;
	const Sci::Position end = compEnd;
	MarkComposing(start, end, false);
	compStart = compEnd = -1;
	// Line ends go through TypeText so that they use the document's end-of-line mode.
	const size_t lineEnd = text.find_first_of("\r\n");
	const std::string_view head = text.substr(0, lineEnd);
	ImeReplaceRange(start, end, head);
	const Sci::Position headEnd = start + static_cast<Sci::Position>(head.size());
	if (lineEnd == std::string_view::npos) {
		ImePlaceCaret(start, headEnd, newCursorPosition);
	} else {
		ImePlaceCaret(start, headEnd, 1);
		TypeText(text.substr(lineEnd));
	}
}

void ScintillaAndroid::ImeFinishComposing() {
	if (compStart >= 0) {
		MarkComposing(compStart, compEnd, false);
		compStart = compEnd = -1;
	}
}

void ScintillaAndroid::ImeSetComposingRegion(int startUtf16, int endUtf16) {
	ImeFinishComposing();
	const Sci::Position a = PositionFromUtf16(std::min(startUtf16, endUtf16));
	const Sci::Position b = PositionFromUtf16(std::max(startUtf16, endUtf16));
	if (a < b) {
		compStart = a;
		compEnd = b;
		MarkComposing(compStart, compEnd, true);
	}
}

void ScintillaAndroid::ImeDeleteSurrounding(int beforeUtf16, int afterUtf16) {
	if (beforeUtf16 == 1 && afterUtf16 == 0 && compStart < 0 && sel.Empty()) {
		// The usual backspace: let Scintilla do it so that CRLF pairs, unindenting
		// and multiple carets behave exactly as with a hardware keyboard.
		KeyCommand(Message::DeleteBack);
		return;
	}
	const Sci::Position selStart = SelectionStart().Position();
	const Sci::Position selEnd = SelectionEnd().Position();
	Sci::Position a = RelativeUtf16(selStart, -static_cast<Sci::Position>(std::max(beforeUtf16, 0)));
	Sci::Position b = RelativeUtf16(selEnd, std::max(afterUtf16, 0));
	// Never leave half of a CRLF pair behind.
	a = pdoc->MovePositionOutsideChar(a, -1, true);
	b = pdoc->MovePositionOutsideChar(b, 1, true);
	{
		UndoGroup ug(pdoc, (b > selEnd) && (a < selStart));
		if (b > selEnd) {
			pdoc->DeleteChars(selEnd, b - selEnd);
		}
		if (a < selStart) {
			pdoc->DeleteChars(a, selStart - a);
		}
	}
	EnsureCaretVisible();
	ShowCaretAtCurrentPosition();
	SetLastXChosen();
}

void ScintillaAndroid::ImeSetSelection(int startUtf16, int endUtf16) {
	const Sci::Position anchor = PositionFromUtf16(startUtf16);
	const Sci::Position caret = PositionFromUtf16(endUtf16);
	SetSelection(caret, anchor);
	EnsureCaretVisible();
	ShowCaretAtCurrentPosition();
	SetLastXChosen();
}

std::string ScintillaAndroid::ImeTextBefore(int countUtf16) {
	const Sci::Position selStart = SelectionStart().Position();
	const Sci::Position from = RelativeUtf16(selStart, -static_cast<Sci::Position>(std::clamp(countUtf16, 0, maxImeContextUnits)));
	return RangeText(from, selStart);
}

std::string ScintillaAndroid::ImeTextAfter(int countUtf16) {
	const Sci::Position selEnd = SelectionEnd().Position();
	const Sci::Position to = RelativeUtf16(selEnd, std::clamp(countUtf16, 0, maxImeContextUnits));
	return RangeText(selEnd, to);
}

std::string ScintillaAndroid::ImeSelectedText() {
	const Sci::Position selStart = SelectionStart().Position();
	Sci::Position selEnd = SelectionEnd().Position();
	if (static_cast<size_t>(selEnd - selStart) > maxImeSelectionBytes) {
		selEnd = pdoc->MovePositionOutsideChar(selStart + static_cast<Sci::Position>(maxImeSelectionBytes), -1, false);
	}
	return RangeText(selStart, selEnd);
}

void ScintillaAndroid::ImeState(int out[4]) {
	out[0] = static_cast<int>(Utf16FromPosition(SelectionStart().Position()));
	out[1] = static_cast<int>(Utf16FromPosition(SelectionEnd().Position()));
	if (compStart >= 0) {
		out[2] = static_cast<int>(Utf16FromPosition(compStart));
		out[3] = static_cast<int>(Utf16FromPosition(compEnd));
	} else {
		out[2] = -1;
		out[3] = -1;
	}
}

// Editor overrides

void ScintillaAndroid::SetVerticalScrollPos() {
}

void ScintillaAndroid::SetHorizontalScrollPos() {
}

bool ScintillaAndroid::ModifyScrollBars(Sci::Line nMax, Sci::Line nPage) {
	bool modified = false;
	const Sci::Line vNewMax = nMax - nPage + 1;
	if (vMax != vNewMax || vPage != nPage) {
		vMax = vNewMax;
		vPage = nPage;
		modified = true;
	}
	const int hNewPage = static_cast<int>(GetTextRectangle().Width());
	const int hNewMax = (scrollWidth > hNewPage) ? scrollWidth - hNewPage : 0;
	if (hMax != hNewMax || hPage != hNewPage) {
		hMax = hNewMax;
		hPage = hNewPage;
		modified = true;
	}
	return modified;
}

void ScintillaAndroid::Copy() {
	if (!sel.Empty()) {
		SelectionText st;
		CopySelectionRange(&st);
		CopyToClipboard(st);
	}
}

void ScintillaAndroid::CopyToClipboard(const SelectionText &selectedText) {
	JNIEnv *env = Env();
	if (!env || !host) {
		return;
	}
	const Local<jstring> text(env, NewJString(env, selectedText.AsView()));
	if (text) {
		env->CallVoidMethod(host, g_ids.hostSetClipboard, text.get());
	}
	ClearException(env);
}

void ScintillaAndroid::Paste() {
	JNIEnv *env = Env();
	if (!env || !host) {
		return;
	}
	const Local<jstring> clip(env, static_cast<jstring>(env->CallObjectMethod(host, g_ids.hostGetClipboard)));
	if (ClearException(env) || !clip) {
		return;
	}
	std::string text = Utf8FromJString(env, clip.get());
	if (text.empty()) {
		return;
	}
	if (convertPastes) {
		text = Document::TransformLineEnds(text, pdoc->eolMode);
	}
	UndoGroup ug(pdoc);
	ClearSelection(multiPasteMode == MultiPaste::Each);
	InsertPasteShape(text, PasteShape::stream);
	EnsureCaretVisible();
}

void ScintillaAndroid::ClaimSelection() {
}

void ScintillaAndroid::NotifyChange() {
}

void ScintillaAndroid::NotifyParent(NotificationData scn) {
	JNIEnv *env = Env();
	if (!env || !host) {
		return;
	}
	env->CallVoidMethod(host, g_ids.hostNotify,
		static_cast<jint>(scn.nmhdr.code),
		static_cast<jlong>(scn.position),
		static_cast<jint>(scn.ch),
		static_cast<jint>(scn.modifiers),
		static_cast<jint>(scn.modificationType),
		static_cast<jlong>(scn.length),
		static_cast<jlong>(scn.linesAdded),
		static_cast<jlong>(scn.line),
		static_cast<jint>(scn.margin),
		static_cast<jint>(scn.updated));
	ClearException(env);
}

void ScintillaAndroid::NotifyModified(Document *document, DocModification mh, void *userData) {
	if (compStart >= 0) {
		// Keep the composing range attached to its text when something else edits the document.
		if (FlagSet(mh.modificationType, ModificationFlags::InsertText)) {
			if (mh.position <= compStart) {
				compStart += mh.length;
				compEnd += mh.length;
			} else if (mh.position < compEnd) {
				compEnd += mh.length;
			}
		} else if (FlagSet(mh.modificationType, ModificationFlags::DeleteText)) {
			const Sci::Position deleteEnd = mh.position + mh.length;
			const auto adjust = [&](Sci::Position p) noexcept {
				if (p <= mh.position) {
					return p;
				}
				return (p >= deleteEnd) ? p - mh.length : mh.position;
			};
			compStart = adjust(compStart);
			compEnd = adjust(compEnd);
			if (compStart >= compEnd) {
				compStart = compEnd = -1;
			}
		}
	}
	ScintillaBase::NotifyModified(document, mh, userData);
}

bool ScintillaAndroid::FineTickerRunning(TickReason reason) {
	return timers[static_cast<size_t>(reason)];
}

void ScintillaAndroid::FineTickerStart(TickReason reason, int millis, int) {
	timers[static_cast<size_t>(reason)] = true;
	CallHost(g_ids.hostStartTimer, static_cast<jint>(reason), static_cast<jint>(millis));
}

void ScintillaAndroid::FineTickerCancel(TickReason reason) {
	if (timers[static_cast<size_t>(reason)]) {
		timers[static_cast<size_t>(reason)] = false;
		CallHost(g_ids.hostCancelTimer, static_cast<jint>(reason));
	}
}

bool ScintillaAndroid::SetIdle(bool on) {
	if (idler.state != on) {
		idler.state = on;
		CallHost(g_ids.hostSetIdle, static_cast<jboolean>(on));
	}
	return true;
}

void ScintillaAndroid::QueueIdleWork(WorkItems items, Sci::Position upTo) {
	Editor::QueueIdleWork(items, upTo);
	if (!workQueued) {
		workQueued = true;
		CallHost(g_ids.hostQueueWork);
	}
}

void ScintillaAndroid::SetMouseCapture(bool on) {
	capturedMouse = on;
}

bool ScintillaAndroid::HaveMouseCapture() {
	return capturedMouse;
}

void ScintillaAndroid::SetDocPointer(Document *document) {
	// Composing state and its indicator belong to the document being left.
	ImeFinishComposing();
	ScintillaBase::SetDocPointer(document);
	pdoc->AllocateLineCharacterIndex(LineCharacterIndexType::Utf16);
}

std::string ScintillaAndroid::UTF8FromEncoded(std::string_view encoded) const {
	return std::string(encoded);
}

std::string ScintillaAndroid::EncodedFromUTF8(std::string_view utf8) const {
	return std::string(utf8);
}

std::unique_ptr<CaseFolder> ScintillaAndroid::CaseFolderForEncoding() {
	if (pdoc->dbcsCodePage == SC_CP_UTF8) {
		return std::make_unique<CaseFolderUnicode>();
	}
	return ScintillaBase::CaseFolderForEncoding();
}

std::string ScintillaAndroid::CaseMapString(const std::string &s, CaseMapping caseMapping) {
	if (s.empty() || caseMapping == CaseMapping::same) {
		return s;
	}
	if (pdoc->dbcsCodePage == SC_CP_UTF8) {
		return CaseConvertString(s, (caseMapping == CaseMapping::upper) ? CaseConversion::upper : CaseConversion::lower);
	}
	return ScintillaBase::CaseMapString(s, caseMapping);
}

void ScintillaAndroid::CreateCallTipWindow(PRectangle) {
}

void ScintillaAndroid::AddToPopUp(const char *, int, bool) {
}

sptr_t ScintillaAndroid::WndProc(Message iMessage, uptr_t wParam, sptr_t lParam) {
	try {
		return ScintillaBase::WndProc(iMessage, wParam, lParam);
	} catch (std::bad_alloc &) {
		errorStatus = Status::BadAlloc;
	} catch (...) {
		errorStatus = Status::Failure;
	}
	return 0;
}

sptr_t ScintillaAndroid::DefWndProc(Message, uptr_t, sptr_t) {
	return 0;
}
