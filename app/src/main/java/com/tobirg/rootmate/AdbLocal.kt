package com.tobirg.rootmate

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Reboots the TV without root: it talks to the TV's own adbd over the loopback interface as an ordinary adb
 * client (the TV has "ADB over network" on, adbd listens on tcp 5555) and asks for the `reboot:` service. The first
 * time adbd shows the usual "Allow USB debugging?" dialog on the TV for this app's key; after "Always allow"
 * it does not ask again. Only the parts of the adb protocol that are needed are implemented (CNXN, AUTH, OPEN).
 */
object AdbLocal {

    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43

    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3

    private const val VERSION = 0x01000001
    private const val MAX_PAYLOAD = 256 * 1024
    private const val PORT = 5555

    class AdbException(message: String) : Exception(message)

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun send(sock: Socket, cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(cmd xor -1)
        val out = sock.getOutputStream()
        out.write(h.array())
        if (data.isNotEmpty()) out.write(data)
        out.flush()
    }

    private fun readFully(sock: Socket, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        val input = sock.getInputStream()
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) throw IOException("closed")
            got += r
        }
        return buf
    }

    private fun receive(sock: Socket): Msg {
        val h = ByteBuffer.wrap(readFully(sock, 24)).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int
        val a0 = h.int
        val a1 = h.int
        val len = h.int
        h.int
        h.int
        if (len < 0 || len > MAX_PAYLOAD) throw IOException("bad length")
        return Msg(cmd, a0, a1, if (len > 0) readFully(sock, len) else ByteArray(0))
    }

    // ---- key handling ----

    private fun loadOrCreateKey(context: Context): KeyPair {
        val priv = File(context.filesDir, "adbkey.pk8")
        val pub = File(context.filesDir, "adbkey.x509")
        try {
            if (priv.isFile && pub.isFile) {
                val kf = KeyFactory.getInstance("RSA")
                return KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                    kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes()))
                )
            }
        } catch (e: Exception) {
            // regenerate below
        }
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val kp = gen.generateKeyPair()
        priv.writeBytes(kp.private.encoded)
        pub.writeBytes(kp.public.encoded)
        return kp
    }

    /** adbd checks a PKCS#1 v1.5 signature over the raw 20-byte token, as if it were a SHA-1 digest. */
    private fun signToken(kp: KeyPair, token: ByteArray): ByteArray {
        val prefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val s = Signature.getInstance("NONEwithRSA")
        s.initSign(kp.private)
        s.update(prefix)
        s.update(token)
        return s.sign()
    }

    private fun littleEndian(v: BigInteger, size: Int): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until minOf(size, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    /** adb's own public key layout (struct RSAPublicKey: len, n0inv, n, rr, exponent), base64, with a name appended. */
    private fun publicKeyPayload(kp: KeyPair): ByteArray {
        val pub = kp.public as RSAPublicKey
        val n = pub.modulus
        val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong()
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words)
        b.putInt(n0inv.toInt())
        b.put(littleEndian(n, 256))
        b.put(littleEndian(rr, 256))
        b.putInt(pub.publicExponent.toInt())
        return (Base64.encodeToString(b.array(), Base64.NO_WRAP) + " rootmate@tv").toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    }

    /** What the TV still needs for adb: developer options, USB debugging, adbd listening on tcp 5555. */
    class Status(val developer: Boolean, val usbDebugging: Boolean, val listening: Boolean) {
        val ready get() = developer && usbDebugging && listening
    }

    /** Checks the settings and tries the port (up to 500 ms): call off the UI thread. */
    fun status(context: Context): Status {
        fun global(name: String) = try {
            android.provider.Settings.Global.getInt(context.contentResolver, name, 0) == 1
        } catch (e: Exception) {
            false
        }
        val dev = global(android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        val usb = global(android.provider.Settings.Global.ADB_ENABLED)
        val open = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 500); true }
        } catch (e: IOException) {
            false
        }
        return Status(dev, usb, open)
    }

    /**
     * Connects to the local adbd, authenticates and starts the reboot. Blocks (call off the UI thread):
     * up to [authWaitMs] while the user answers the debugging dialog. [onAskUser] runs when that dialog is expected.
     */
    fun reboot(context: Context, authWaitMs: Int, onAskUser: () -> Unit) {
        val kp = loadOrCreateKey(context)
        val sock = Socket()
        try {
            try {
                sock.connect(InetSocketAddress("127.0.0.1", PORT), 3000)
            } catch (e: IOException) {
                throw AdbException(context.getString(R.string.err_adb_unreachable))
            }
            sock.soTimeout = 8000
            send(sock, A_CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.US_ASCII))
            var signed = false
            var keySent = false
            while (true) {
                val m = try {
                    receive(sock)
                } catch (e: java.net.SocketTimeoutException) {
                    throw AdbException(context.getString(R.string.err_adb_no_answer))
                }
                when (m.cmd) {
                    A_CNXN -> break
                    A_AUTH -> {
                        if (m.arg0 != AUTH_TOKEN) continue
                        if (!signed) {
                            signed = true
                            send(sock, A_AUTH, AUTH_SIGNATURE, 0, signToken(kp, m.data))
                        } else if (!keySent) {
                            keySent = true
                            send(sock, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload(kp))
                            sock.soTimeout = authWaitMs
                            onAskUser()
                        } else {
                            throw AdbException(context.getString(R.string.err_adb_denied))
                        }
                    }
                    else -> {}
                }
            }
            sock.soTimeout = 5000
            send(sock, A_OPEN, 1, 0, "reboot:\u0000".toByteArray(Charsets.US_ASCII))
            try {
                // adbd answers OKAY (or just drops the connection when the reboot starts): either means it was accepted
                while (true) {
                    val m = receive(sock)
                    if (m.cmd == A_OKAY || m.cmd == A_CLSE) break
                }
            } catch (e: IOException) {
                // connection dropped by the reboot
            }
        } finally {
            try { sock.close() } catch (e: Exception) { }
        }
    }
}
