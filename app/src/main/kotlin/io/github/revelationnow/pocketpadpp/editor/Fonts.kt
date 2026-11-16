package io.github.revelationnow.pocketpadpp.editor

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import java.io.File

/**
 * Where the editor's typefaces come from.
 *
 * A fixed-width font comes with the app (DejaVu Sans Mono, four styles) and is the default. Android's own
 * "monospace" is not relied on: some phone makers' font themes replace it with a proportional font, and it
 * has no box-drawing characters, so columns would not line up there.
 *
 * A font is known by one name, which is also what Scintilla is given as the style's font:
 * [BUILT_IN], a name starting with [FILE_PREFIX] for a font file the user added, or one of Android's
 * family names ("monospace", "serif", ...).
 */
object Fonts {
    const val BUILT_IN = "DejaVu Sans Mono"
    const val FILE_PREFIX = "file:"

    /** The largest font file that is taken; collections for Chinese, Japanese and Korean run to tens of megabytes. */
    const val MAX_FILE_BYTES = 64L * 1024 * 1024

    private var assets: AssetManager? = null
    private var folder: File? = null
    private val cache = HashMap<String, Typeface>()

    fun init(context: Context) {
        if (assets != null) return
        val app = context.applicationContext
        assets = app.assets
        folder = File(app.filesDir, "fonts")
    }

    /** The folder font files added by the user are kept in. */
    fun folder(context: Context): File {
        init(context)
        return folder!!
    }

    /** The app's fixed-width font, for the parts around the editor that need columns to line up. */
    fun mono(context: Context, bold: Boolean = false, italic: Boolean = false): Typeface {
        init(context)
        return typeface(BUILT_IN, if (bold) 700 else 400, italic)
    }

    /** Reads a font file, or returns null if it is not one Android can use. */
    fun load(file: File): Typeface? = runCatching { Typeface.Builder(file).build() }.getOrNull()

    /** Whether every character of the typeface's own takes the same width, judged by a narrow and a wide letter. */
    fun isFixedWidth(typeface: Typeface): Boolean {
        val paint = Paint().apply { this.typeface = typeface; textSize = 100f }
        return paint.measureText("iiii") == paint.measureText("WWWW")
    }

    /** Drops what was loaded, after a font file was added or removed. */
    fun forget() = synchronized(cache) { cache.clear() }

    fun typeface(face: String, weight: Int, italic: Boolean): Typeface {
        val bold = weight >= 600
        val key = "$face|$weight|$italic"
        synchronized(cache) {
            cache[key]?.let { return it }
            val made = when {
                face.isEmpty() || face == BUILT_IN -> builtIn(bold, italic)
                face.startsWith(FILE_PREFIX) -> {
                    val base = folder?.let { load(File(it, face.removePrefix(FILE_PREFIX).substringAfterLast('/'))) }
                    // A file that has gone missing leaves the built-in font, not whatever the system picks.
                    if (base == null) builtIn(bold, italic) else styled(base, weight, italic)
                }
                // Unknown family names fall back to the system default typeface.
                else -> styled(Typeface.create(face, Typeface.NORMAL), weight, italic)
            }
            cache[key] = made
            return made
        }
    }

    private fun builtIn(bold: Boolean, italic: Boolean): Typeface {
        val name = "fonts/DejaVuSansMono" + when {
            bold && italic -> "-BoldOblique"
            bold -> "-Bold"
            italic -> "-Oblique"
            else -> ""
        } + ".ttf"
        val loaded = assets?.let { runCatching { Typeface.createFromAsset(it, name) }.getOrNull() }
        return loaded ?: styled(Typeface.MONOSPACE, if (bold) 700 else 400, italic)
    }

    /** A family's own bold and italic where it has them; Android thickens or slants the regular face where it has not. */
    private fun styled(family: Typeface, weight: Int, italic: Boolean): Typeface =
        if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(family, weight.coerceIn(1, 1000), italic)
        } else {
            Typeface.create(family, (if (weight >= 600) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))
        }
}
