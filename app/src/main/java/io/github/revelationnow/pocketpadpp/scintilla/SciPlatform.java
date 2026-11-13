package io.github.revelationnow.pocketpadpp.scintilla;

/** Process-wide factory the native layer uses for fonts and off-screen surfaces. */
public interface SciPlatform {
    SciFont createFont(String face, float sizePx, int weight, boolean italic);
    SciSurface createPixmap(int width, int height);
}
