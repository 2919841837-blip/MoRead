package com.mozhi.reader.core.dictionary

/**
 * RIPEMD-128（Dobbertin/Bosselaers/Preneel 1996）。MDict 加密索引只用它从 8 字节种子派生
 * 16 字节密钥，自带实现以免为单个摘要引入整个 BouncyCastle。
 */
internal object Ripemd128 {
    fun digest(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8
        val paddedSize = (input.size + 8) / 64 * 64 + 64
        val message = input.copyOf(paddedSize)
        message[input.size] = 0x80.toByte()
        for (i in 0 until 8) message[paddedSize - 8 + i] = (bitLength ushr (8 * i)).toByte()

        val state = intArrayOf(0x67452301, 0xefcdab89.toInt(), 0x98badcfe.toInt(), 0x10325476)
        val words = IntArray(16)
        for (block in 0 until paddedSize step 64) {
            for (i in 0 until 16) {
                val offset = block + i * 4
                words[i] = (message[offset].toInt() and 255) or
                    ((message[offset + 1].toInt() and 255) shl 8) or
                    ((message[offset + 2].toInt() and 255) shl 16) or
                    ((message[offset + 3].toInt() and 255) shl 24)
            }
            compress(state, words)
        }
        return ByteArray(16) { (state[it / 4] ushr (8 * (it % 4))).toByte() }
    }

    private fun compress(state: IntArray, x: IntArray) {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var ap = a; var bp = b; var cp = c; var dp = d
        for (j in 0 until 64) {
            val round = j / 16
            var t = (a + f(round, b, c, d) + x[LEFT_WORD[j]] + LEFT_K[round]).rotateLeft(LEFT_SHIFT[j])
            a = d; d = c; c = b; b = t
            t = (ap + f(3 - round, bp, cp, dp) + x[RIGHT_WORD[j]] + RIGHT_K[round]).rotateLeft(RIGHT_SHIFT[j])
            ap = dp; dp = cp; cp = bp; bp = t
        }
        val t = state[1] + c + dp
        state[1] = state[2] + d + ap
        state[2] = state[3] + a + bp
        state[3] = state[0] + b + cp
        state[0] = t
    }

    private fun f(round: Int, x: Int, y: Int, z: Int): Int = when (round) {
        0 -> x xor y xor z
        1 -> (x and y) or (x.inv() and z)
        2 -> (x or y.inv()) xor z
        else -> (x and z) or (y and z.inv())
    }

    private val LEFT_K = intArrayOf(0x00000000, 0x5a827999, 0x6ed9eba1, 0x8f1bbcdc.toInt())
    private val RIGHT_K = intArrayOf(0x50a28be6, 0x5c4dd124, 0x6d703ef3, 0x00000000)

    private val LEFT_WORD = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2
    )
    private val RIGHT_WORD = intArrayOf(
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14
    )
    private val LEFT_SHIFT = intArrayOf(
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12
    )
    private val RIGHT_SHIFT = intArrayOf(
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8
    )
}
