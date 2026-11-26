package com.uroboros.search

import android.net.Network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal enum class CrawlResource(val accept: String, val limit: Int) {
    PAGE("text/html", 2 * 1024 * 1024),
    ROBOTS("text/plain", 512 * 1024),
    FEED("application/rss+xml, application/atom+xml, application/xml, text/xml", 512 * 1024),
    SITEMAP("application/xml, text/xml, application/gzip", 4 * 1024 * 1024),
    REPOSITORY_JSON("application/vnd.github+json", 2 * 1024 * 1024),
    REPOSITORY_SOURCE("text/plain", 128 * 1024);

    fun accepts(type: String): Boolean = when (this) {
        PAGE -> type.equals(accept, true)
        ROBOTS -> type.isBlank() || type.equals(accept, true)
        FEED -> type.lowercase(java.util.Locale.ROOT) in setOf(
            "application/rss+xml", "application/atom+xml", "application/xml", "text/xml",
        )
        SITEMAP -> type.lowercase(java.util.Locale.ROOT) in
            setOf("application/xml", "text/xml", "application/gzip", "application/x-gzip")
        REPOSITORY_JSON -> type.equals("application/json", true) || type.equals(accept, true)
        REPOSITORY_SOURCE -> type.equals(accept, true)
    }
}

internal data class PageValidators(val etag: String? = null, val modified: String? = null) {
    val present: Boolean get() = etag != null || modified != null

    fun updatedBy(headers: PageValidators) = PageValidators(headers.etag ?: etag, headers.modified ?: modified)

    companion object {
        fun from(etag: String?, modified: String?): PageValidators = PageValidators(
            etag?.takeIf { it.length <= 256 && Regex("(?:W/)?\"[!#-~]*\"").matches(it) },
            modified?.takeIf { it.length <= 80 && it.all { ch -> ch in ' '..'~' } &&
                runCatching { java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME) }.isSuccess },
        )
    }
}

internal data class CrawlResponse(
    val code: Int,
    val type: String,
    val body: ByteArray,
    val location: String?,
    val directives: String,
    val validators: PageValidators = PageValidators(),
)

internal const val CRAWLER_NAME = "UroborosLocalSearch"

/**
 * Кодировка из заголовка Content-Type или null, если её там нет или она не
 * поддерживается платформой. Читают и обход ([CrawlSession]), и прямое
 * открытие присланых ссылок — разбор один, в одном месте.
 */
internal fun charsetOf(type: String): String? =
    type.substringAfter("charset=", "").substringBefore(';').trim('"', ' ')
        .takeIf { it.isNotBlank() && runCatching { java.nio.charset.Charset.isSupported(it) }.getOrDefault(false) }

/**
 * Бюджеты ожидания одного запроса: соединение, чтение, весь вызов.
 *
 * ДВА НАБОРА — объявленные числа, не подобранные:
 *  - [FetchBudgets.CRAWL] — вежливый обход: короткие бюджеты, без повтора;
 *  - [FetchBudgets.DIRECT] — прямое открытие присланой ссылки: весь вызов
 *    укладывается под предел открытия одной ссылки (SearchPlatform.OPEN_LIMIT_MS),
 *    а не режет её раньше. Большая HTML-страница (github.com — около 200 КБ)
 *    на мобильной сети в шесть секунд не влезает, и обходный бюджет срезал её
 *    до половины объявленного предела. Повтор сломанного соединения — один:
 *    переключение WiFi→сота на телефоне как раз такой случай.
 */
internal data class FetchBudgets(
    val connectMs: Long,
    val readMs: Long,
    val callMs: Long,
    /** Повторять ли сломанное соединение: обходу — нет (лишний стук в источник), прямому открытию — да. */
    val retryConnection: Boolean,
) {
    companion object {
        /** Обход: как до появления прямого открытия, вежливость не разжата. */
        val CRAWL = FetchBudgets(4_000L, 4_000L, 6_000L, retryConnection = false)

        /** Прямое открытие: под пределом одной ссылки, с одним повтором соединения. */
        val DIRECT = FetchBudgets(8_000L, 12_000L, 18_000L, retryConnection = true)
    }
}

internal class CrawlTransport(private val client: Call.Factory) {
    /** Закрепляем проверку IP в том DNS, которым клиент устанавливает соединение. */
    constructor(network: Network? = null, budgets: FetchBudgets = FetchBudgets.CRAWL) : this(
        OkHttpClient.Builder()
            .apply { network?.socketFactory?.let { socketFactory(it) } }
            .proxy(Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    (network?.getAllByName(hostname)?.toList() ?: Dns.SYSTEM.lookup(hostname))
                        .filter(::isPublicWebAddress).ifEmpty {
                            throw UnknownHostException("No public address for source")
                        }
            })
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(budgets.retryConnection)
            .connectTimeout(budgets.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(budgets.readMs, TimeUnit.MILLISECONDS)
            .callTimeout(budgets.callMs, TimeUnit.MILLISECONDS)
            .build())

    suspend fun fetch(url: HttpUrl, resource: CrawlResource, validators: PageValidators): CrawlResponse =
        suspendCancellableCoroutine { task ->
            val request = Request.Builder().url(url)
                .header("User-Agent", "$CRAWLER_NAME/1").header("Accept", resource.accept).apply {
                    validators.etag?.let { header("If-None-Match", it) }
                    validators.modified?.let { header("If-Modified-Since", it) }
                }.build()
            val call = client.newCall(request)
            task.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (task.isActive) task.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val type = it.header("Content-Type").orEmpty()
                            val attachment = it.header("Content-Disposition").orEmpty().trim().startsWith("attachment", true)
                            val accepted = resource.accepts(type.substringBefore(';').trim()) &&
                                (!attachment || resource == CrawlResource.SITEMAP)
                            val code = if (it.code == 200 && !accepted) 415 else it.code
                            val data = if (code == 200) {
                                readBody(it, resource.limit) { task.isActive }
                            } else ByteArray(0)
                            CrawlResponse(code, type, data, it.header("Location"),
                                it.headers.values("X-Robots-Tag").joinToString(","),
                                PageValidators.from(it.header("ETag"), it.header("Last-Modified")))
                        }
                        if (task.isActive) task.resume(result)
                    } catch (error: Exception) {
                        if (task.isActive) task.resumeWithException(error)
                    }
                }
            })
        }

    private fun readBody(response: Response, limit: Int, active: () -> Boolean): ByteArray {
        val body = response.body ?: throw IOException("Missing body")
        if (body.contentLength() > limit) throw IOException("Page too large")
        return body.byteStream().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (active()) {
                val read = input.read(buffer)
                if (read < 0) return@use bytes.toByteArray()
                if (bytes.size() + read > limit) throw IOException("Page too large")
                bytes.write(buffer, 0, read)
            }
            throw IOException("Crawl cancelled")
        }
    }
}

internal fun isPublicWebAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 16) {
        // Global unicast без документальных и основных переходных диапазонов.
        return (bytes[0] and 224) == 32 && bytes.take(2) != listOf(32, 2) &&
            bytes.take(4) != listOf(32, 1, 13, 184) && bytes.take(4) != listOf(32, 1, 0, 0)
    }
    if (bytes.size != 4) return false
    val (a, b, c) = bytes
    return a !in setOf(0, 10, 127, 169) && a < 224 &&
        !(a == 100 && b in 64..127) && !(a == 172 && b in 16..31) &&
        !(a == 192 && b in setOf(0, 168, 88)) &&
        !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)) &&
        !(a == 203 && b == 0 && c == 113)
}
