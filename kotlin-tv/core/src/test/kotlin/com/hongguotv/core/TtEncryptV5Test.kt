// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtEncryptV5Test {
    /** 加解密往返 + 结构锁（与参考实现的解密链对称） */
    @Test
    fun roundtrip() {
        val plain = """{"magic_tag":"ss_app_log","header":{"aid":8662},"_gen_time":1791016810000}""".toByteArray()
        val body = TtEncryptV5.encrypt(plain)
        assertEquals(0x74, body[0].toInt()); assertEquals(0x63, body[1].toInt())
        assertEquals(0x05, body[2].toInt()); assertEquals(0x10, body[3].toInt())
        val (decrypted, gz) = TtEncryptV5.decrypt(body)
        assertArrayEquals(plain, decrypted)
        assertEquals(0x1f, gz[0].toInt() and 255); assertEquals(0x8b, gz[1].toInt() and 255)
        // gzip 头 XFL/OS 必须覆写为 00/00（zlib 家族头校验）
        assertEquals(0, gz[8].toInt()); assertEquals(0, gz[9].toInt())
        // 密文总长：6 magic + 32 salt + 16 iv + 对齐后的 payload
        assertEquals(54 + ((48 + gz.size + 15) / 16) * 16, body.size)
    }

    /** 同盐同 IV 重加密必须逐字节确定（AES-CBC + 固定 gzip 字节流） */
    @Test
    fun deterministicWithFixedSalt() {
        val plain = ByteArray(300) { (it % 251).toByte() }
        val salt = ByteArray(32) { (it * 3).toByte() }
        val iv = ByteArray(16) { (it * 11).toByte() }
        val a = TtEncryptV5.encryptWithSalt(plain, salt, iv)
        val b = TtEncryptV5.encryptWithSalt(plain, salt, iv)
        assertArrayEquals(a, b)
        assertEquals(salt.toList(), a.toList().subList(6, 38))
        assertEquals(iv.toList(), a.toList().subList(38, 54))
    }

    /** 密钥派生链：key = TtHash(TtHash(salt) ‖ ORD_LIST)[0..16]，64 位输出截前 16 */
    @Test
    fun derivedKeyShape() {
        val salt = ByteArray(32)
        val key = TtEncryptV5.deriveKey(salt)
        assertEquals(16, key.size)
        val expected = TtHash.calculate(TtHash.calculate(salt) + TtHash.TT_ORD_LIST)
        assertArrayEquals(expected.copyOfRange(0, 16), key)
        assertTrue(key.any { it != 0.toByte() })
    }
}
