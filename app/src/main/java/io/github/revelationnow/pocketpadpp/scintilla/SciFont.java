package io.github.revelationnow.pocketpadpp.scintilla;

/**
 * A realised font. Implemented per platform (Android Paint, desktop AWT for tests)
 * and called from the native Scintilla platform layer.
 */
public interface SciFont {
    float ascent();
    float descent();
    float internalLeading();
    float averageCharWidth();
    float width(String text);
    /**
     * Fills {@code out[i]} with the advance of UTF-16 code unit {@code i}.
     * Units that continue a cluster (low surrogates, combining marks) get 0.
     */
    void advances(String text, float[] out);
}
