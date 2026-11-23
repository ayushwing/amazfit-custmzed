package com.ayushwing.pace.launcher;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Wire format shared with the phone (see docs/architecture.md):
 *
 *   'P' 'A' | type u8 | flags u8 | length u32 (big-endian) | payload (UTF-8 JSON)
 *
 * RFCOMM is a reliable, ordered stream, so there is no checksum; the magic
 * bytes catch a desynchronised stream and the connection is dropped.
 */
final class Frame {
    static final int HELLO = 0x00;
    static final int NOTIF_PUSH = 0x01;
    static final int NOTIF_ACTION = 0x02;
    static final int NOTIF_REMOVE = 0x03;
    static final int CALL_START = 0x04;
    static final int CALL_END = 0x05;
    static final int CALL_ACTION = 0x06;
    static final int CALL_ACTIVE = 0x07;
    static final int PING = 0x7E;
    static final int PONG = 0x7F;

    private static final int MAX_PAYLOAD = 64 * 1024;

    final int type;
    final String json;

    Frame(int type, String json) {
        this.type = type;
        this.json = json;
    }

    static Frame read(DataInputStream in) throws IOException {
        if (in.readUnsignedByte() != 'P' || in.readUnsignedByte() != 'A') {
            throw new IOException("bad magic");
        }
        int type = in.readUnsignedByte();
        in.readUnsignedByte(); // flags, unused so far
        int len = in.readInt();
        if (len < 0 || len > MAX_PAYLOAD) throw new IOException("bad length " + len);
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new Frame(type, new String(buf, StandardCharsets.UTF_8));
    }

    void write(DataOutputStream out) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        out.writeByte('P');
        out.writeByte('A');
        out.writeByte(type);
        out.writeByte(0);
        out.writeInt(body.length);
        out.write(body);
        out.flush();
    }
}
