// Pocketpad++ - Scintilla platform layer for Android.
// Implements Scintilla's Platform.h (Font, Surface, Window, ListBox, Menu, Platform)
// by forwarding to the Java interfaces SciFont, SciSurface and SciPlatform.
// Nothing here uses the Android NDK directly, so the same code runs on a desktop JVM for tests.

#include "SciHeaders.h"
#include "Bridge.h"
#include "PlatAndroid.h"

using namespace Scintilla;
using namespace Scintilla::Internal;
using namespace pocketpad;

namespace {

constexpr jint noColour = 0;

/** Scintilla keeps colours as ABGR; Android and Java2D want ARGB. */
jint Argb(ColourRGBA c) noexcept {
	const unsigned int v = (static_cast<unsigned int>(c.GetAlpha()) << 24) |
		(static_cast<unsigned int>(c.GetRed()) << 16) |
		(static_cast<unsigned int>(c.GetGreen()) << 8) |
		static_cast<unsigned int>(c.GetBlue());
	return static_cast<jint>(v);
}

jfloat F(XYPOSITION v) noexcept {
	return static_cast<jfloat>(v);
}

class FontAndroid final : public Font {
public:
	jobject jfont = nullptr;	// Global reference to a SciFont.
	XYPOSITION ascent = 1;
	XYPOSITION descent = 1;
	XYPOSITION internalLeading = 0;
	XYPOSITION averageCharWidth = 1;

	explicit FontAndroid(const FontParameters &fp) {
		JNIEnv *env = Env();
		if (!env || !g_platform) {
			return;
		}
		const Local<jstring> face(env, NewJString(env, fp.faceName ? fp.faceName : ""));
		const Local<jobject> created(env, env->CallObjectMethod(g_platform, g_ids.platCreateFont,
			face.get(), F(fp.size), static_cast<jint>(fp.weight), static_cast<jboolean>(fp.italic)));
		if (ClearException(env) || !created) {
			return;
		}
		jfont = env->NewGlobalRef(created.get());
		// Whole pixels keep line heights and baselines crisp.
		const auto metric = [&](jmethodID method, XYPOSITION fallback) {
			const jfloat value = env->CallFloatMethod(jfont, method);
			return ClearException(env) ? fallback : static_cast<XYPOSITION>(value);
		};
		ascent = std::ceil(metric(g_ids.fontAscent, 1));
		descent = std::ceil(metric(g_ids.fontDescent, 1));
		internalLeading = metric(g_ids.fontInternalLeading, 0);
		averageCharWidth = metric(g_ids.fontAverageCharWidth, 1);
		if (averageCharWidth <= 0) {
			averageCharWidth = 1;
		}
	}
	~FontAndroid() noexcept override {
		if (jfont) {
			if (JNIEnv *env = Env()) {
				env->DeleteGlobalRef(jfont);
			}
		}
	}
};

const FontAndroid *AsFont(const Font *font) noexcept {
	return static_cast<const FontAndroid *>(font);
}

/**
 * Text converted for Java, remembering for every source byte where its character ends
 * in UTF-16 units so that per-byte positions can be reported back to Scintilla.
 */
struct Decoded {
	std::u16string u16;
	std::vector<int> unitEnd;
};

void AppendCodePoint(std::u16string &out, unsigned int cp) {
	if (cp >= 0x10000) {
		const unsigned int v = cp - 0x10000;
		out.push_back(static_cast<char16_t>(0xD800 + (v >> 10)));
		out.push_back(static_cast<char16_t>(0xDC00 + (v & 0x3FF)));
	} else {
		out.push_back(static_cast<char16_t>(cp));
	}
}

Decoded Decode(std::string_view text, bool utf8, bool wantMap) {
	Decoded d;
	d.u16.reserve(text.size());
	if (wantMap) {
		d.unitEnd.resize(text.size());
	}
	size_t i = 0;
	while (i < text.size()) {
		size_t width = 1;
		unsigned int cp = static_cast<unsigned char>(text[i]);
		if (utf8 && cp >= 0x80) {
			const int classified = UTF8Classify(text.substr(i));
			if (classified & UTF8MaskInvalid) {
				cp = unicodeReplacementChar;
			} else {
				width = static_cast<size_t>(classified & UTF8MaskWidth);
				cp = static_cast<unsigned int>(UnicodeFromUTF8(text.substr(i, width)));
			}
		}
		AppendCodePoint(d.u16, cp);
		if (wantMap) {
			const int end = static_cast<int>(d.u16.size());
			for (size_t k = 0; k < width; k++) {
				d.unitEnd[i + k] = end;
			}
		}
		i += width;
	}
	return d;
}

jstring NewJString16(JNIEnv *env, const std::u16string &s) {
	return env->NewString(reinterpret_cast<const jchar *>(s.data()), static_cast<jsize>(s.size()));
}

bool AllSpaces(std::string_view text) noexcept {
	for (const char ch : text) {
		if (ch != ' ') {
			return false;
		}
	}
	return true;
}

class SurfaceAndroid final : public Surface {
	jobject jsurface = nullptr;	// Global reference to a SciSurface, or null when only measuring.
	bool ownsPixmap = false;
	bool initialised = false;
	SurfaceMode mode;

