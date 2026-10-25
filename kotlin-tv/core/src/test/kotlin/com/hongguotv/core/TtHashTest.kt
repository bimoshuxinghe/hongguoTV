// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class TtHashTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun input(n: Int) = ByteArray(n) { i -> ((i * 7 + 3) % 256).toByte() }

    /** Python 参考实现 calculate(range(32)) 的对拍锚点 */
    @Test
    fun matchesPythonReference() {
        val out = TtHash.calculate(ByteArray(32) { it.toByte() })
        assertEquals(
            "3d94eea49c580aef816935762be049559d6d1440dede12e6a125f1841fff8e6fa9d71862a3e5746b571be3d187b0041046f52ebd850c7cbd5fde8ee38473b649",
            hex(out)
        )
    }

    /** 各长度向量的 Python 参考对拍（单块/多块/魔改分叉边界） */
    @Test
    fun lengthVectors() {
        val cases = mapOf(
            0 to "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            1 to "e45bf5817ddf94aa2f7a407071f0eedc6beb98f768b4cd33d1176d44d1563a45a5d7212290eb7670c6786b13591aedac86478993895e8b24e612014abaa6ba04",
            19 to "ae31ede5ae0b51a4748d925137e39bebcb0d83df818ea9b308eeb1d9fcb913598aa6f54b55ee9239651010e3fb1db506d04f9c556ba535ffed41c41553dd392a",
            32 to "c3902ef600c188f0a9b0d32d5e78edf886d61887e698a81aab084c8f86dbfe6f5c4ba5a226e2b0313a837747c1a09a56b1ec9f52479b6f9959ac1b0d0c3d3465",
            39 to "08673ac2515372c2be09b1b82bdf334a875e536ad3d5749c7ed03cf8d819e1df89787441d69ea7a579d65345c5b0fd3466ec8efa5f32a3215d8054d917c30958",
            100 to "364662a2cfeefa210252f45394a87e9ab0d268fe0c448b7e60d69888c8506824fa142beff9a337045417b0bcc5d2e1cecd65223d4d1078cd12d52a2f57b92ed9",
            127 to "548c0b30880e6b0f3bfd75510c12e3ac1b1a0579c37868b18d793358fecaac2400c67021b109ccaa87e711cab67bb51762a20ac0f01c10c0b9680834033ff9ea",
            128 to "99b16f17aa0b969a5b8f08f367719d516e330ccd2660b6f0688ec031dbc783de50a1cd185a2568dba75070a2403d17d4741d163578515dfd2ff756ddfe4d47b1",
            200 to "cca3c0276046ef9f2897bdfc3ec330f77f4959914b1462bd581b232ddb3e9aa98acf5f5a2b21c7f49d2e43721daa61a2b5cee6af6052dfeb766e66ddb0d1719c"
        )
        for ((n, expect) in cases) assertEquals("长度 $n", expect, hex(TtHash.calculate(input(n))))
    }

    /** len % 128 <= 110 的分支输出必须与标准 SHA-512 逐字节相同 */
    @Test
    fun standardBranchEqualsSha512() {
        for (n in intArrayOf(0, 1, 19, 32, 39, 100, 128, 200, 238, 365)) {
            val expected = MessageDigest.getInstance("SHA-512").digest(input(n))
            assertEquals("长度 $n", hex(expected), hex(TtHash.calculate(input(n))))
        }
    }

    /** len % 128 >= 111 的魔改分支必须与标准 SHA-512 分叉（以 len=127 锁定） */
    @Test
    fun magicBranchDivergesFromSha512() {
        val standard = MessageDigest.getInstance("SHA-512").digest(input(127))
        val modified = TtHash.calculate(input(127))
        assertEquals(
            "548c0b30880e6b0f3bfd75510c12e3ac1b1a0579c37868b18d793358fecaac2400c67021b109ccaa87e711cab67bb51762a20ac0f01c10c0b9680834033ff9ea",
            hex(modified)
        )
        check(!standard.contentEquals(modified)) { "魔改分支不应等于标准 SHA-512" }
    }

    /** ORD_LIST 长度与 TtEncryptV5 密钥派生依赖的字节数一致 */
    @Test
    fun ordListShape() {
        assertEquals(64, TtHash.TT_ORD_LIST.size)
    }
}
