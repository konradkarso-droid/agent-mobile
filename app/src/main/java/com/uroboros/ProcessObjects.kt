package com.uroboros

import android.content.Context
import com.uroboros.llm.LlmEngine
import com.uroboros.memory.TrustedMediator
import com.uroboros.safety.DeviceSafetyWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Объекты, которые живут столько же, сколько процесс, а не столько, сколько экран.
 *
 * ЗАЧЕМ. Экран (активность) Android пересоздаёт по своим причинам — поворот,
 * смена темы, нехватка памяти, — а процесс при этом остаётся. Пока эти объекты
 * создавал экран, каждое пересоздание заводило новый движок модели и новый
 * сторож, а старые оставались брошенными. Хуже того, сторож жил на области
 * экрана: работа, пережившая экран, осталась бы без остановки по нагреву —
 * зона застыла бы на последнем значении. Долгий прогон не должен переживать
 * свою проверку безопасности, поэтому сторож живёт на области процесса и
 * умирает только вместе с ним.
 *
 * ЧЕМ ЭТО НЕ ЯВЛЯЕТСЯ. Это не AgentRuntime из ARCHITECTURE.md §4: там владелец
 * живёт в службе, экран к нему подключается и читает единое состояние. Здесь
 * только одно владение на процесс; экран по-прежнему зовёт объекты напрямую.
 *
 * ЧЕГО НЕ УМЕЕТ.
 *  - Не переживает смерть процесса: выгрузил Android приложение — объекты
 *    создадутся заново, модель придётся грузить снова. Держать процесс живым —
 *    работа службы переднего плана, не этого файла.
 *  - Работа, запущенная в области экрана (`lifecycleScope`), по-прежнему
 *    обрывается вместе с экраном, даже если объекты, которыми она пользуется,
 *    живы. Сюда переехало владение, а не долгие прогоны.
 *
 * Здесь только то, что обязано быть одним на процесс: память, сторож, движок.
 * Остальное (задача цикла, компилятор, очередь вопросов) держит в себе ссылки
 * на экран и пока создаётся экраном.
 */
object ProcessObjects {

    class Held internal constructor(
        val mediator: TrustedMediator,
        val watchdog: DeviceSafetyWatchdog,
        val llmEngine: LlmEngine,
    ) {
        /**
         * Имя модели, загруженной в [llmEngine], для строки на экране.
         *
         * Нужен потому, что экран, созданный заново при уже загруженной модели,
         * грузить её второй раз не должен, а имени файла движок не хранит.
         * `null` — ни одна загрузка в этом процессе не удалась.
         */
        @Volatile
        var loadedModelName: String? = null
    }

    /**
     * Область жизни процесса. Главный поток — тот же, на котором сторож работал
     * в области экрана, так что поведение его потоков не меняется.
     * SupervisorJob: сбой одного потока сторожа не гасит остальные.
     */
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var held: Held? = null

    /**
     * Единственный экземпляр на процесс; создаётся при первом обращении.
     * Контекст берётся только прикладной: ссылка на экран здесь пережила бы
     * экран и удержала бы его в памяти.
     */
    fun get(context: Context): Held =
        held ?: synchronized(this) {
            held ?: create(context.applicationContext).also { held = it }
        }

    private fun create(app: Context): Held {
        val watchdog = DeviceSafetyWatchdog(app, processScope)
        return Held(
            mediator = TrustedMediator(app),
            watchdog = watchdog,
            llmEngine = LlmEngine(app, watchdog),
        )
    }
}