	bool Utf8Mode() const noexcept {
		return mode.codePage == SC_CP_UTF8;
	}
	void DrawString(const Font *font_, XYPOSITION x, XYPOSITION ybase, std::string_view text, ColourRGBA fore, bool utf8);
	void Measure(const Font *font_, std::string_view text, XYPOSITION *positions, bool utf8);
	XYPOSITION Width(const Font *font_, std::string_view text, bool utf8);
public:
	SurfaceAndroid() noexcept = default;
	SurfaceAndroid(JNIEnv *env, jobject pixmap, SurfaceMode mode_) :
		jsurface(env->NewGlobalRef(pixmap)), ownsPixmap(true), initialised(true), mode(mode_) {
	}
	~SurfaceAndroid() noexcept override {
		Release();
	}

	jobject Peer() const noexcept {
		return jsurface;
	}

	void Init(WindowID wid) override;
	void Init(SurfaceID sid, WindowID wid) override;
	std::unique_ptr<Surface> AllocatePixMap(int width, int height) override;

	void SetMode(SurfaceMode mode_) override;

	void Release() noexcept override;
	int SupportsFeature(Supports feature) noexcept override;
	bool Initialised() override;
	int LogPixelsY() override;
	int PixelDivisions() override;
	int DeviceHeightFont(int points) override;
	void LineDraw(Point start, Point end, Stroke stroke) override;
	void PolyLine(const Point *pts, size_t npts, Stroke stroke) override;
	void Polygon(const Point *pts, size_t npts, FillStroke fillStroke) override;
	void RectangleDraw(PRectangle rc, FillStroke fillStroke) override;
	void RectangleFrame(PRectangle rc, Stroke stroke) override;
	void FillRectangle(PRectangle rc, Fill fill) override;
	void FillRectangleAligned(PRectangle rc, Fill fill) override;
	void FillRectangle(PRectangle rc, Surface &surfacePattern) override;
	void RoundedRectangle(PRectangle rc, FillStroke fillStroke) override;
	void AlphaRectangle(PRectangle rc, XYPOSITION cornerSize, FillStroke fillStroke) override;
	void GradientRectangle(PRectangle rc, const std::vector<ColourStop> &stops, GradientOptions options) override;
	void DrawRGBAImage(PRectangle rc, int width, int height, const unsigned char *pixelsImage) override;
	void Ellipse(PRectangle rc, FillStroke fillStroke) override;
	void Stadium(PRectangle rc, FillStroke fillStroke, Ends ends) override;
	void Copy(PRectangle rc, Point from, Surface &surfaceSource) override;

	std::unique_ptr<IScreenLineLayout> Layout(const IScreenLine *screenLine) override;

