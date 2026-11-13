package host;

import io.github.revelationnow.pocketpadpp.scintilla.SciFont;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;

/** Desktop stand-in for the Android font, used only by the off-device tests. */
final class HostFont implements SciFont {
    static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    final Font font;
    private final LineMetrics metrics;

    HostFont(String face, float sizePx, int weight, boolean italic) {
        int style = (weight >= 600 ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
        String family = (face == null || face.isEmpty() || face.equals("monospace")) ? "DejaVu Sans Mono" : face;
        font = new Font(family, style, 1).deriveFont(sizePx);
        metrics = font.getLineMetrics("Ag", FRC);
    }

    @Override public float ascent() { return metrics.getAscent(); }
    @Override public float descent() { return metrics.getDescent(); }
    @Override public float internalLeading() { return 0; }
    @Override public float averageCharWidth() { return width("n"); }

    @Override public float width(String text) {
        return (float) font.getStringBounds(text, FRC).getWidth();
    }

    @Override public void advances(String text, float[] out) {
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int units = Character.charCount(cp);
            out[i] = (float) font.getStringBounds(text.substring(i, i + units), FRC).getWidth();
            for (int k = 1; k < units; k++) {
                out[i + k] = 0;
            }
            i += units;
        }
    }
}
