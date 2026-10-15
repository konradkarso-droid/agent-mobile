package com.uroboros.memory.nav

import com.uroboros.llm.WordDamage
import java.io.File

/**
 * Словари определителя лица для тестов — те же файлы, что грузит приложение:
 * таблица глаголов (`app/src/main/assets/verb_person_stems.txt`,
 * [PersonForm.verbs]), трафарет слов (`app/src/main/assets/word_stencil6.bin`,
 * [PersonForm.words]) и словарь начальных форм
 * (`app/src/main/assets/lemma_forms.txt`, [PersonForm.lemmas]). Без них «я» глаголом без местоимения («точу»,
 * «работаю») — «не ясно», и тесты мерили бы не то правило, что на телефоне.
 *
 * Словари одни на процесс, как у приложения, и тест, проверяющий поведение
 * без них, обязан вернуть их на место (см. PersonFormTest).
 *
 * Файлы ищутся от каталога модуля (так запускает Gradle) и от корня
 * репозитория.
 */
object TestVerbs {
    private fun asset(name: String): File =
        listOf("src/main/assets/$name", "app/src/main/assets/$name")
            .map { File(it) }
            .firstOrNull { it.exists() }
            ?: error("$name не найден от ${File(".").absolutePath}")

    val table: RetellTable by lazy {
        asset("verb_person_stems.txt").bufferedReader(Charsets.UTF_8).useLines { RetellTable.readStems(it) }
    }

    val stencil: WordDamage by lazy { asset("word_stencil6.bin").inputStream().use { WordDamage.read(it) } }

    val lemmas: LemmaDictionary by lazy {
        asset("lemma_forms.txt").bufferedReader(Charsets.UTF_8).useLines { LemmaDictionary.read(it) }
    }

    fun install() {
        PersonForm.verbs = table
        PersonForm.words = stencil
        PersonForm.lemmas = lemmas
    }
}