	void DrawTextNoClip(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore, ColourRGBA back) override;
	void DrawTextClipped(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore, ColourRGBA back) override;
	void DrawTextTransparent(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore) override;
	void MeasureWidths(const Font *font_, std::string_view text, XYPOSITION *positions) override;
	XYPOSITION WidthText(const Font *font_, std::string_view text) override;

	void DrawTextNoClipUTF8(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore, ColourRGBA back) override;
	void DrawTextClippedUTF8(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore, ColourRGBA back) override;
	void DrawTextTransparentUTF8(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore) override;
	void MeasureWidthsUTF8(const Font *font_, std::string_view text, XYPOSITION *positions) override;
	XYPOSITION WidthTextUTF8(const Font *font_, std::string_view text) override;

	XYPOSITION Ascent(const Font *font_) override;
	XYPOSITION Descent(const Font *font_) override;
	XYPOSITION InternalLeading(const Font *font_) override;
	XYPOSITION Height(const Font *font_) override;
	XYPOSITION AverageCharWidth(const Font *font_) override;

	void SetClip(PRectangle rc) override;
	void PopClip() override;
	void FlushCachedState() override;
	void FlushDrawing() override;
};

void SurfaceAndroid::Init(WindowID) {
	Release();
	initialised = true;
}

void SurfaceAndroid::Init(SurfaceID sid, WindowID) {
	Release();
	JNIEnv *env = Env();
	if (env && sid) {
		jsurface = env->NewGlobalRef(static_cast<jobject>(sid));
	}
	initialised = true;
}

std::unique_ptr<Surface> SurfaceAndroid::AllocatePixMap(int width, int height) {
	JNIEnv *env = Env();
	if (env && g_platform && width > 0 && height > 0) {
		const Local<jobject> pixmap(env, env->CallObjectMethod(g_platform, g_ids.platCreatePixmap,
			static_cast<jint>(width), static_cast<jint>(height)));
		if (!ClearException(env) && pixmap) {
			return std::make_unique<SurfaceAndroid>(env, pixmap.get(), mode);
		}
	}
	auto measuring = std::make_unique<SurfaceAndroid>();
	measuring->Init(nullptr);
	measuring->SetMode(mode);
	return measuring;
}

void SurfaceAndroid::SetMode(SurfaceMode mode_) {
	mode = mode_;
}

void SurfaceAndroid::Release() noexcept {
	if (jsurface) {
		if (JNIEnv *env = Env()) {
			if (ownsPixmap) {
				env->CallVoidMethod(jsurface, g_ids.surfRelease);
				ClearException(env);
			}
			env->DeleteGlobalRef(jsurface);
		}
		jsurface = nullptr;
	}
	ownsPixmap = false;
	initialised = false;
}

int SurfaceAndroid::SupportsFeature(Supports feature) noexcept {
	switch (feature) {
	case Supports::LineDrawsFinal:
	case Supports::FractionalStrokeWidth:
	case Supports::TranslucentStroke:
	case Supports::PixelModification:
		return 1;
	default:
		return 0;
	}
}

bool SurfaceAndroid::Initialised() {
	return initialised;
}

int SurfaceAndroid::LogPixelsY() {
	return static_cast<int>(std::lround(72.0 * g_pixelsPerPoint));
}

int SurfaceAndroid::PixelDivisions() {
	return 1;
}

int SurfaceAndroid::DeviceHeightFont(int points) {
	// Scintilla passes hundredths of a point and divides by 100 again when creating the font,
	// so scaling here turns style sizes into device pixels.
	return static_cast<int>(std::lround(points * g_pixelsPerPoint));
}

void SurfaceAndroid::LineDraw(Point start, Point end, Stroke stroke) {
	if (JNIEnv *env = jsurface ? Env() : nullptr) {
		env->CallVoidMethod(jsurface, g_ids.surfLine, F(start.x), F(start.y), F(end.x), F(end.y),
			Argb(stroke.colour), stroke.WidthF());
		ClearException(env);
	}
}

namespace {

void DrawPoly(jobject jsurface, const Point *pts, size_t npts, jint fill, jint strokeColour, float width, bool closed) {
	JNIEnv *env = jsurface ? Env() : nullptr;
	if (!env || npts < 2) {
		return;
	}
	std::vector<jfloat> xy(npts * 2);
	for (size_t i = 0; i < npts; i++) {
		xy[i * 2] = F(pts[i].x);
		xy[i * 2 + 1] = F(pts[i].y);
	}
	const Local<jfloatArray> arr(env, env->NewFloatArray(static_cast<jsize>(xy.size())));
	if (!arr) {
		ClearException(env);
		return;
	}
	env->SetFloatArrayRegion(arr.get(), 0, static_cast<jsize>(xy.size()), xy.data());
	env->CallVoidMethod(jsurface, g_ids.surfPolygon, arr.get(), static_cast<jint>(npts), fill, strokeColour,
		width, static_cast<jboolean>(closed));
	ClearException(env);
}

}

void SurfaceAndroid::PolyLine(const Point *pts, size_t npts, Stroke stroke) {
	DrawPoly(jsurface, pts, npts, noColour, Argb(stroke.colour), stroke.WidthF(), false);
}

void SurfaceAndroid::Polygon(const Point *pts, size_t npts, FillStroke fillStroke) {
	DrawPoly(jsurface, pts, npts, Argb(fillStroke.fill.colour), Argb(fillStroke.stroke.colour),
		fillStroke.stroke.WidthF(), true);
}

void SurfaceAndroid::RectangleDraw(PRectangle rc, FillStroke fillStroke) {
	if (JNIEnv *env = jsurface ? Env() : nullptr) {
		env->CallVoidMethod(jsurface, g_ids.surfFillRect, F(rc.left), F(rc.top), F(rc.right), F(rc.bottom),
			Argb(fillStroke.fill.colour));
		ClearException(env);
		const PRectangle rcStroke = rc.Inset(fillStroke.stroke.width / 2);
		env->CallVoidMethod(jsurface, g_ids.surfStrokeRect, F(rcStroke.left), F(rcStroke.top), F(rcStroke.right),
			F(rcStroke.bottom), Argb(fillStroke.stroke.colour), fillStroke.stroke.WidthF());
		ClearException(env);
	}
}

void SurfaceAndroid::RectangleFrame(PRectangle rc, Stroke stroke) {
	if (JNIEnv *env = jsurface ? Env() : nullptr) {
		const PRectangle rcStroke = rc.Inset(stroke.width / 2);
		env->CallVoidMethod(jsurface, g_ids.surfStrokeRect, F(rcStroke.left), F(rcStroke.top), F(rcStroke.right),
			F(rcStroke.bottom), Argb(stroke.colour), stroke.WidthF());
		ClearException(env);
	}
}

void SurfaceAndroid::FillRectangle(PRectangle rc, Fill fill) {
	if (JNIEnv *env = jsurface ? Env() : nullptr) {
		env->CallVoidMethod(jsurface, g_ids.surfFillRect, F(rc.left), F(rc.top), F(rc.right), F(rc.bottom),
			Argb(fill.colour));
		ClearException(env);
	}
}

void SurfaceAndroid::FillRectangleAligned(PRectangle rc, Fill fill) {
	FillRectangle(PixelAlign(rc, PixelDivisions()), fill);
}

void SurfaceAndroid::FillRectangle(PRectangle rc, Surface &surfacePattern) {
	JNIEnv *env = jsurface ? Env() : nullptr;
	if (!env) {
		return;
	}
	const jobject pattern = static_cast<SurfaceAndroid &>(surfacePattern).Peer();
	if (pattern) {
		env->CallVoidMethod(jsurface, g_ids.surfFillPattern, F(rc.left), F(rc.top), F(rc.right), F(rc.bottom), pattern);
		ClearException(env);
	} else {
		FillRectangle(rc, Fill(ColourRGBA(0xc0, 0xc0, 0xc0)));
	}
}

void SurfaceAndroid::RoundedRectangle(PRectangle rc, FillStroke fillStroke) {
	AlphaRectangle(rc, 3.0, fillStroke);
}

void SurfaceAndroid::AlphaRectangle(PRectangle rc, XYPOSITION cornerSize, FillStroke fillStroke) {
	if (JNIEnv *env = jsurface ? Env() : nullptr) {
		const PRectangle rcStroke = rc.Inset(fillStroke.stroke.width / 2);
		env->CallVoidMethod(jsurface, g_ids.surfRoundRect, F(rcStroke.left), F(rcStroke.top), F(rcStroke.right),
			F(rcStroke.bottom), F(cornerSize), Argb(fillStroke.fill.colour), Argb(fillStroke.stroke.colour),
			fillStroke.stroke.WidthF());
		ClearException(env);
	}
}

void SurfaceAndroid::GradientRectangle(PRectangle rc, const std::vector<ColourStop> &stops, GradientOptions options) {
	JNIEnv *env = jsurface ? Env() : nullptr;
	if (!env || stops.empty()) {
		return;
	}
	std::vector<jint> colours;
	std::vector<jfloat> positions;
	for (const ColourStop &stop : stops) {
		colours.push_back(Argb(stop.colour));
		positions.push_back(F(stop.position));
	}
	const jsize count = static_cast<jsize>(stops.size());
	const Local<jintArray> jcolours(env, env->NewIntArray(count));
	const Local<jfloatArray> jpositions(env, env->NewFloatArray(count));
	if (!jcolours || !jpositions) {
		ClearException(env);
		return;
	}
	env->SetIntArrayRegion(jcolours.get(), 0, count, colours.data());
	env->SetFloatArrayRegion(jpositions.get(), 0, count, positions.data());
	env->CallVoidMethod(jsurface, g_ids.surfGradientRect, F(rc.left), F(rc.top), F(rc.right), F(rc.bottom),
		jcolours.get(), jpositions.get(), static_cast<jboolean>(options == GradientOptions::leftToRight));
	ClearException(env);
}

void SurfaceAndroid::DrawRGBAImage(PRectangle rc, int width, int height, const unsigned char *pixelsImage) {
	JNIEnv *env = jsurface ? Env() : nullptr;
	if (!env || width <= 0 || height <= 0 || !pixelsImage) {
		return;
	}
	const size_t count = static_cast<size_t>(width) * static_cast<size_t>(height);
	std::vector<jint> argb(count);
	for (size_t i = 0; i < count; i++) {
		const unsigned char *px = pixelsImage + i * 4;
		const unsigned int v = (static_cast<unsigned int>(px[3]) << 24) | (static_cast<unsigned int>(px[0]) << 16) |
			(static_cast<unsigned int>(px[1]) << 8) | static_cast<unsigned int>(px[2]);
		argb[i] = static_cast<jint>(v);
	}
	const Local<jintArray> arr(env, env->NewIntArray(static_cast<jsize>(count)));
	if (!arr) {
		ClearException(env);
		return;
	}
	env->SetIntArrayRegion(arr.get(), 0, static_cast<jsize>(count), argb.data());
	env->CallVoidMethod(jsurface, g_ids.surfImage, F(rc.left), F(rc.top), F(rc.right), F(rc.bottom),
		static_cast<jint>(width), static_cast<jint>(height), arr.get());
	ClearException(env);
}

void SurfaceAndroid::Ellipse(PRectangle rc, FillStroke fillStroke) {
	if (JNIEnv *env = jsurface ? Env() : nullptr) {
		const PRectangle rcStroke = rc.Inset(fillStroke.stroke.width / 2);
		env->CallVoidMethod(jsurface, g_ids.surfEllipse, F(rcStroke.left), F(rcStroke.top), F(rcStroke.right),
			F(rcStroke.bottom), Argb(fillStroke.fill.colour), Argb(fillStroke.stroke.colour), fillStroke.stroke.WidthF());
		ClearException(env);
	}
}

void SurfaceAndroid::Stadium(PRectangle rc, FillStroke fillStroke, Ends) {
	// Flat and angled ends are drawn as semicircles for now.
	AlphaRectangle(rc, rc.Height() / 2, fillStroke);
}

void SurfaceAndroid::Copy(PRectangle rc, Point from, Surface &surfaceSource) {
	JNIEnv *env = jsurface ? Env() : nullptr;
	const jobject source = static_cast<SurfaceAndroid &>(surfaceSource).Peer();
	if (env && source) {
		env->CallVoidMethod(jsurface, g_ids.surfCopy, F(rc.left), F(rc.top), F(rc.right), F(rc.bottom),
			F(from.x), F(from.y), source);
		ClearException(env);
	}
}

std::unique_ptr<IScreenLineLayout> SurfaceAndroid::Layout(const IScreenLine *) {
	// Bidirectional layout is not supported yet.
	return {};
}

void SurfaceAndroid::DrawString(const Font *font_, XYPOSITION x, XYPOSITION ybase, std::string_view text, ColourRGBA fore, bool utf8) {
	JNIEnv *env = jsurface ? Env() : nullptr;
	const FontAndroid *font = AsFont(font_);
	if (!env || !font || !font->jfont || text.empty() || AllSpaces(text)) {
		return;
	}
	const Decoded decoded = Decode(text, utf8, false);
	const Local<jstring> jtext(env, NewJString16(env, decoded.u16));
	if (!jtext) {
		ClearException(env);
		return;
	}
	env->CallVoidMethod(jsurface, g_ids.surfText, font->jfont, F(x), F(ybase), jtext.get(), Argb(fore));
	ClearException(env);
}

void SurfaceAndroid::Measure(const Font *font_, std::string_view text, XYPOSITION *positions, bool utf8) {
	if (text.empty()) {
		return;
	}
	JNIEnv *env = Env();
	const FontAndroid *font = AsFont(font_);
	const Decoded decoded = Decode(text, utf8, true);
	const jsize units = static_cast<jsize>(decoded.u16.size());
	std::vector<jfloat> advances(static_cast<size_t>(units), 0.0f);
	bool measured = false;
	if (env && font && font->jfont) {
		const Local<jstring> jtext(env, NewJString16(env, decoded.u16));
		const Local<jfloatArray> arr(env, env->NewFloatArray(units));
		if (jtext && arr) {
			env->CallVoidMethod(font->jfont, g_ids.fontAdvances, jtext.get(), arr.get());
			if (!ClearException(env)) {
				env->GetFloatArrayRegion(arr.get(), 0, units, advances.data());
				measured = true;
			}
		} else {
			ClearException(env);
		}
	}
	if (!measured) {
		const jfloat fallback = font ? F(font->averageCharWidth) : 1.0f;
		std::fill(advances.begin(), advances.end(), fallback);
	}
	// Prefix sums: cumulative[k] is the x position after k UTF-16 units.
	std::vector<XYPOSITION> cumulative(static_cast<size_t>(units) + 1, 0.0);
	for (jsize k = 0; k < units; k++) {
		cumulative[static_cast<size_t>(k) + 1] = cumulative[static_cast<size_t>(k)] + advances[static_cast<size_t>(k)];
	}
	for (size_t i = 0; i < text.size(); i++) {
		positions[i] = cumulative[static_cast<size_t>(decoded.unitEnd[i])];
	}
}

XYPOSITION SurfaceAndroid::Width(const Font *font_, std::string_view text, bool utf8) {
	JNIEnv *env = Env();
	const FontAndroid *font = AsFont(font_);
	if (!env || !font || !font->jfont || text.empty()) {
		return 0;
	}
	const Decoded decoded = Decode(text, utf8, false);
	const Local<jstring> jtext(env, NewJString16(env, decoded.u16));
	if (!jtext) {
		ClearException(env);
		return 0;
	}
	const jfloat width = env->CallFloatMethod(font->jfont, g_ids.fontWidth, jtext.get());
	if (ClearException(env)) {
		return 0;
	}
	return width;
}

void SurfaceAndroid::DrawTextNoClip(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore, ColourRGBA back) {
	FillRectangle(rc, back);
	DrawString(font_, rc.left, ybase, text, fore, Utf8Mode());
}

void SurfaceAndroid::DrawTextClipped(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore, ColourRGBA back) {
	SetClip(rc);
	DrawTextNoClip(rc, font_, ybase, text, fore, back);
	PopClip();
}

void SurfaceAndroid::DrawTextTransparent(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore) {
	DrawString(font_, rc.left, ybase, text, fore, Utf8Mode());
}

void SurfaceAndroid::MeasureWidths(const Font *font_, std::string_view text, XYPOSITION *positions) {
	Measure(font_, text, positions, Utf8Mode());
}

XYPOSITION SurfaceAndroid::WidthText(const Font *font_, std::string_view text) {
	return Width(font_, text, Utf8Mode());
}

void SurfaceAndroid::DrawTextNoClipUTF8(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore, ColourRGBA back) {
	FillRectangle(rc, back);
	DrawString(font_, rc.left, ybase, text, fore, true);
}

void SurfaceAndroid::DrawTextClippedUTF8(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore, ColourRGBA back) {
	SetClip(rc);
	DrawTextNoClipUTF8(rc, font_, ybase, text, fore, back);
	PopClip();
}

void SurfaceAndroid::DrawTextTransparentUTF8(PRectangle rc, const Font *font_, XYPOSITION ybase, std::string_view text, ColourRGBA fore) {
	DrawString(font_, rc.left, ybase, text, fore, true);
}

void SurfaceAndroid::MeasureWidthsUTF8(const Font *font_, std::string_view text, XYPOSITION *positions) {
	Measure(font_, text, positions, true);
}

XYPOSITION SurfaceAndroid::WidthTextUTF8(const Font *font_, std::string_view text) {
	return Width(font_, text, true);
}

XYPOSITION SurfaceAndroid::Ascent(const Font *font_) {
	return font_ ? AsFont(font_)->ascent : 1;
}

XYPOSITION SurfaceAndroid::Descent(const Font *font_) {
	return font_ ? AsFont(font_)->descent : 1;
}

XYPOSITION SurfaceAndroid::InternalLeading(const Font *font_) {
	return font_ ? AsFont(font_)->internalLeading : 0;
}

XYPOSITION SurfaceAndroid::Height(const Font *font_) {
	return Ascent(font_) + Descent(font_);
}

XYPOSITION SurfaceAndroid::AverageCharWidth(const Font *font_) {
	return font_ ? AsFont(font_)->averageCharWidth : 1;
}

void SurfaceAndroid::SetClip(PRectangle rc) {
	if (JNIEnv *env = jsurface ? Env() : nullptr) {
		env->CallVoidMethod(jsurface, g_ids.surfPushClip, F(rc.left), F(rc.top), F(rc.right), F(rc.bottom));
		ClearException(env);
	}
}

void SurfaceAndroid::PopClip() {
	if (JNIEnv *env = jsurface ? Env() : nullptr) {
		env->CallVoidMethod(jsurface, g_ids.surfPopClip);
		ClearException(env);
	}
}

void SurfaceAndroid::FlushCachedState() {
}

void SurfaceAndroid::FlushDrawing() {
}

/** Autocompletion lists are not implemented yet; this keeps the core's AutoComplete object harmless. */
class ListBoxStub final : public ListBox {
	int visibleRows = 5;
	std::vector<std::string> items;
	int selected = -1;
public:
	void SetFont(const Font *) override {}
	void Create(Window &, int, Point, int, bool, Technology) override {}
	void SetAverageCharWidth(int) override {}
	void SetVisibleRows(int rows) override { visibleRows = rows; }
	int GetVisibleRows() const override { return visibleRows; }
	PRectangle GetDesiredRect() override { return PRectangle(); }
	int CaretFromEdge() override { return 0; }
	void Clear() noexcept override { items.clear(); selected = -1; }
	void Append(char *s, int) override { items.emplace_back(s ? s : ""); }
	int Length() override { return static_cast<int>(items.size()); }
	void Select(int n) override { selected = n; }
	int GetSelection() override { return selected; }
	int Find(const char *) override { return -1; }
	std::string GetValue(int n) override {
		return (n >= 0 && n < static_cast<int>(items.size())) ? items[static_cast<size_t>(n)] : std::string();
	}
	void RegisterImage(int, const char *) override {}
	void RegisterRGBAImage(int, int, int, const unsigned char *) override {}
	void ClearRegisteredImages() override {}
	void SetDelegate(IListBoxDelegate *) override {}
	void SetList(const char *, char, char) override {}
	void SetOptions(ListOptions) override {}
};

}

