// SPDX-License-Identifier: GPL-3.0-only
// TT-Encrypt V5 的魔改哈希（SHA-512 骨架变体）。
// 算法考证对齐 hongguo-desktop 的 tt_hash.rs（ARM 反汇编 → 逐值核验的唯一事实源）：
// - K_TABLE / TT_IV 与标准 SHA-512 常量逐值一致（按 (lo, hi) u32 字对存放），
//   压缩函数与消息调度即标准 SHA-512，因此用 Long 算术的标准实现。
// - len % 128 <= 110：填充与标准一致，输出与标准 SHA-512 逐字节相同。
// - len % 128 >= 111：else 填充分支——缓冲区比标准多 2 字节被整除截断，
//   8 字节长度只有高 6 字节落入末块（低 2 字节随截断丢失），与标准分叉；
//   len=127 golden 向量锁定该分叉（bitlen 整个退化为零）。
package com.hongguotv.core

import java.io.ByteArrayOutputStream

internal object TtHash {
    /** TT-Encrypt V5 密钥派生盐（官方 APK libvideodec.so 偏移 0x5220 原样存在） */
    val TT_ORD_LIST: ByteArray = intArrayOf(
        77, 212, 194, 230, 184, 49, 98, 9, 14, 82, 179, 199, 166, 115, 59, 164, 28, 178, 70, 43, 130,
        154, 181, 138, 25, 107, 57, 219, 87, 23, 117, 36, 244, 155, 175, 127, 8, 232, 214, 141, 38,
        167, 46, 55, 193, 169, 90, 47, 31, 5, 165, 24, 146, 174, 242, 148, 151, 50, 182, 42, 56, 170,
        221, 88
    ).map { it.toByte() }.toByteArray()

    /** u32 字面量辅助：无符号值照抄，统一转 Int 存放 */
    private fun u32(vararg values: Long) = IntArray(values.size) { values[it].toInt() }

    /** K 表：160 个 u32 = 80 个 u64，按 (lo, hi) 字对存放，逐值等于标准 SHA-512 的 80 个 K 常量 */
    private val K_TABLE = u32(
        3609767458, 1116352408, 602891725, 1899447441, 3964484399, 3049323471, 2173295548, 3921009573,
        4081628472, 961987163, 3053834265, 1508970993, 2937671579, 2453635748, 3664609560, 2870763221,
        2734883394, 3624381080, 1164996542, 310598401, 1323610764, 607225278, 3590304994, 1426881987,
        4068182383, 1925078388, 991336113, 2162078206, 633803317, 2614888103, 3479774868, 3248222580,
        2666613458, 3835390401, 944711139, 4022224774, 2341262773, 264347078, 2007800933, 604807628,
        1495990901, 770255983, 1856431235, 1249150122, 3175218132, 1555081692, 2198950837, 1996064986,
        3999719339, 2554220882, 766784016, 2821834349, 2566594879, 2952996808, 3203337956, 3210313671,
        1034457026, 3336571891, 2466948901, 3584528711, 3758326383, 113926993, 168717936, 338241895,
        1188179964, 666307205, 1546045734, 773529912, 1522805485, 1294757372, 2643833823, 1396182291,
        2343527390, 1695183700, 1014477480, 1986661051, 1206759142, 2177026350, 344077627, 2456956037,
        1290863460, 2730485921, 3158454273, 2820302411, 3505952657, 3259730800, 106217008, 3345764771,
        3606008344, 3516065817, 1432725776, 3600352804, 1467031594, 4094571909, 851169720, 275423344,
        3100823752, 430227734, 1363258195, 506948616, 3750685593, 659060556, 3785050280, 883997877,
        3318307427, 958139571, 3812723403, 1322822218, 2003034995, 1537002063, 3602036899, 1747873779,
        1575990012, 1955562222, 1125592928, 2024104815, 2716904306, 2227730452, 442776044, 2361852424,
        593698344, 2428436474, 3733110249, 2756734187, 2999351573, 3204031479, 3815920427, 3329325298,
        3928383900, 3391569614, 566280711, 3515267271, 3454069534, 3940187606, 4000239992, 4118630271,
        1914138554, 116418474, 2731055270, 174292421, 3203993006, 289380356, 320620315, 460393269,
        587496836, 685471733, 1086792851, 852142971, 365543100, 1017036298, 2618297676, 1126000580,
        3409855158, 1288033470, 4234509866, 1501505948, 987167468, 1607167915, 1246189591, 1816402316
    )

