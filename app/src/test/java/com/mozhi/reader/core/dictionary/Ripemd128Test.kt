package com.mozhi.reader.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class Ripemd128Test {
    private fun hex(text: String): String =
        Ripemd128.digest(text.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }

    // 算法作者公布的标准测试向量，覆盖空输入、单块、跨块填充与多块。
    @Test fun matchesPublishedTestVectors() {
        assertEquals("cdf26213a150dc3ecb610f18f6b38b46", hex(""))
        assertEquals("86be7afa339d0fc7cfc785e72f578d33", hex("a"))
        assertEquals("c14a12199c66e4ba84636b0f69144c77", hex("abc"))
        assertEquals("9e327b3d6e523062afc1132d7df9d1b8", hex("message digest"))
        assertEquals("fd2aa607f71dc8f510714922b371834e", hex("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("a1aa0689d0fafa2ddc22e88b49133a06", hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
        assertEquals("d1e959eb179c911faea4624c60c5c702", hex("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"))
        assertEquals("3f45ef194732c2dbb2c4a2c769795fa3", hex("1234567890".repeat(8)))
        assertEquals("4a7f5723f954eba1216c9d8f6320431f", hex("a".repeat(1_000_000)))
    }

    // 55/56 字节恰好跨过「长度字段塞不进当前块」的边界，64 字节整块后要再补一整块。
    @Test fun paddingBoundaries() {
        fun zeros(size: Int) = Ripemd128.digest(ByteArray(size)).joinToString("") { "%02x".format(it) }
        assertEquals("d0898990ad65d38ba3bf9e87500f66b5", zeros(55))
        assertEquals("d6ee6d16a255fe416b8fffcb2cb03aaf", zeros(56))
        assertEquals("082bfa9b829ef3a9e220dcc54e4c6383", zeros(64))
    }
}
