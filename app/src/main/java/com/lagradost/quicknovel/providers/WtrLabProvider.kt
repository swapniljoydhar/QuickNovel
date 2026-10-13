package com.lagradost.quicknovel.providers

import android.net.Uri
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.network.WebViewResolver
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newReview
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import com.lagradost.quicknovel.util.AppUtils.parseJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt


class WtrLabProvider : MainAPI() {
    override val mainUrl = "https://wtr-lab.com"
    override val name = "WTR-LAB"
    override val lang = "en"
    override val iconId = R.drawable.icon_wtrlab
    override val hasMainPage = true
    override val usesCloudFlareKiller = true
    override val hasReviews = true

    // The AI translation endpoint is protected: going too fast trips 429s/protection.
    // APIRepository only rate limits load(), so loadHtml() throttles itself below.
    override val rateLimitTime = 1000L

    override val mainCategories = listOf(
        "All" to "all",
        "Ongoing" to "ongoing",
        "Completed" to "completed"
    )

    //&orderBy=
    override val orderBys = listOf(
        "Date" to "date",
        "Name" to "name",
        "View" to "view",
        "Reader" to "reader",
        "Chapter" to "chapter"
    )
    override val tags = listOf(
        "All" to "",
        "Action" to "1",
        "Adult" to "2",
        "Adventure" to "3",
        "Anime" to "4",
        "Arts" to "5",
        "Comedy" to "6",
        "Drama" to "7",
        "Eastern" to "8",
        "Ecchi" to "ecchi",
        "Fan-fiction" to "9",
        "Fantasy" to "10",
        "Game" to "11",
        "Gender-bender" to "12",
        "Harem" to "13",
        "Historical" to "14",
        "Horror" to "15",
        "Isekai" to "16",
        "Josei" to "17",
        "Lgbt" to "18",
        "Magic" to "19",
        "Magical-realism" to "20",
        "Manhua" to "21",
        "Martial-arts" to "22",
        "Mature" to "23",
        "Mecha" to "24",
        "Military" to "25",
        "Modern-life" to "26",
        "Movies" to "27",
        "Mystery" to "28",
        "Other" to "29",
        "Psychological" to "30",
        "Realistic-fiction" to "31",
        "Reincarnation" to "32",
        "Romance" to "33",
        "School-life" to "34",
        "Sci-fi" to "35",
        "Seinen" to "36",
        "Shoujo" to "37",
        "Shoujo-ai" to "38",
        "Shounen" to "39",
        "Shounen-ai" to "40",
        "Slice-of-life" to "41",
        "Smut" to "42",
        "Sports" to "43",
        "Supernatural" to "44",
        "System" to "45",
        "Tragedy" to "46",
        "Urban" to "47",
        "Urban-life" to "48",
        "Video-games" to "49",
        "War" to "50",
        "Wuxia" to "51",
        "Xianxia" to "52",
        "Xuanhuan" to "53",
        "Yaoi" to "54",
        "Yuri" to "55"
    )


    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url =
            "$mainUrl/en/novel-list?page=$page&status=$mainCategory&orderBy=$orderBy&genre=$tag"
        val doc = app.get(url).document
        val returnValue = doc.select(".series-list>div").mapNotNull { select ->
            val titleHolder = select.selectFirst("a") ?: return@mapNotNull null
            val href = titleHolder.attr("href")
            val name = titleHolder.attr("title")
            newSearchResponse(name, href) {
                posterUrl = fixUrlNull(select.selectFirst("a img")?.attr("src"))
            }
        }
        return HeadMainPageResponse(url, returnValue)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/en/novel-finder?text=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select(".series-list>div").mapNotNull { select ->
            val titleHolder = select.selectFirst("a") ?: return@mapNotNull null
            val href = titleHolder.attr("href")
            val name = titleHolder.attr("title")
            newSearchResponse(name, href) {
                posterUrl = fixUrlNull(select.selectFirst("a img")?.attr("src"))
            }
        }
    }

    private suspend fun getChapterRange(
        url: String,
        rawId: Long,
        start: Long,
        end: Long
    ): List<ChapterData> {
        val chapterDataUrl = "$mainUrl/api/chapters/$rawId?start=$start&end=$end"
        val chaptersDataJson = app.get(chapterDataUrl).text
        val chaptersData = parseJson<ResultChaptersJsonResponse.Root>(chaptersDataJson)
        return chaptersData.chapters.map { chapter ->
            newChapterData(
                "#${chapter.order} ${chapter.title}",
                // raw_id/chapter_id let loadHtml hit /api/reader/get directly,
                // without re-fetching this page just for __NEXT_DATA__
                "${url.trimEnd('/')}/chapter-${chapter.order}?raw_id=$rawId&chapter_id=${chapter.id}"
            ) {
                dateOfRelease = chapter.updatedAt
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: throw ErrorLoadingException("No title")
        val jsonNode = doc.selectFirst("#__NEXT_DATA__")
        val json = jsonNode?.data() ?: throw ErrorLoadingException("no chapters")
        val chaptersJson = parseJson<ResultJsonResponse.Root>(json)

        val rawId = chaptersJson.props.pageProps.serie.serieData.rawId
        val rawChapterCount = chaptersJson.props.pageProps.serie.serieData.rawChapterCount

        // The chapters api returns at most 500 entries per call
        val chapters = mutableListOf<ChapterData>()
        var start = 1L
        while (start <= rawChapterCount) {
            val end = minOf(start + CHAPTER_PAGE_SIZE - 1, rawChapterCount)
            chapters.addAll(getChapterRange(url, rawId, start, end))
            start += CHAPTER_PAGE_SIZE
        }
        val uniqueChapters = chapters.distinctBy { it.url }

        val id = chaptersJson.props.pageProps.serie.serieData.id.toString()
        return newStreamResponse(title, url, uniqueChapters) {
            synopsis = doc.selectFirst(".desc-wrap")?.text()
            posterUrl = fixUrlNull(doc.selectFirst(".image-wrap > img")?.attr("src"))
            val details = doc.select("div.detail-buttons div")
            details.forEach { div ->
                if (div.text().contains("Views")) {
                    val text = div.ownText().split(" ")
                    this.views = text.getOrNull(2)?.trim()?.toIntOrNull()
                    setStatus(text.getOrNull(0)?.trim())
                }
            }
            val ratingElement = details.selectFirst("div.rating")
            val ratingText = ratingElement?.text()

            peopleVoted = ratingText?.let { text ->
                Regex("""\((\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            }
            rating = ratingText?.let { text ->
                Regex("""([\d.]+)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                    it.times(20).times(10).roundToInt()
                }
            }
            reviewData = id
            related = getRelated(url, id)
        }
    }

    suspend fun getRelated(url: String, id: String): List<SearchResponse> {
        val url = "$mainUrl/api/v2/novel/similar/$id"
        val response = app.get(url).parsedSafe<RelatedResponse>()

        return response?.data?.map { item ->
            val title = item.innerData.title
            val id = item.id
            val slug = item.slug

            val href = "$mainUrl/en/novel/$id/$slug"

            newSearchResponse(
                name = title,
                url = href
            ) {
                posterUrl = fixUrlNull(item.innerData.image)
            }
        } ?: emptyList()
    }

    override suspend fun loadReviews(url: String, page: Int, data: String?): List<UserReview> {
        val id = data ?: return emptyList()
        val realUrl =
            "$mainUrl/api/review/get?serie_id=$id&page=${page - 1}&sort=most_liked"
        val res = app.get(realUrl).parsedSafe<ReviewResponse>()
        return res?.data?.mapNotNull { item ->
            val reviewTxt = item.comment ?: return@mapNotNull null

            newReview(reviewTxt) {
                username = item.username
                date = item.createdAt
                rating = item.rate?.times(200)
            }
        } ?: emptyList()
    }

    // ======================== chapter content ========================

    /** Thrown when the pre-generated AI (english) translation is not there yet */
    private class AiUnavailableException(message: String) : ErrorLoadingException(message)

    private val chapterOrderRegex = Regex("""chapter-(\d+)""")

    /** WTR marks glossed terms in the translated text as ※8⛬ style placeholders */
    private val glossaryMarkerRegex = Regex("※(\\d+)(?:[⛬〓〰●•\\uFE0F]+)?")

    private val encryptedTripleRegex = Regex("^[A-Za-z0-9+/_=-]+:[A-Za-z0-9+/_=-]+:[A-Za-z0-9+/_=-]+$")

    private val aiLockedRegex = Regex(
        """locked|unlock|ticket|pending|processing|translation.{0,30}(queue|pending)|not\s+translated|translation\s+not\s+available|translation\s+not\s+found""",
        RegexOption.IGNORE_CASE
    )

    private val cjkRegex = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]")
    private val latinRegex = Regex("[A-Za-z\\u00C0-\\u024F]")

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * The AI translation api occasionally returns the untranslated raw text.
     * Detect that so chapters never silently get saved in Chinese.
     */
    private fun looksMostlyCJK(text: String): Boolean {
        val s = text.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return false
        val cjk = cjkRegex.findAll(s).count()
        val latin = latinRegex.findAll(s).count()
        val letters = cjk + latin
        if (cjk < 20) return false
        if (latin == 0) return true
        return cjk.toDouble() / letters >= 0.35
    }

    private fun extractApiMessage(root: JsonNode): String {
        val candidates = listOf(
            root.path("message"),
            root.path("error"),
            root.path("error").path("message"),
            root.path("data").path("message"),
            root.path("data").path("error"),
            root.path("data").path("data").path("message"),
            root.path("data").path("data").path("error"),
            root.path("reason"),
            root.path("detail"),
            root.path("status_message"),
            root.path("data").path("status_message"),
        )
        return candidates.firstOrNull { node -> node != null && node.isTextual && node.asText().isNotBlank() }
            ?.asText()?.take(300) ?: ""
    }

    private fun glossaryTerm(terms: JsonNode, idx: Int): String? {
        if (!terms.isArray) return null
        val entry = terms.get(idx) ?: return null
        when {
            entry.isTextual -> return entry.asText().trim()
                .takeIf { it.isNotEmpty() && !it.contains("※") }

            entry.isArray -> {
                for (candidate in entry) {
                    if (candidate.isTextual) {
                        val text = candidate.asText().trim()
                        if (text.isNotEmpty() && !text.contains("※")) return text
                    }
                }
            }

            entry.isObject -> {
                for (key in listOf("term", "text", "translation", "name")) {
                    val value = entry.path(key)
                    if (value.isTextual) {
                        return value.asText().trim().takeIf { it.isNotEmpty() }
                    }
                }
            }
        }
        return null
    }

    private fun resolveGlossary(text: String, terms: JsonNode): String {
        if (!terms.isArray || !text.contains('※')) return text
        return glossaryMarkerRegex.replace(text) { match ->
            val idx = match.groupValues.getOrNull(1)?.toIntOrNull()
            (idx?.let { glossaryTerm(terms, it) } ?: match.value).take(2000)
        }
    }

    private fun looksLikeEncryptedTriple(value: String): Boolean {
        val s = value.trim()
        return s.length >= 24 && !s.contains(Regex("\\s")) &&
                encryptedTripleRegex.matches(s) &&
                s.split(":").all { it.length >= 8 }
    }

    /**
     * Fetches WTR's pre-generated AI translation from /api/reader/get.
     * translate="ai" is the important part: "web" returns the raw Chinese body.
     */
    private suspend fun loadHtmlAi(url: String, order: Long): String {
        val uri = Uri.parse(url)
        var rawId = uri.getQueryParameter("raw_id")
        var chapterId = uri.getQueryParameter("chapter_id")?.toLongOrNull()

        // Fallback for deep links without the ids: pull them from the page json
        if (rawId.isNullOrEmpty() || chapterId == null) {
            val urlWithService = if (url.contains("?")) "$url&service=web" else "$url?service=web"
            val doc = app.get(urlWithService).document
            val nextData = doc.selectFirst("#__NEXT_DATA__")?.data()
                ?: throw ErrorLoadingException("WTR-LAB page metadata not found")
            val chapterPage = parseJson<LoadJsonResponse.Root>(nextData)
            rawId = chapterPage.props.pageProps.serie.serieData.rawId.toString()
            chapterId = chapterPage.props.pageProps.serie.chapter.id
        }
        val finalRawId = rawId ?: throw ErrorLoadingException("WTR-LAB raw id not found")

        val payload = mutableMapOf<String, Any>(
            "translate" to "ai",
            "language" to "en",
            "raw_id" to finalRawId,
            "chapter_no" to order,
            "retry" to false,
            "force_retry" to false,
        )
        chapterId?.let { payload["chapter_id"] = it }

        val responseText = app.post(
            url = "$mainUrl/api/reader/get",
            headers = mapOf("Accept" to "application/json", "Referer" to url),
            json = payload
        ).text

        val root = parseJson<JsonNode>(responseText)
        val data = root.path("data").path("data")
        val bodyNode = data.path("body")
        if (!bodyNode.isTextual || bodyNode.asText().isBlank()) {
            val message = extractApiMessage(root)
            when {
                aiLockedRegex.containsMatchIn(responseText) ->
                    throw AiUnavailableException(
                        "Chapter $order: AI translation is locked/pending on WTR-LAB (${message.ifBlank { "locked" }})"
                    )

                message.isNotBlank() ->
                    throw ErrorLoadingException("WTR-LAB: $message")

                else ->
                    throw AiUnavailableException("Chapter $order: AI translation is not available on WTR-LAB yet")
            }
        }

        val rawBody = bodyNode.asText()
        val terms = data.path("glossary_data").path("terms")
        val images = data.path("images").takeIf { it.isArray }
            ?.mapNotNull { img -> img.asText().takeIf { it.isNotBlank() } }
            ?: emptyList()

        val parts: List<String> =
            if (rawBody.startsWith("arr:") || rawBody.startsWith("str:") || looksLikeEncryptedTriple(rawBody)) {
                val decrypted = decryptContent(rawBody)
                // arr mode is one entry per paragraph, str mode is one blob split by newlines
                if (decrypted.size <= 1) decrypted.flatMap { it.split(Regex("\\r?\\n+")) }
                else decrypted
            } else {
                rawBody.split(Regex("\\r?\\n+"))
            }

        val text = StringBuilder()
        var imageIndex = 0
        for (part in parts) {
            val paragraph = part.trim()
            if (paragraph.isEmpty()) continue
            if (paragraph == "[image]") {
                images.getOrNull(imageIndex++)?.let { img ->
                    val src = if (img.startsWith("/")) mainUrl + img else img
                    text.append("<img src=\"").append(src.replace("\"", "&quot;")).append("\"/>")
                }
                continue
            }
            // strip any leftover tags, then resolve ※N⛬ glossary placeholders
            val plain = if (paragraph.contains('<')) Jsoup.parse(paragraph).text() else paragraph
            val resolved = resolveGlossary(plain, terms).trim()
            if (resolved.isNotEmpty()) {
                text.append("<p>").append(escapeHtml(resolved)).append("</p>")
            }
        }

        val result = text.toString()
        if (result.isBlank()) throw AiUnavailableException("Chapter $order: AI translation returned an empty body")
        if (looksMostlyCJK(result)) throw AiUnavailableException(
            "Chapter $order: AI translation not available - WTR-LAB returned the raw Chinese text"
        )
        return result
    }

    override suspend fun loadHtml(url: String): String {
        val order = chapterOrderRegex.find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw ErrorLoadingException("WTR-LAB could not parse the chapter number from $url")

        // downloads do not pass through the repository rate limiter, throttle here instead
        rateLimitMutex.withLock { delay(rateLimitTime) }

        try {
            return loadHtmlAi(url, order)
        } catch (e: AiUnavailableException) {
            // fail closed: without an AI translation the site just shows the raw Chinese text
            throw e
        } catch (e: Exception) {
            // last resort: render the reader page in a WebView (site's own translation pipeline)
            val urlWithService =
                if (url.contains("?")) "$url&service=web" else "$url?service=web"
            val script = """
                             (function() {
                                 var checkInterval = setInterval(function() {
                                     var element = document.querySelector("div.chapter-body");
                                     var firstLine = element ? element.querySelector("div[data-line]") : null;
                                     if (firstLine && firstLine.innerText.trim().length > 0) {
                                         clearInterval(checkInterval);
                                         NativeAndroid.onElementFound(element.innerHTML);
                                     }
                                 }, 1000);
                                 setTimeout(function() { clearInterval(checkInterval); }, 30000);
                             })();
                         """.trimIndent()
            val html = WebViewResolver(scriptToFinish = script, useOkhttp = false)
                .resolveUsingWebView(urlWithService)
                ?: throw ErrorLoadingException("WTR-LAB: could not load chapter $order")

            if (looksMostlyCJK(html)) throw ErrorLoadingException(
                "Chapter $order: no AI translation on WTR-LAB yet (page is still in Chinese)"
            )
            return html
        }
    }

    fun decryptContent(encryptedText: String): List<String> {
        if (encryptedText.isEmpty()) return emptyList()

        var isArray = false
        var rawText = encryptedText

        if (encryptedText.startsWith("arr:")) {
            isArray = true
            rawText = encryptedText.removePrefix("arr:")
        } else if (encryptedText.startsWith("str:")) {
            rawText = encryptedText.removePrefix("str:")
        }

        val parts = rawText.split(":")
        if (parts.size != 3) throw IllegalArgumentException("Invalid format")

        val ivBytes = Base64.decode(parts[0], Base64.DEFAULT)
        val shortCipher = Base64.decode(parts[1], Base64.DEFAULT)
        val longCipher = Base64.decode(parts[2], Base64.DEFAULT)

        val cipherBytes = ByteArray(longCipher.size + shortCipher.size)
        System.arraycopy(longCipher, 0, cipherBytes, 0, longCipher.size)
        System.arraycopy(shortCipher, 0, cipherBytes, longCipher.size, shortCipher.size)

        val keyString = "IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru"
        val keyBytes = keyString.substring(0, 32).toByteArray(Charsets.UTF_8)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(cipherBytes)
        val decryptedText = decryptedBytes.toString(Charsets.UTF_8)

        return if (isArray) {
            parseJson<List<String>>(decryptedText)
        } else {
            listOf(decryptedText)
        }
    }

    companion object {
        private const val CHAPTER_PAGE_SIZE = 500L
    }

    data class RelatedResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("data") val data: List<RelatedItem>? = null
    )

    data class RelatedItem(
        @JsonProperty("raw_id") val id: Long,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("data") val innerData: RelatedInnerData,
    )

    data class RelatedInnerData(
        @JsonProperty("title") val title: String,
        @JsonProperty("image") val image: String? = null,
    )

    data class ReviewResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: List<ReviewItem>? = null
    )

    data class ReviewItem(
        @JsonProperty("comment") val comment: String? = null,
        @JsonProperty("rate") val rate: Int? = null,
        @JsonProperty("username") val username: String? = null,
        @JsonProperty("created_at") val createdAt: String? = null,
        @JsonProperty("user_id") val userId: String? = null
    )

    object ResultChaptersJsonResponse {
        data class Root(
            val chapters: List<Chapter>,
        )

        data class Chapter(
            @JsonProperty("serie_id")
            val serieId: Long,
            val id: Long,
            val order: Long,
            val title: String,
            val name: String,
            @JsonProperty("updated_at")
            val updatedAt: String,
        )
    }

    object ResultJsonResponse {
        data class Root(
            val props: Props,
        )

        data class Props(
            val pageProps: PageProps,
        )

        data class PageProps(
            val serie: Serie,
        )

        data class Serie(
            @JsonProperty("serie_data")
            val serieData: SerieData,
        )

        data class SerieData(
            @JsonProperty("raw_id")
            val rawId: Long,
            @JsonProperty("id")
            val id: Long,
            @JsonProperty("raw_chapter_count")
            val rawChapterCount: Long,
        )
    }

    /** only used by the fallback that reads the chapter page's __NEXT_DATA__ */
    object LoadJsonResponse {
        data class Root(
            val props: Props,
        )

        data class Props(
            val pageProps: PageProps,
        )

        data class PageProps(
            val serie: Serie,
        )

        data class Chapter(
            val id: Long,
        )

        data class Serie(
            @JsonProperty("serie_data")
            val serieData: SerieData,
            val chapter: Chapter,
        )

        data class SerieData(
            val id: Long,
            @JsonProperty("raw_id")
            val rawId: Long,
        )
    }
}
