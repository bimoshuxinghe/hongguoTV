// SPDX-License-Identifier: GPL-3.0-only
// TT-Encrypt V5：设备注册 body 的加密形态（与官方 APK / TikTok 固件公开实现三方互证）。
// 结构：magic(6, "tc"+v5) ‖ salt(32 随机) ‖ iv(16 随机) ‖ AES-128-CBC(payload + PKCS7)
// key = 魔改哈希( 魔改哈希(salt) ‖ ORD_LIST )[0..16]
// payload = 魔改哈希(gzip)[16..64]（48B 完整性哈希，取错窗口服务端静默拒 device_id=0）‖ gzip
// gzip 头的 XFL/OS 必须为 00/00（zlib 家族头校验）；明文/gzip form 会被静默降级，必须加密。
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object TtEncryptV5 {
    private val MAGIC = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00)
    private val random = SecureRandom()

    /** 随机盐/IV 加密（设备注册用） */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val salt = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(16).also(random::nextBytes)
        return encryptWithSalt(plaintext, salt, iv)
    }

    /** 指定盐/IV 加密（对拍/单测用） */
    fun encryptWithSalt(plaintext: ByteArray, salt: ByteArray, iv: ByteArray): ByteArray {
        val gz = gzip(plaintext)
        // Java 的 GZIPOutputStream 头本就写 XFL/OS=0，这里显式覆写以锁定契约
        gz[8] = 0; gz[9] = 0
        val seed = TtHash.calculate(salt) + TtHash.TT_ORD_LIST
        val key = TtHash.calculate(seed).copyOfRange(0, 16)
        val payload = TtHash.calculate(gz).copyOfRange(16, 64) + gz
        val pad = 16 - payload.size % 16
        val padded = payload + ByteArray(pad) { pad.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return MAGIC + salt + iv + cipher.doFinal(padded)
    }

    /** 解密（对拍/取证用）：返回 (明文, gzip 字节) 并校验完整性哈希 */
    fun decrypt(body: ByteArray): Pair<ByteArray, ByteArray> {
        require(body.size >= 6 + 32 + 16 + 16 && body.copyOfRange(0, 6).contentEquals(MAGIC)) { "不是 TT-Encrypt V5 包" }
        val salt = body.copyOfRange(6, 38)
        val iv = body.copyOfRange(38, 54)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(salt), "AES"), IvParameterSpec(iv))
        val payload = cipher.doFinal(body.copyOfRange(54, body.size))
        val pad = payload.last().toInt()
        require(pad in 1..16 && payload.size >= pad && payload.copyOfRange(payload.size - pad, payload.size).all { it.toInt() == pad }) { "PKCS7 填充非法" }
        val trimmed = payload.copyOfRange(0, payload.size - pad)
        require(trimmed.size >= 48) { "payload 过短" }
        val appHash = trimmed.copyOfRange(0, 48)
        val gz = trimmed.copyOfRange(48, trimmed.size)
        require(TtHash.calculate(gz).copyOfRange(16, 64).contentEquals(appHash)) { "完整性哈希不符" }
        return gunzip(gz) to gz
    }

    internal fun deriveKey(salt: ByteArray): ByteArray = TtHash.calculate(TtHash.calculate(salt) + TtHash.TT_ORD_LIST).copyOfRange(0, 16)

    /** gzip（mtime=0；XFL/OS 由调用方覆写） */
    internal fun gzip(raw: ByteArray): ByteArray = ByteArrayOutputStream(raw.size / 2 + 64).also { out ->
        GZIPOutputStream(out).use { it.write(raw) }
    }.toByteArray()

    private fun gunzip(gz: ByteArray): ByteArray {
        val input = java.util.zip.GZIPInputStream(gz.inputStream())
        return input.readBytes().also { input.close() }
    }
}
