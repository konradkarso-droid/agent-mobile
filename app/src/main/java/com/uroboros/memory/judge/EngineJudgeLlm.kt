package com.uroboros.memory.judge

import com.dark.gguf_lib.models.GenerationEvent
import com.uroboros.llm.LlmEngine

/**
 * Прогон генерации оборвался ошибкой движка.
 *
 * Отдельное исключение, а не пустой ответ: пустой ответ судья прочитал бы как
 * «модель промолчала», то есть как свойство пары, и записал бы это в хранилище.
 * Поломка движка и молчание модели — разные вещи, и снаружи их потом не
 * различить.
 */
class JudgeGenerationException(message: String) : RuntimeException(message)

/**
 * Переходник между [MemoryJudge] и движком.
 *
 * Единственное, что он делает: задаёт модели один вопрос со своим системным
 * сообщением и возвращает сказанное. Ни промпта, ни вердиктов здесь нет — они
 * в [MemoryJudge].
 *
 * Почему лента из двух сообщений, а не обычный одиночный запрос. Одиночный
 * путь подставляет системной стеной принципы агента, и судья читал бы их
 * вместо своей инструкции. Движок ставит стену только если в ленте нет роли
 * `system` — здесь она есть, и стена не подставляется.
 *
 * ЧЕГО ЭТОТ КЛАСС НЕ ДЕЛАЕТ. Он не обеспечивает повторяемость выдачи. Вердикт
 * судьи строится на том, что модель дважды сказала одно и то же про одну пару;
 * на разговорных настройках ответ разыгрывается, и такое совпадение означает
 * удачу, а не согласие модели с собой. Повторяемость ставит вызывающий, обернув
 * весь прогон в [LlmEngine.withDeterministicSampling]. Снятый без этого вердикт
 * не значит ничего, и по самому вердикту это не видно.
 */
class EngineJudgeLlm(
    private val engine: LlmEngine,
    /** Потолок выдачи на вопрос; судья берёт [ANSWER_TOKENS], тема строки о себе — свой (SelfLineStep). */
    private val answerTokens: Int = ANSWER_TOKENS,
) : MemoryJudge.Llm {

    override suspend fun answer(system: String, request: String): String {
        val said = StringBuilder()
        var failure: String? = null
        engine.generateConversationFlow(
            messages = listOf(SYSTEM_ROLE to system, USER_ROLE to request),
            maxTokens = answerTokens,
        ).collect { event ->
            when (event) {
                is GenerationEvent.Token -> said.append(event.text)
                is GenerationEvent.Error -> failure = event.message
                else -> Unit
            }
        }
        failure?.let { throw JudgeGenerationException(it) }
        return said.toString()
    }

    companion object {
        private const val SYSTEM_ROLE = "system"
        private const val USER_ROLE = "user"

        /**
         * Потолок выдачи на один вопрос.
         *
         * Нужна одна цифра, но запрошено два токена: модель может начать ответ
         * с пробела, и тогда при потолке в один токен цифра не успела бы
         * прозвучать — все пары до единой стали бы непрочитанными.
         *
         * Выше не поднимать без причины. Каждый лишний токен умножается на
         * число пар и на два порядка: на памяти в полсотни записей это тысячи
         * лишних вызовов выдачи. Если ответы всё же приходят непрочитанными,
         * сначала посмотреть, что именно сказала модель, а не поднимать потолок
         * вслепую: скорее всего она отвечает словами, и лечится это промптом.
         */
        const val ANSWER_TOKENS = 2
    }
}
