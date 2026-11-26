package com.uroboros.search

import com.uroboros.memory.ActionProvenance
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Откуда пришла ссылка — определяет и строку компонента для ворот действий
 * ([requestedBy]), и происхождение запроса ([provenance]). Происхождение из
 * группы — не пользовательское: слова группы не поднимают себе доверия, и
 * именно поэтому сетевой вызов по ссылке из группы весит больше и открывается
 * только отдельным разрешением владельца (ChatPrefs.groupLinks).
 */
internal enum class LinkOrigin(val requestedBy: String, val provenance: ActionProvenance) {
    APP("link-follow/app", ActionProvenance.USER),
    OWNER_DIRECT("link-follow/telegram-owner", ActionProvenance.USER),
    GROUP("link-follow/telegram-group", ActionProvenance.GROUP_MESSAGE),
}

/**
 * Приём адреса для прямого открытия присланной ссылки. Отличается от белого
 * списка обхода ([CrawlSources.address]): обход сам выбирает, куда идти, и
 * потому ограничен несколькими сайтами; здесь адрес назвал человек, и граница
 * другая — любой публичный https-адрес, но без схем, паролей и экзотики.
 *
 * ЧЕГО НЕ ПРИНИМАЕТСЯ, и это объявленная граница, а не недоделка:
 *  - не-https (http, tg, mailto, ftp…) — шифрование обязательно;
 *  - явный порт, отличный от 443;
 *  - имя и пароль в адресе;
 *  - пробелы и управляющие знаки внутри, обратная дробная черта в пути,
 *    длина больше [MAX_URL].
 *
 * Адрес без схемы принимается: телеграм размечает ссылками и голые домены
 * («nplus1.ru/news/…»), им достаётся https. Локальные адреса сюда не
 * фильтруются — их останавливает транспорт ([CrawlTransport]) проверкой DNS.
 *
 * Чистый объект: ни Android, ни сети.
 */
internal object LinkAdmission {

    /** Объявленное число, не подобранное: самая длинная принимаемая ссылка. */
    const val MAX_URL = 800

    fun admit(value: String): HttpUrl? {
        val raw = value.trim()
        if (raw.isEmpty() || raw.length > MAX_URL) return null
        if (raw.any { it.isISOControl() || it.isWhitespace() }) return null
        val colon = raw.indexOf(':')
        val slash = raw.indexOf('/')
        val candidate = when {
            colon >= 0 && (slash < 0 || colon < slash) ->
                if (!raw.startsWith("https://", ignoreCase = true)) return null else raw
            else -> "https://$raw"
        }
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.port != 443) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.pathSegments.any { part -> part.any { it == '\\' || it.isISOControl() } }) return null
        if (url.query?.any { it.isISOControl() } == true) return null
        return url.newBuilder().fragment(null).build()
    }
}

/**
 * Прямое открытие одной присланой ссылки: загрузка и разбор, без индекса и
 * без обхода. Перенаправления не идут следом автоматически — каждый шаг
 * перепроверяется приёмом [LinkAdmission], чтобы переадресация не увела
 * адрес за пределы правил.
 *
 * robots.txt не проверяется: это не обход, а открытие одной страницы по
 * прямой просьбе человека; мета-robots самой страницы соблюдается разбором
 * ([extractPage]). Пауза между запросами одного сайта не выдерживается —
 * запрос всегда один на ссылку.
 *
 * Сеть по умолчанию — [CrawlTransport] с бюджетами прямого открытия
 * ([FetchBudgets.DIRECT]): запрос человека не обязан влезать в обходные шесть
 * секунд, но остаётся под пределом одной ссылки ([SearchPlatform.OPEN_LIMIT_MS]).
 *
 * Чистый объект: сеть подставляется функцией, в юнит-тесте заменяется.
 */
internal class LinkOpener(
    private val network: suspend (HttpUrl, CrawlResource, PageValidators) -> CrawlResponse =
        CrawlTransport(budgets = FetchBudgets.DIRECT)::fetch,
) {
    sealed interface Open {
        data class Page(val url: String, val title: String, val text: String) : Open

        /** Не открылось; [why] — причина для строки прибора и ответа. */
        data class Refused(val why: String) : Open
    }

    suspend fun open(url: HttpUrl, stillAllowed: () -> Boolean): Open {
        var current = url
        repeat(REDIRECTS + 1) {
            if (!stillAllowed()) return Open.Refused("разрешение сняли, пока открывал")
            val response = try {
                network(current, CrawlResource.PAGE, PageValidators())
            } catch (stopped: kotlinx.coroutines.CancellationException) {
                throw stopped
            } catch (unreachable: IOException) {
                return Open.Refused("не достучался: ${unreachable.message ?: unreachable.javaClass.simpleName}")
            }
            when (response.code) {
                200 -> {
                    val page = extractPage(current, response.body, response.directives, charsetOf(response.type)).page
                        ?: return Open.Refused("страница без читаемого текста или закрыта от чтения")
                    return Open.Page(page.url, page.title, page.text)
                }
                301, 302, 303, 307, 308 -> {
                    val next = response.location?.let(current::resolve)?.toString()?.let(LinkAdmission::admit)
                        ?: return Open.Refused("переадресация ведёт за пределы правил открытия")
                    current = next
                }
                404, 410 -> return Open.Refused("страницы больше нет (ответ ${response.code})")
                415 -> return Open.Refused("это не HTML-страница")
                else -> return Open.Refused("сервер ответил ${response.code}")
            }
        }
        return Open.Refused("слишком много переадресаций")
    }

    companion object {
        /** Сколько переадресаций пройти максимум. Объявленное число, как у обхода. */
        private const val REDIRECTS = 4

        /** Вопрос читателю страниц, когда сообщение — одна ссылка без слов. Объявленная строка. */
        const val SUMMARY_QUESTION = "О чём эта страница? Перескажи по-русски главное."
    }
}

/**
 * Короткая выдержка страницы, когда ответ модели недоступен: первые непустые
 * строки, не длиннее [limit] знаков. Заголовки («# ») пропускаются — название
 * и так названо строкой выше.
 */
internal fun pageExcerpt(text: String, limit: Int = 400): String =
    text.lineSequence().filter { it.isNotBlank() && !it.startsWith("# ") }
        .take(4).joinToString("\n").take(limit)

/**
 * Строка про недоступный ответ модели с известной причиной. Выдержка выдаётся и
 * без модели: страницу просил открыть человек, и его просьба не зависит от
 * состояния модели. Причина [why] называет, почему ответа нет («модель не
 * загружена», «чтение не уложилось в срок»); null — причина неизвестна,
 * начало строки остаётся прежним.
 */
internal fun modelUnavailableText(why: String?, title: String, text: String, url: String): String =
    "Ответ модели недоступен${why?.let { " — $it" } ?: ""}. Открыл страницу «$title».\n\n" +
        "${pageExcerpt(text)}\n\nИсточник: $url"
