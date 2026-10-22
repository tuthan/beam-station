package io.github.tuthan.beamstation

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * ADB client that talks to the TV itself (127.0.0.1:5555, open while debugging is on). It's what lets the app switch
 * the home screen and the screensaver, and turn off apps, without a computer. The first time, the TV asks
 * "Allow USB debugging?" and you accept with "Always allow". HomeMode uses it with its own fixed commands; Wifi
 * adds network names and passwords, always single-quoted.
 * Protocol: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/protocol.txt
 */
internal class Adb private constructor(private val socket: Socket) : Closeable {
    /** Notified when the TV is showing the permission prompt (so the screen can explain what to do). */
    fun interface Approval {
        fun waiting()
    }

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private val input = DataInputStream(socket.getInputStream())
    private val out: OutputStream = socket.getOutputStream()
    private var nextLocalId = 1

    /** Runs a command in the TV's shell and returns its output (stdout and stderr together). */
    @Throws(IOException::class)
    fun shell(command: String): String {
        val local = nextLocalId++
        send(OPEN, local, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))
        var remote = 0
        val result = ByteArrayOutputStream()
        while (true) {
            val m = read()
            if (m.arg1 != local) continue
            if (m.command == OKAY) {
                remote = m.arg0
            } else if (m.command == WRTE) {
                result.write(m.data)
                send(OKAY, local, m.arg0, null)
            } else if (m.command == CLSE) {
                if (remote == 0 && m.arg0 == 0) throw IOException("ADB refused the command")
                send(CLSE, local, m.arg0, null)
                return result.toString("UTF-8")
            }
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (ignored: IOException) {
        }
    }

    private fun read(): Message {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        b.int // checksum (newer adbd doesn't check it)
        val magic = b.int
        if ((command xor -1) != magic) throw IOException("invalid ADB message")
        if (length < 0 || length > 1024 * 1024) throw IOException("ADB message too large")
        val data = ByteArray(length)
        input.readFully(data)
        return Message(command, arg0, arg1, data)
    }

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray?) {
        val length = data?.size ?: 0
        var sum = 0
        if (data != null) for (x in data) sum += x.toInt() and 0xff
        val b = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(sum).putInt(command xor -1)
        if (data != null) b.put(data)
        out.write(b.array())
        out.flush()
    }

    companion object {
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val STLS = 0x534c5453
        private const val VERSION = 0x01000001
        private const val MAX_DATA = 256 * 1024
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        /** Start of the PKCS#1 block of a SHA-1 signature (adbd treats the token as if it were the digest). */
        private val SHA1_PREFIX =
            byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        /** Connects and authenticates. If the TV doesn't know our key yet, waits up to `approvalMs` for you to accept. */
        @JvmStatic
        @Throws(IOException::class)
        fun connect(c: Context, approvalMs: Int, approval: Approval?): Adb {
            val keys = keys(c)
            val s = Socket()
            try {
                s.connect(InetSocketAddress("127.0.0.1", 5555), 3000)
            } catch (e: IOException) {
                s.close()
                throw IOException("debugging is off on the TV (port 5555 closed)")
            }
            val adb = try {
                s.soTimeout = 10_000
                Adb(s)
            } catch (e: Exception) {   // IOException or RuntimeException (nothing else is thrown here)
                s.close()
                throw e
            }
            try {
                adb.send(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray(Charsets.US_ASCII))
                var signed = false
                var offeredKey = false
                while (true) {
                    val m = try {
                        adb.read()
                    } catch (e: SocketTimeoutException) {
                        throw IOException(if (offeredKey) "debugging permission wasn't accepted on the TV"
                            else "the TV's ADB didn't respond")
                    }
                    if (m.command == CNXN) {
                        s.soTimeout = 20_000
                        return adb
                    }
                    if (m.command == STLS) throw IOException("the TV asked for TLS (wireless debugging); use USB debugging")
                    if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw IOException("unexpected reply from ADB")
                    if (!signed) {
                        adb.send(AUTH, AUTH_SIGNATURE, 0, sign(keys.private, m.data))
                        signed = true
                    } else if (!offeredKey) {
                        // Unknown key: send the public one; the TV shows "Allow USB debugging?".
                        adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob(keys.public))
                        offeredKey = true
                        approval?.waiting()
                        s.soTimeout = approvalMs
                    } else {
                        throw IOException("the TV rejected the app's key")
                    }
                }
            } catch (e: Exception) {   // IOException or RuntimeException (nothing else is thrown here)
                adb.close()
                throw e
            }
        }

        // MARK: - The app's key (kept in private storage; never goes to the log)

        @Synchronized
        private fun keys(c: Context): KeyPair {
            val dir = c.filesDir
            val privateFile = File(dir, "adbkey")
            val publicFile = File(dir, "adbkey.pub")
            try {
                val factory = KeyFactory.getInstance("RSA")
                if (privateFile.exists() && publicFile.exists()) {
                    try {
                        return KeyPair(factory.generatePublic(X509EncodedKeySpec(readFile(publicFile))),
                            factory.generatePrivate(PKCS8EncodedKeySpec(readFile(privateFile))))
                    } catch (e: GeneralSecurityException) {
                        // Corrupted file (the power went out mid-write): new key; the TV asks again.
                        AppLog.w("ADB key unreadable, generating a new one")
                    } catch (e: IOException) {
                        AppLog.w("ADB key unreadable, generating a new one")
                    }
                }
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(2048)
                val pair = generator.generateKeyPair()
                writeFile(privateFile, pair.private.encoded)
                writeFile(publicFile, pair.public.encoded)
                return pair
            } catch (e: GeneralSecurityException) {
                throw IOException("ADB key: " + e.message)
            }
        }

        private fun sign(key: PrivateKey, token: ByteArray): ByteArray {
            try {
                val block = ByteArray(256)
                block[1] = 1
                val separator = block.size - SHA1_PREFIX.size - token.size - 1
                for (i in 2 until separator) block[i] = 0xff.toByte()
                System.arraycopy(SHA1_PREFIX, 0, block, separator + 1, SHA1_PREFIX.size)
                System.arraycopy(token, 0, block, separator + 1 + SHA1_PREFIX.size, token.size)
                val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                return cipher.doFinal(block)
            } catch (e: GeneralSecurityException) {
                throw IOException("ADB signature: " + e.message)
            }
        }

        /** Public key in adbd's format (RSAPublicKey struct in base64, plus a name). */
        private fun publicKeyBlob(key: PublicKey): ByteArray {
            val rsa = key as RSAPublicKey
            val n = rsa.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
            val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
            val b = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(64)
            b.putInt(n0inv.toInt())
            b.put(littleEndian(n, 256))
            b.put(littleEndian(rr, 256))
            b.putInt(rsa.publicExponent.toInt())
            val text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " beam-station@tv\u0000"
            return text.toByteArray(Charsets.US_ASCII)
        }

        private fun littleEndian(value: BigInteger, size: Int): ByteArray {
            val big = value.toByteArray()
            val little = ByteArray(size)
            var i = 0
            while (i < size && i < big.size) {
                little[i] = big[big.size - 1 - i]
                i++
            }
            return little
        }

        private fun readFile(f: File): ByteArray {
            FileInputStream(f).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            }
        }

        /** Writes all or nothing: to a file alongside, synced to disk, and only then renamed to the real name. */
        private fun writeFile(f: File, data: ByteArray) {
            val tmp = File(f.path + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(data)
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) throw IOException("could not save " + f.name)
        }
    }
}
