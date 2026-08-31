package com.xiaomi.tts.app

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class NovelSegment(val speaker: String, val isDialogue: Boolean, val text: String)

/**
 * 小说文本解析：启发式识别对白与角色（"XXX说道：'……'"模式）
 */
object NovelParser {

    private val DIALOGUE = Regex("""[“"]([^”"]{2,300})[”"]""")
    private val SPEAKER = Regex(
        """([\u4e00-\u9fa5A-Za-z0-9]{1,6})(?:说道|问道|答道|喊道|叫道|笑道|骂道|哭道|叹道|低声道?|沉声道?|大声说?|轻声说?|说|道)"""
    )

    /** 编码自适应：严格 UTF-8 失败则回退 GBK（中文 txt 常见） */
    fun decodeText(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        String(bytes, Charset.forName("GBK"))
    }

    /** 解析为分段：对白 / 旁白交替 */
    fun parse(text: String): List<NovelSegment> {
        val segs = mutableListOf<NovelSegment>()
        var idx = 0
        for (m in DIALOGUE.findAll(text)) {
            val before = text.substring(idx, m.range.first).trim()
            if (before.isNotEmpty()) segs += NovelSegment("旁白", false, before)
            // 从引号前文末尾找 "XXX说" 模式提取角色名
            val speaker = SPEAKER.find(before.takeLast(24))?.groupValues?.get(1)
                ?: if (before.isEmpty()) "未标注" else "未标注"
            segs += NovelSegment(speaker, true, m.groupValues[1])
            idx = m.range.last + 1
        }
        val tail = text.substring(idx).trim()
        if (tail.isNotEmpty()) segs += NovelSegment("旁白", false, tail)
        return splitLong(segs)
    }

    /** 过长段落按句号切分（单次合成保护，≤约500字） */
    private fun splitLong(segs: List<NovelSegment>): List<NovelSegment> {
        val out = mutableListOf<NovelSegment>()
        for (s in segs) {
            if (s.text.length <= 500) {
                out += s
                continue
            }
            val sb = StringBuilder()
            var count = 0
            for (ch in s.text) {
                sb.append(ch)
                count++
                if (count >= 400 && ch in "。！？！?…") {
                    out += s.copy(text = sb.toString())
                    sb.setLength(0)
                    count = 0
                }
            }
            if (sb.isNotEmpty()) out += s.copy(text = sb.toString())
        }
        return out
    }

    /** 角色列表（按台词段数降序） */
    fun speakers(segs: List<NovelSegment>): List<Pair<String, Int>> =
        segs.groupingBy { it.speaker }.eachCount().toList().sortedByDescending { it.second }

    /** 默认音色分配：旁白→白桦（沉稳男声），其他角色循环分配 */
    fun defaultVoices(sp: List<Pair<String, Int>>): Map<String, String> {
        val pool = listOf("冰糖", "苏打", "茉莉", "Mia", "Chloe")
        var i = 0
        return sp.associate { (name, _) ->
            if (name == "旁白") name to "白桦"
            else name to pool[(i++) % pool.size]
        }
    }
}
