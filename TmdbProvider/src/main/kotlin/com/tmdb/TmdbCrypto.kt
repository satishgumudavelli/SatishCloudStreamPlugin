package com.tmdb

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

fun hmacSha256Hex(key: String, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

fun pbkdf2Sha256(passwordAscii: String, saltHex: String, iterations: Int, keyLenBytes: Int): ByteArray {
    val spec = PBEKeySpec(passwordAscii.toCharArray(), hexToBytes(saltHex), iterations, keyLenBytes * 8)
    return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
}

fun aesGcmDecrypt(key: ByteArray, ivHex: String, ciphertextHex: String, tagHex: String): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, hexToBytes(ivHex)))
    val pt = cipher.doFinal(hexToBytes(ciphertextHex) + hexToBytes(tagHex))
    return String(pt, Charsets.UTF_8)
}
