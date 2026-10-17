package com.mozhi.reader.ai.knowledge

import kotlinx.serialization.json.*

/** A portable card drafted from verified facts; missing traits are left for the reader to edit. */
data class ExtractedCharacterCard(val name: String, val description: String, val personality: String = "") {
    fun toJson(): String = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        putJsonObject("data") {
            put("name", name)
            put("description", description)
            put("personality", personality)
            put("scenario", "")
            put("first_mes", "")
            put("mes_example", "")
            put("creator", "MoRead")
            put("character_version", "1.0")
            put("creator_notes", "来自书中人物资料，可能包含用户手动补充；原文依据仅来自所选提取范围。")
            put("system_prompt", "")
            put("post_history_instructions", "")
            putJsonArray("alternate_greetings") {}
            putJsonArray("tags") { add("书中人物") }
            putJsonObject("extensions") {}
        }
    })

    companion object {
        fun from(person: BookCharacter): ExtractedCharacterCard {
            // 档案与手写属性都没有（完整扫描的旧资料）时走旧结构；其余一律按取值规则导出。
            val dossier = person.dossier
            if (dossier == null && person.manualAttributes == null) return legacy(person)
            fun value(kind: CharacterAttributeKind): String = person.attributeValues(kind).firstOrNull().orEmpty()
            val basics = listOf("身份" to value(CharacterAttributeKind.IDENTITY), "性别" to value(CharacterAttributeKind.GENDER),
                "年龄" to value(CharacterAttributeKind.AGE))
                .filter { it.second.isNotBlank() }.joinToString("；") { "${it.first}：${it.second}" }
            val description = listOfNotNull(
                person.tagline().takeIf(String::isNotBlank),
                basics.takeIf(String::isNotBlank),
                person.aliases.takeIf { it.isNotEmpty() }?.let { "别名：" + it.joinToString("、") },
                person.attributeValues(CharacterAttributeKind.APPEARANCE).joinToString("；").takeIf(String::isNotBlank)?.let { "外貌：$it" },
                person.bio().takeIf(String::isNotBlank),
                (person.manualRelations() ?: dossier?.relationships.orEmpty()).takeIf { it.isNotEmpty() }?.let { relations ->
                    "人物关系：" + relations.joinToString("；") { "${it.target}（${it.relation}）" + it.detail.takeIf(String::isNotBlank)?.let { d -> "：$d" }.orEmpty() }
                },
                person.arc().takeIf(String::isNotBlank)?.let { "成长：$it" }
            ).joinToString("\n\n")
            val personality = (person.manualTraits() ?: dossier?.personality.orEmpty()).joinToString("、") { trait ->
                trait.trait + trait.detail.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()
            }
            return ExtractedCharacterCard(person.name, description, personality)
        }

        private fun legacy(person: BookCharacter): ExtractedCharacterCard = ExtractedCharacterCard(person.name,
            (person.attributes.map { "${it.kind.label()}：${it.value}" } + person.relationships.map { "${person.name} → ${it.target}：${it.relation}" }
                + listOf(person.evidence.joinToString("\n\n") { evidence ->
                "${evidence.fact.text}\n（第 ${evidence.chapterIndex + 1} 章依据：${evidence.fact.quote}）"
            })).filter(String::isNotBlank).joinToString("\n\n"),
            person.attributes.filter { it.kind == CharacterAttributeKind.PERSONALITY }.map { it.value }.distinct().joinToString("、"))
    }
}

fun CharacterAttributeKind.label(): String = when (this) {
    CharacterAttributeKind.ALIAS -> "别名"
    CharacterAttributeKind.AGE -> "年龄"
    CharacterAttributeKind.GENDER -> "性别"
    CharacterAttributeKind.IDENTITY -> "身份"
    CharacterAttributeKind.APPEARANCE -> "外貌"
    CharacterAttributeKind.PERSONALITY -> "性格"
}
