@file:OptIn(ExperimentalUnsignedTypes::class)

package com.vidbox

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory
import java.security.SecureRandom

fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

fun base64UrlDecode(s: String): ByteArray {
    val fixed = s.replace("-", "+").replace("_", "/")
    val padded = fixed + "=".repeat((4 - fixed.length % 4) % 4)
    return Base64.decode(padded, Base64.DEFAULT)
}

/** AES-256-GCM where [token] is base64url(nonce[12] + ciphertext + tag[16]), as used by vidrock.net. */
fun aesGcmDecryptToken(keyHex: String, token: String): String {
    val raw = base64UrlDecode(token)
    val nonce = raw.copyOfRange(0, 12)
    val ctAndTag = raw.copyOfRange(12, raw.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(keyHex), "AES"), GCMParameterSpec(128, nonce))
    return String(cipher.doFinal(ctAndTag), Charsets.UTF_8)
}

fun aesGcmDecrypt(key: ByteArray, ivHex: String, ciphertextHex: String, tagHex: String): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, hexToBytes(ivHex)))
    val pt = cipher.doFinal(hexToBytes(ciphertextHex) + hexToBytes(tagHex))
    return String(pt, Charsets.UTF_8)
}

fun hmacSha256Hex(key: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun pbkdf2Sha256(passwordAscii: String, saltHex: String, iterations: Int, keyLenBytes: Int): ByteArray {
    val spec = PBEKeySpec(passwordAscii.toCharArray(), hexToBytes(saltHex), iterations, keyLenBytes * 8)
    return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
}

/** CryptoJS.AES.encrypt/decrypt(text, passphrase) - OpenSSL "Salted__" format, EVP_BytesToKey(MD5) derivation. */
object CryptoJsAes {
    private fun evpBytesToKey(passphrase: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        var prev = ByteArray(0)
        val out = ArrayList<Byte>()
        while (out.size < keyLen + ivLen) {
            md5.reset()
            md5.update(prev)
            md5.update(passphrase)
            md5.update(salt)
            prev = md5.digest()
            out.addAll(prev.toList())
        }
        val all = out.toByteArray()
        return all.copyOfRange(0, keyLen) to all.copyOfRange(keyLen, keyLen + ivLen)
    }

    fun decrypt(base64: String, passphrase: String): String {
        val data = Base64.decode(base64, Base64.DEFAULT)
        require(data.size > 16 && String(data, 0, 8, Charsets.US_ASCII) == "Salted__") { "not CryptoJS salted format" }
        val salt = data.copyOfRange(8, 16)
        val ct = data.copyOfRange(16, data.size)
        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt, 32, 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    fun decryptUrlSafe(urlSafeBase64: String, passphrase: String): String {
        val fixed = urlSafeBase64.replace("-", "+").replace("_", "/")
        return decrypt(fixed + "=".repeat((4 - fixed.length % 4) % 4), passphrase)
    }

    fun encryptUrlSafe(plaintext: String, passphrase: String): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt, 32, 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = "Salted__".toByteArray(Charsets.US_ASCII) + salt + ct
        return Base64.encodeToString(out, Base64.NO_WRAP).replace("+", "-").replace("/", "_").replace("=", "")
    }
}

/**
 * The XOR keystream cipher shared by player.videasy.to ("4K") and vidking.net ("Rock").
 * Transcribed from their webpack chunks - a seeded PRNG (fnv1a + murmur3-style avalanche,
 * golden-ratio mixing) generating a keystream XORed against the base64url ciphertext body,
 * validated against a 4-byte "mvm1" magic header.
 */
object MvmCipher {
    private const val MS = 2654435769u
    private val MAGIC = byteArrayOf(109, 118, 109, 49)

    private fun avalanche(eIn: UInt): UInt {
        var e = eIn
        e = e xor (e shr 16)
        e *= 2246822507u
        e = e xor (e shr 13)
        e *= 3266489909u
        e = e xor (e shr 16)
        return e
    }

    private fun rotl(l: UInt, o: Int): UInt {
        val shift = o and 31
        return if (shift == 0) l else (l shl shift) or (l shr (32 - shift))
    }

    private fun fnv1a(s: String): UInt {
        var o = 2166136261u
        for (c in s) o = (o xor c.code.toUInt()) * 16777619u
        return avalanche(o)
    }

    private class State(seed: String, mediaId: Int) {
        val table = UIntArray(61)
        val visited = BooleanArray(61)
        var acc: UInt

        init {
            var i = avalanche(fnv1a(seed) xor avalanche(mediaId.toUInt() xor MS))
            for (r in 0 until 8) {
                val n = (i % 61u).toInt()
                i = rotl(i + MS, 7 + (r and 7))
                table[n] = i xor avalanche(i)
                visited[n] = true
                i = avalanche(i + n.toUInt())
            }
            acc = avalanche(i xor 2779096485u)
        }

        fun nextWord(counter: Int): UInt {
            val r = (acc % 61u).toInt()
            val hasSlot = visited[r]
            val u = if (hasSlot) table[r] else 0u
            val mask = if (hasSlot) 0xFFFFFFFFu else 0u
            val d = MS * (counter + 1).toUInt()
            val xorUD = u xor d
            var g = (acc xor xorUD) or (acc and xorUD and mask)
            g = rotl(g + acc, r and 31) xor rotl(acc, (r * 7) and 31)
            val newAcc = avalanche(g + MS)
            table[r] = newAcc
            visited[r] = true
            acc = newAcc
            return newAcc
        }
    }

    fun decode(base64UrlToken: String, seed: String, mediaId: Int): String {
        val cipherBytes = base64UrlDecode(base64UrlToken)
        val state = State(seed, mediaId)
        val out = ByteArray(cipherBytes.size)
        var idx = 0
        var counter = 0
        while (idx < cipherBytes.size) {
            val word = state.nextWord(counter++)
            out[idx] = (cipherBytes[idx].toInt() xor (word and 0xFFu).toInt()).toByte(); idx++
            if (idx < cipherBytes.size) { out[idx] = (cipherBytes[idx].toInt() xor ((word shr 8) and 0xFFu).toInt()).toByte(); idx++ }
            if (idx < cipherBytes.size) { out[idx] = (cipherBytes[idx].toInt() xor ((word shr 16) and 0xFFu).toInt()).toByte(); idx++ }
            if (idx < cipherBytes.size) { out[idx] = (cipherBytes[idx].toInt() xor ((word shr 24) and 0xFFu).toInt()).toByte(); idx++ }
        }
        for (i in MAGIC.indices) {
            if (out[i] != MAGIC[i]) throw IllegalStateException("mvm1 decrypt failed: bad seed or tampered payload")
        }
        return String(out, MAGIC.size, out.size - MAGIC.size, Charsets.UTF_8)
    }
}
