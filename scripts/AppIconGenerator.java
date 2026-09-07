import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the application icon and writes it as a Windows {@code .ico} plus a {@code .png}.
 *
 * <p>Run with the single-file source launcher, no build step:
 * <pre>java scripts/AppIconGenerator.java chat-desktop/src/main/resources</pre>
 *
 * <p>The icon is generated rather than hand-drawn so it stays in step with {@code Theme.accent()},
 * and so the whole toolchain remains Java. The output is committed - it changes about never, and a
 * build-time dependency on drawing it would slow every compile for nothing.
 *
 * <p>There is no ICO writer in {@code ImageIO}. The container is simple enough to emit directly: a
 * six-byte directory header, one sixteen-byte entry per size, then the payloads. Windows Vista and
 * later accept PNG payloads inside an ICO, which avoids writing a DIB encoder.
 */
public final class AppIconGenerator {

    /** Theme.accent() in light mode; keep these in step. */
    private static final Color ACCENT = new Color(0x3390EC);
    private static final Color ACCENT_DEEP = new Color(0x2B82D9);

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private AppIconGenerator() {
    }

    public static void main(String[] args) throws IOException {
        File outDir = new File(args.length > 0 ? args[0] : ".");
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("Could not create " + outDir);
        }

        List<BufferedImage> images = new ArrayList<>();
        for (int size : SIZES) {
            images.add(draw(size));
        }

        File ico = new File(outDir, "tetherless.ico");
        writeIco(images, ico);
        System.out.println("wrote " + ico.getAbsolutePath());

        File png = new File(outDir, "tetherless-256.png");
        ImageIO.write(draw(256), "png", png);
        System.out.println("wrote " + png.getAbsolutePath());
    }

    /** A rounded accent tile carrying a white speech bubble. */
    private static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double s = size;
        g.setPaint(new GradientPaint(0f, 0f, ACCENT, 0f, (float) s, ACCENT_DEEP));
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, s * 0.24, s * 0.24));

        // The bubble is deliberately chunky: at 16px anything finer turns to porridge.
        double bw = s * 0.60;
        double bh = s * 0.44;
        double bx = (s - bw) / 2.0;
        double by = s * 0.22;
        double radius = bh * 0.42;

        Area bubble = new Area(new RoundRectangle2D.Double(bx, by, bw, bh, radius, radius));

        GeneralPath tail = new GeneralPath();
        tail.moveTo(bx + bw * 0.24, by + bh * 0.86);
        tail.lineTo(bx + bw * 0.20, by + bh * 1.42);
        tail.lineTo(bx + bw * 0.56, by + bh * 0.92);
        tail.closePath();
        bubble.add(new Area(tail));

        // A keyhole reads as a smudge below 32px, so only the larger sizes carry it.
        if (size >= 32) {
            double kd = bh * 0.30;
            double kx = bx + (bw - kd) / 2.0;
            double ky = by + bh * 0.24;
            Area keyhole = new Area(new Ellipse2D.Double(kx, ky, kd, kd));

            GeneralPath stem = new GeneralPath();
            stem.moveTo(kx + kd * 0.34, ky + kd * 0.70);
            stem.lineTo(kx + kd * 0.66, ky + kd * 0.70);
            stem.lineTo(kx + kd * 0.80, ky + kd * 1.75);
            stem.lineTo(kx + kd * 0.20, ky + kd * 1.75);
            stem.closePath();
            keyhole.add(new Area(stem));

            bubble.subtract(keyhole);
        }

        g.setColor(Color.WHITE);
        g.fill(bubble);
        g.dispose();
        return image;
    }

    // ------------------------------------------------------------- ICO output

    private static void writeIco(List<BufferedImage> images, File file) throws IOException {
        List<byte[]> payloads = new ArrayList<>();
        for (BufferedImage image : images) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            ImageIO.write(image, "png", buffer);
            payloads.add(buffer.toByteArray());
        }

        try (OutputStream out = new FileOutputStream(file)) {
            writeShortLe(out, 0);                 // reserved
            writeShortLe(out, 1);                 // 1 = icon
            writeShortLe(out, images.size());

            // Payloads start after the header and the full directory.
            int offset = 6 + 16 * images.size();
            for (int i = 0; i < images.size(); i++) {
                int side = images.get(i).getWidth();
                byte[] payload = payloads.get(i);

                out.write(side >= 256 ? 0 : side);   // 0 encodes 256
                out.write(side >= 256 ? 0 : side);
                out.write(0);                        // palette size; 0 for truecolour
                out.write(0);                        // reserved
                writeShortLe(out, 1);                // colour planes
                writeShortLe(out, 32);               // bits per pixel
                writeIntLe(out, payload.length);
                writeIntLe(out, offset);
                offset += payload.length;
            }

            for (byte[] payload : payloads) {
                out.write(payload);
            }
        }
    }

    private static void writeShortLe(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeIntLe(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
