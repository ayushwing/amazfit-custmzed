package com.ayushwing.pace.companion

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Wire format shared with the watch (watch-launcher/.../Frame.java):
 *
 *   'P' 'A' | type u8 | flags u8 | length u32 (big-endian) | payload (UTF-8 JSON)
 */
data class Frame(val type: Int, val json: String) {

    fun write(out: DataOutputStream) {
        val body = json.toByteArray(Charsets.UTF_8)
        out.writeByte('P'.code)
        out.writeByte('A'.code)
        out.writeByte(type)
        out.writeByte(0)
        out.writeInt(body.size)
        out.write(body)
        out.flush()
    }

    companion object {
        const val HELLO = 0x00
        const val NOTIF_PUSH = 0x01
        const val NOTIF_ACTION = 0x02
        const val NOTIF_REMOVE = 0x03
        const val PING = 0x7E
        const val PONG = 0x7F

        private const val MAX_PAYLOAD = 64 * 1024

        fun read(input: DataInputStream): Frame {
            if (input.readUnsignedByte() != 'P'.code || input.readUnsignedByte() != 'A'.code) {
                throw IOException("bad magic")
            }
            val type = input.readUnsignedByte()
            input.readUnsignedByte() // flags
            val len = input.readInt()
            if (len !in 0..MAX_PAYLOAD) throw IOException("bad length $len")
            val buf = ByteArray(len)
            input.readFully(buf)
            return Frame(type, String(buf, Charsets.UTF_8))
        }
    }
}
