package com.uroboros.initiative

/**
 * Повод заговорить первым. Условия, доставка, ход в ленте, уведомление и
 * прибор — общие (см. [InitiativeDecision] и AgentService); источник решает
 * только, есть ли что сказать и что именно.
 *
 * Источник пока один — любопытство ([CuriositySource]). Новый источник — ещё
 * одна реализация; общие условия он ослабить не может: они проверяются до и
 * после него, а не им.
 */
interface InitiativeSource {

    /** Что сказать сейчас. Зовётся раз в минуту бодрствования — должно быть дёшево. */
    suspend fun offer(): Offer

    sealed class Offer {
        /** Нечего сказать; [reason] — почему, словами для прибора. */
        data class Silent(val reason: String) : Offer()

        /**
         * Есть что сказать.
         *
         * @param line служебная строка модели вместо реплики владельца, во
         *   втором лице. В ленту ложится как реплика, на экран не выводится.
         * @param what о чём, для прибора: «сон «…»».
         * @param onSent строка ушла в движок в момент `at` (зовётся и на нуле
         *   токенов: строка всё равно сказана).
         * @param onAppended ход лёг в ленту — сообщение агента существует.
         */
        class Say(
            val line: String,
            val what: String,
            val onSent: suspend (at: Long) -> Unit,
            val onAppended: suspend () -> Unit,
        ) : Offer()
    }
}
