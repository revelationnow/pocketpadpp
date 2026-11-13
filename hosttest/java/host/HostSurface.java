package host;

import io.github.revelationnow.pocketpadpp.scintilla.SciFont;
import io.github.revelationnow.pocketpadpp.scintilla.SciSurface;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;

/** Desktop stand-in for the Android canvas surface, used only by the off-device tests. */
final class HostSurface implements SciSurface {
    static long calls;
    static long textCalls;

    final BufferedImage image;
    private final Graphics2D g;
    private final ArrayDeque<Shape> clips = new ArrayDeque<>();

    HostSurface(int width, int height) {
        image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    private static Color color(int argb) {
        return new Color(argb, true);
    }

    private void fillAndStroke(Shape shape, int fillArgb, int strokeArgb, float width) {
        if (fillArgb != 0) {
            g.setColor(color(fillArgb));
            g.fill(shape);
        }
        if (strokeArgb != 0 && width > 0) {
            g.setColor(color(strokeArgb));
            g.setStroke(new BasicStroke(width));
            g.draw(shape);
        }
    }

    @Override public void release() { g.dispose(); }

    @Override public void fillRect(float l, float t, float r, float b, int argb) {
        calls++;
        g.setColor(color(argb));
        g.fill(new Rectangle2D.Float(l, t, r - l, b - t));
    }

    @Override public void strokeRect(float l, float t, float r, float b, int argb, float width) {
        calls++;
        fillAndStroke(new Rectangle2D.Float(l, t, r - l, b - t), 0, argb, width);
    }

    @Override public void line(float x1, float y1, float x2, float y2, int argb, float width) {
        calls++;
        fillAndStroke(new Line2D.Float(x1, y1, x2, y2), 0, argb, width);
    }

    @Override public void polygon(float[] xy, int points, int fillArgb, int strokeArgb, float width, boolean closed) {
        calls++;
        Path2D.Float path = new Path2D.Float();
        path.moveTo(xy[0], xy[1]);
        for (int i = 1; i < points; i++) {
            path.lineTo(xy[i * 2], xy[i * 2 + 1]);
        }
        if (closed) {
            path.closePath();
        }
        fillAndStroke(path, fillArgb, strokeArgb, width);
    }

    @Override public void roundRect(float l, float t, float r, float b, float radius, int fillArgb, int strokeArgb, float width) {
        calls++;
        fillAndStroke(new RoundRectangle2D.Float(l, t, r - l, b - t, radius * 2, radius * 2), fillArgb, strokeArgb, width);
    }

    @Override public void ellipse(float l, float t, float r, float b, int fillArgb, int strokeArgb, float width) {
        calls++;
        fillAndStroke(new Ellipse2D.Float(l, t, r - l, b - t), fillArgb, strokeArgb, width);
    }

    @Override public void gradientRect(float l, float t, float r, float b, int[] argb, float[] stops, boolean horizontal) {
        calls++;
        if (argb.length < 2) {
            fillRect(l, t, r, b, argb[0]);
            return;
        }
        Color[] colors = new Color[argb.length];
        for (int i = 0; i < argb.length; i++) {
            colors[i] = color(argb[i]);
        }
        g.setPaint(horizontal
            ? new LinearGradientPaint(l, t, r, t, stops, colors)
            : new LinearGradientPaint(l, t, l, b, stops, colors));
        g.fill(new Rectangle2D.Float(l, t, r - l, b - t));
    }

    @Override public void image(float l, float t, float r, float b, int width, int height, int[] argb) {
        calls++;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, width, height, argb, 0, width);
        g.drawImage(img, (int) l, (int) t, (int) (r - l), (int) (b - t), null);
    }

    @Override public void copy(float l, float t, float r, float b, float fromX, float fromY, SciSurface source) {
        calls++;
        BufferedImage src = ((HostSurface) source).image;
        int w = (int) (r - l);
        int h = (int) (b - t);
        g.drawImage(src, (int) l, (int) t, (int) l + w, (int) t + h,
            (int) fromX, (int) fromY, (int) fromX + w, (int) fromY + h, null);
    }

    @Override public void fillPattern(float l, float t, float r, float b, SciSurface pattern) {
        calls++;
        BufferedImage src = ((HostSurface) pattern).image;
        g.setPaint(new TexturePaint(src, new Rectangle2D.Float(0, 0, src.getWidth(), src.getHeight())));
        g.fill(new Rectangle2D.Float(l, t, r - l, b - t));
    }

    @Override public void text(SciFont font, float x, float yBase, String text, int argb) {
        calls++;
        textCalls++;
        g.setColor(color(argb));
        g.setFont(((HostFont) font).font);
        g.drawString(text, x, yBase);
    }

    @Override public void pushClip(float l, float t, float r, float b) {
        calls++;
        clips.push(g.getClip() == null ? new Rectangle2D.Float(0, 0, image.getWidth(), image.getHeight()) : g.getClip());
        g.clip(new Rectangle2D.Float(l, t, r - l, b - t));
    }

    @Override public void popClip() {
        calls++;
        if (!clips.isEmpty()) {
            g.setClip(clips.pop());
        }
    }
}
