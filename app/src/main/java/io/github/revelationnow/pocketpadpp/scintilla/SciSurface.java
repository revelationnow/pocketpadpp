package io.github.revelationnow.pocketpadpp.scintilla;

/**
 * A drawing target. Colours are ARGB ints; a fill or stroke of 0 means "none".
 * Called from the native Scintilla platform layer.
 */
public interface SciSurface {
    void release();
    void fillRect(float l, float t, float r, float b, int argb);
    void strokeRect(float l, float t, float r, float b, int argb, float width);
    void line(float x1, float y1, float x2, float y2, int argb, float width);
    void polygon(float[] xy, int points, int fillArgb, int strokeArgb, float width, boolean closed);
    void roundRect(float l, float t, float r, float b, float radius, int fillArgb, int strokeArgb, float width);
    void ellipse(float l, float t, float r, float b, int fillArgb, int strokeArgb, float width);
    void gradientRect(float l, float t, float r, float b, int[] argb, float[] stops, boolean horizontal);
    void image(float l, float t, float r, float b, int width, int height, int[] argb);
    void copy(float l, float t, float r, float b, float fromX, float fromY, SciSurface source);
    void fillPattern(float l, float t, float r, float b, SciSurface pattern);
    void text(SciFont font, float x, float yBase, String text, int argb);
    void pushClip(float l, float t, float r, float b);
    void popClip();
}
