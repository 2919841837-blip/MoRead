package com.mozhi.reader.ai.genui.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * libquickjs.so（quickjs-kt 1.0.15）用 JNI 按名字访问几个 Java 类。库自带的 consumer 规则
 * 覆盖了 QuickJs / QuickJsException / binding.JsProperty|JsFunction|JsObject / UByteArray，
 * 但漏了 MemoryUsage（26 个 long 的构造器）与 kotlin.Unit。R8 收走它们后，JVM 单测和 debug 包
 * 都测不出来，只有 minify 的 release/performance 包在原生层崩——与 2026-09-05 ObjectBox
 * 向量检索全线失效是同一类问题。
 *
 * 升级 quickjs-kt 后按 proguard-rules.pro 里记的 grep 办法重新核对原生层要找的类名清单。
 */
class GenUiJniKeepRulesTest {

    private val jniAccessedTypes = listOf(
        "com.dokar.quickjs.MemoryUsage",
        "kotlin.Unit"
    )

    @Test
    fun proguardRulesKeepTypesTheQuickJsNativeLayerLooksUpByName() {
        val rules = proguardRulesFile().readText()
        jniAccessedTypes.forEach { type ->
            val keepRule = Regex("""-keep\s+class\s+${Regex.escape(type)}\b""")
            assertTrue(
                "proguard-rules.pro 缺少 $type 的 keep 规则：minify 包里互动界面引擎会在原生层失败",
                keepRule.containsMatchIn(rules)
            )
        }
    }

    /** 单测工作目录随 Gradle 版本可能是模块目录或仓库根，两处都找。 */
    private fun proguardRulesFile(): File {
        val candidates = listOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro"),
            File("../app/proguard-rules.pro")
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("找不到 proguard-rules.pro（工作目录 ${File("").absolutePath}）")
    }
}