    /** 初始状态：16 个 u32 = 8 个 u64，按 (lo, hi) 字对存放，逐值等于标准 SHA-512 的 IV */
    private val TT_IV = u32(
        4089235720, 1779033703, 2227873595, 3144134277, 4271175723, 1013904242, 1595750129, 2773480762,
        2917565137, 1359893119, 725511199, 2600822924, 4215389547, 528734635, 327033209, 1541459225
    )

    private val K = LongArray(80) { i ->
        ((K_TABLE[2 * i + 1].toLong() and 0xffffffffL) shl 32) or (K_TABLE[2 * i].toLong() and 0xffffffffL)
    }
    private val IV = LongArray(8) { i ->
        ((TT_IV[2 * i + 1].toLong() and 0xffffffffL) shl 32) or (TT_IV[2 * i].toLong() and 0xffffffffL)
    }

    private fun ror(x: Long, n: Int) = (x ushr n) or (x shl (64 - n))

    /** 标准 SHA-512 压缩函数（与参考实现的 hex_27e/hex_30a 逐值等价） */
    private fun compress(state: LongArray, block: ByteArray, offset: Int) {
        val w = LongArray(80)
        for (i in 0 until 16) {
            var v = 0L
            for (j in 0 until 8) v = (v shl 8) or (block[offset + i * 8 + j].toLong() and 255)
            w[i] = v
        }
        for (i in 16 until 80) {
            val s0 = ror(w[i - 15], 1) xor ror(w[i - 15], 8) xor (w[i - 15] ushr 7)
            val s1 = ror(w[i - 2], 19) xor ror(w[i - 2], 61) xor (w[i - 2] ushr 6)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in 0 until 80) {
            val s1 = ror(e, 14) xor ror(e, 18) xor ror(e, 41)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + ch + K[i] + w[i]
            val s0 = ror(a, 28) xor ror(a, 34) xor ror(a, 39)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d
        state[4] += e; state[5] += f; state[6] += g; state[7] += h
    }

    /** 魔改哈希主函数：输出 64 字节 */
    fun calculate(content: ByteArray): ByteArray {
        val length = content.size
        val divisible = length % 128
        val t = 128 - divisible
        // 参考实现填充分支原样：t > 0x11 走标准形态（t 字节补齐）；否则
        // 多补 2 字节（1 + 113 + t + 16 = 130 + t），整除截断丢弃末尾 2 字节
        val appended = if (t > 17) t else 130 + t
        val total = length + appended
        val buf = content.copyOf(total)
        buf[length] = 0x80.toByte()
        val blocks = total / 128
        // 长度字段写入最后 8 字节（大端）：已处理完整块比特数 + 原始余数比特数；
        // 魔改分支里低 2 字节落在整除截断区，永不进入压缩
        val endingBits = 1024L * (blocks - 1) + (divisible.toLong() shl 3)
        for (j in 0 until 8) buf[total - j - 1] = ((endingBits ushr (8 * j)) and 255).toByte()
        val state = IV.copyOf()
        for (i in 0 until blocks) compress(state, buf, i * 128)
        val out = ByteArrayOutputStream(64)
        for (word in state) for (shift in 56 downTo 0 step 8) out.write(((word ushr shift) and 255).toInt())
        return out.toByteArray()
    }
}
