package host;

import io.github.revelationnow.pocketpadpp.scintilla.SciFont;
import io.github.revelationnow.pocketpadpp.scintilla.SciPlatform;
import io.github.revelationnow.pocketpadpp.scintilla.SciSurface;

final class HostPlatform implements SciPlatform {
    @Override public SciFont createFont(String face, float sizePx, int weight, boolean italic) {
        return new HostFont(face, sizePx, weight, italic);
    }

    @Override public SciSurface createPixmap(int width, int height) {
        return new HostSurface(width, height);
    }
}
