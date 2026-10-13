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
            val dossier = person.dossier ?: return legacy(person)
            val basics = listOf("身份" to dossier.identity, "性别" to dossier.gender, "年龄" to dossier.age)
                .filter { it.second.isNotBlank() }.joinToString("；") { "${it.first}：${it.second}" }
            val description = listOfNotNull(
                dossier.tagline.takeIf(String::isNotBlank),
                basics.takeIf(String::isNotBlank),
                person.aliases.takeIf { it.isNotEmpty() }?.let { "别名：" + it.joinToString("、") },
                dossier.appearance.takeIf(String::isNotBlank)?.let { "外貌：$it" },
                dossier.bio.takeIf(String::isNotBlank),
                dossier.relationships.takeIf { it.isNotEmpty() }?.let { relations ->
                    "人物关系：" + relations.joinToString("；") { "${it.target}（${it.relation}）" + it.detail.takeIf(String::isNotBlank)?.let { d -> "：$d" }.orEmpty() }
                },
                dossier.experiences.takeIf { it.isNotEmpty() }?.let { events -> "经历：\n" + events.joinToString("\n") { "- ${it.text}" } },
                dossier.arc.takeIf(String::isNotBlank)?.let { "成长：$it" },
                person.manualDescription?.takeIf(String::isNotBlank)
            ).joinToString("\n\n")
            val personality = dossier.personality.joinToString("、") { trait ->
                trait.trait + trait.detail.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()
            }
            return ExtractedCharacterCard(person.name, description, personality)
        }

        private fun legacy(person: BookCharacter): ExtractedCharacterCard = ExtractedCharacterCard(person.name,
            (person.attributes.map { "${it.kind.label()}：${it.value}" } + person.relationships.map { "${person.name} → ${it.target}：${it.relation}" }
                + listOf(person.manualDescription ?: person.evidence.joinToString("\n\n") { evidence ->
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