namespace Scintilla::Internal {

std::shared_ptr<Font> Font::Allocate(const FontParameters &fp) {
	return std::make_shared<FontAndroid>(fp);
}

std::unique_ptr<Surface> Surface::Allocate(Technology) {
	return std::make_unique<SurfaceAndroid>();
}

Window::~Window() noexcept = default;

void Window::Destroy() noexcept {
	wid = nullptr;
}

PRectangle Window::GetPosition() const {
	return wid ? static_cast<HostWindow *>(wid)->HostBounds() : PRectangle();
}

void Window::SetPosition(PRectangle) {
}

void Window::SetPositionRelative(PRectangle, const Window *) {
}

PRectangle Window::GetClientPosition() const {
	return GetPosition();
}

void Window::Show(bool) {
}

void Window::InvalidateAll() {
	if (wid) {
		static_cast<HostWindow *>(wid)->HostInvalidate();
	}
}

void Window::InvalidateRectangle(PRectangle) {
	// Android redraws whole hardware-accelerated views, so partial invalidation gains nothing.
	InvalidateAll();
}

void Window::SetCursor(Cursor curs) {
	cursorLast = curs;
}

PRectangle Window::GetMonitorRect(Point) {
	return GetPosition();
}

ListBox::ListBox() noexcept = default;
ListBox::~ListBox() noexcept = default;

std::unique_ptr<ListBox> ListBox::Allocate() {
	return std::make_unique<ListBoxStub>();
}

Menu::Menu() noexcept : mid(nullptr) {
}

void Menu::CreatePopUp() {
	Destroy();
}

void Menu::Destroy() noexcept {
	mid = nullptr;
}

void Menu::Show(Point, const Window &) {
}

ColourRGBA Platform::Chrome() {
	return ColourRGBA(0xe0, 0xe0, 0xe0);
}

ColourRGBA Platform::ChromeHighlight() {
	return ColourRGBA(0xff, 0xff, 0xff);
}

const char *Platform::DefaultFont() {
	return "monospace";
}

int Platform::DefaultFontSize() {
	return 13;
}

unsigned int Platform::DoubleClickTime() {
	return 400;
}

void Platform::DebugDisplay(const char *s) noexcept {
	Log("%s", s);
}

void Platform::DebugPrintf(const char *format, ...) noexcept {
	char buffer[2000];
	va_list args;
	va_start(args, format);
	vsnprintf(buffer, std::size(buffer), format, args);
	va_end(args);
	Log("%s", buffer);
}

bool Platform::ShowAssertionPopUps(bool) noexcept {
	return false;
}

void Platform::Assert(const char *c, const char *file, int line) noexcept {
	Log("Assertion [%s] failed at %s %d", c, file, line);
}

}
