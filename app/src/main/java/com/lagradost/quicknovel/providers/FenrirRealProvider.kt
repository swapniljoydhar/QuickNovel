package com.lagradost.quicknovel.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newReview
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import org.jsoup.Jsoup

class FenrirRealProvider : MainAPI() {
    override val name = "FenrirRealm"
    override val mainUrl = "https://fenrirealm.com"
    override val iconId = R.drawable.icon_fenrirealm
    override val hasMainPage = true

    // The site rate limits chapter reads (roughly 30/min, then 429 "Too Many Attempts."
    // for ~40s), so requests are spaced out. Without this the site throttles/timeouts.
    override val rateLimitTime = 3000L
    override val hasReviews = true
    override val usesCloudFlareKiller = true

    private val apiUrl = "$mainUrl/api/new/v2"

    override val mainCategories = listOf(
        "All" to "any",
        "Completed" to "completed",
        "Ongoing" to "on-going"
    )
    override val orderBys =
        listOf(
            "Popular" to "popular",
            "Latest" to "latest",
            "Updated" to "updated"
        )
    override val tags = listOf(
        "All" to "0",
        "Action" to "1",
        "Adult" to "2",
        "Adventure" to "3",
        "Comedy" to "4",
        "Drama" to "5",
        "Ecchi" to "6",
        "Fantasy" to "7",
        "Gender Bender" to "8",
        "Harem" to "9",
        "Historical" to "10",
        "Horror" to "11",
        "Josei" to "12",
        "Martial Arts" to "13",
        "Mature" to "14",
        "Mecha" to "15",
        "Mystery" to "16",
        "Psychological" to "17",
        "Romance" to "18",
        "School Life" to "19",
        "Sci-fi" to "20",
        "Seinen" to "21",
        "Shoujo" to "22",
        "Shoujo Ai" to "23",
        "Shounen" to "24",
        "Shounen Ai" to "25",
        "Slice of Life" to "26",
        "Smut" to "27",
        "Sports" to "28",
        "Supernatural" to "29",
        "Tragedy" to "30",
        "Wuxia" to "31",
        "Xianxia" to "32",
        "Xuanhuan" to "33",
        "Yaoi" to "34",
        "Yuri" to "35",
    )

    /** urls can be /series/{slug} or a deep linked /series/{slug}/{chapter} */
    fun String.getSlugFromUrl() = this
        .replace("$mainUrl/series/", "")
        .trim('/')
        .substringBefore('/')

    private val apiHeaders
        get() = mapOf(
            "Accept" to "application/json",
            "Referer" to "$mainUrl/"
        )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url =
            if (tag != "0") "$apiUrl/series?page=$page&per_page=12&status=$mainCategory&sort=$orderBy&tags%5B%5D=$tag"
            else "$apiUrl/series?page=$page&per_page=24&status=$mainCategory&sort=$orderBy"
        val document = app.get(url).parsed<FenrirMainPageResponse>()
        val returnValue = document.data.map { novel ->
            newSearchResponse(
                name = novel.title,
                url = mainUrl + "/series/" + novel.slug
            ) {
                posterUrl = fixUrlNull(novel.cover)
            }

        }
        return HeadMainPageResponse(url, returnValue)
    }

    suspend fun getRelated(url: String): List<SearchResponse> {
        val name = url.getSlugFromUrl()
        val relatedUrl = "$apiUrl/series/$name/recommendations?limit=10"
        val document = app.get(relatedUrl).parsed<FenrirMainPageResponse>()
        return document.data.map { element ->
            val href = "$mainUrl/series/${element.slug}"
            val title = element.title
            newSearchResponse(
                name = title,
                url = href
            ) {
                posterUrl = fixUrlNull(
                    element.cover
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.getSlugFromUrl()

        // Series metadata comes from the REST API instead of scraping the
        // SvelteKit page (the page no longer reliably embeds seriesData).
        val details = app.get("$apiUrl/series/$slug", headers = apiHeaders)
            .parsed<SeriesDetails>()

        val chapters = app
            .get("$apiUrl/series/$slug/chapters", headers = apiHeaders)
            .parsed<Array<ChapterInf>>()
            .mapNotNull { ch ->
                val id = ch.id ?: return@mapNotNull null
                if ((ch.locked?.price ?: 0) > 0) {
                    null // premium chapters are skipped, api only returns a teaser anyway
                } else {
                    val chName = ch.name.trim()
                    // "name" and "title" are often the same text (" Chapter 1" / "Chapter 1")
                    val display = if (ch.title.isNullOrBlank() ||
                        ch.title.trim().equals(chName, ignoreCase = true)
                    ) chName else "$chName - ${ch.title.trim()}"
                    newChapterData(
                        display,
                        "$apiUrl/chapters/$id"
                    ) {
                        dateOfRelease = ch.updatedAt?.split("T")?.firstOrNull()
                    }
                }
            }

        val title =
            details.title?.trim().takeIf { !it.isNullOrEmpty() }
                ?: throw ErrorLoadingException("Title not found")

        return newStreamResponse(title, url, chapters) {
            this.author = details.user?.name?.trim()
            this.posterUrl = fixUrlNull(details.cover)
            this.synopsis = details.description?.trim()
            this.tags = (details.genres.orEmpty() + details.tags.orEmpty())
                .mapNotNull { t -> t.name?.trim()?.takeIf { it.isNotEmpty() } }
                .distinct()
            this.views = details.stats?.totalViews
            setStatus(details.status)
            related = getRelated(url)
            reviewData = details.id?.toString()
        }
    }


    override suspend fun loadReviews(url: String, page: Int, data: String?): List<UserReview> {
        val libraryId = data ?: return emptyList()

        val realUrl = "$apiUrl/comments/series/$libraryId?page=$page&sort=latest"
        val res = app.get(realUrl).parsed<CommentsResponse>()
        return res.data.map { comment ->
            newReview(comment.content) {
                username = comment.user.username
                date = comment.createdAt.split("T").firstOrNull()
                avatarUrl = comment.user.avatar
            }
        }
    }

    // ======================== chapter content ========================

    /**
     * Zero-width / invisible characters the site injects as a per-reader
     * fingerprint between words. Useless for reading, breaks TTS and epubs.
     */
    private val invisibleChars = Regex("[\\u200B-\\u200F\\u2060-\\u2064\\uFEFF\\u00AD]")

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Removes anti-scrape watermarks: hidden junk divs, style tag, zero-width chars */
    private fun cleanChapterHtml(body: String): String {
        val document = Jsoup.parse(body)
        document.select("script, style, iframe, svg, noscript").remove()
        document.select("[aria-hidden=\"true\"]").remove()
        // fix relative image/anchor urls
        document.select("[src^=\"/\"]").forEach { it.attr("src", mainUrl + it.attr("src")) }
        document.select("a[href^=\"/\"]").forEach { it.attr("href", mainUrl + it.attr("href")) }
        return document.body().html().replace(invisibleChars, "")
    }

    /** "text" content_format: plain lines, used e.g. for premium teasers */
    private fun textToHtml(text: String): String =
        text.split(Regex("\r\n|\r|\n")).joinToString("") { line ->
            val trimmed = line.trim().replace(invisibleChars, "")
            if (trimmed.isEmpty()) "" else "<p>${escapeHtml(trimmed)}</p>"
        }

    /** "json" content_format: TipTap document stored as a string (older chapters) */
    private val tiptapMapper = ObjectMapper()

    private fun tiptapToHtml(body: String): String {
        val doc = runCatching { tiptapMapper.readTree(body) }.getOrNull()
            ?: throw ErrorLoadingException("Invalid chapter content")
        val sb = StringBuilder()
        doc.path("content")?.takeIf { it.isArray }?.forEach { node ->
            renderTipTapNode(node, sb)
        }
        return sb.toString().replace(invisibleChars, "")
    }

    private fun renderTipTapNode(node: JsonNode, sb: StringBuilder) {
        when (node.path("type").asText()) {
            "paragraph" -> appendWrapped("p", node, sb)
            "heading" -> {
                val level = node.path("attrs").path("level").asInt(2).coerceIn(1, 6)
                appendWrapped("h$level", node, sb)
            }
            "blockquote" -> appendWrapped("blockquote", node, sb)
            "bulletList" -> appendWrapped("ul", node, sb)
            "orderedList" -> appendWrapped("ol", node, sb)
            "listItem" -> appendWrapped("li", node, sb)
            "horizontalRule" -> sb.append("<hr/>")
            "hardBreak" -> sb.append("<br/>")
            "image" -> appendTipTapImage(node, sb)
            else -> renderTipTapChildren(node, sb)
        }
    }

    private fun appendWrapped(tag: String, node: JsonNode, sb: StringBuilder) {
        val inner = StringBuilder()
        renderTipTapChildren(node, inner)
        if (inner.isNotBlank()) {
            sb.append('<').append(tag).append('>')
                .append(inner)
                .append("</").append(tag).append('>')
        }
    }

    private fun appendTipTapImage(node: JsonNode, sb: StringBuilder) {
        val src = node.path("attrs").path("src").asText().takeIf { it.isNotBlank() } ?: return
        val fixed = if (src.startsWith("/")) mainUrl + src else src
        sb.append("<img src=\"").append(fixed.replace("\"", "&quot;")).append("\"/>")
    }

    private fun renderTipTapChildren(node: JsonNode, sb: StringBuilder) {
        node.path("content")?.takeIf { it.isArray }?.forEach { child ->
            if (child.path("type").asText() == "text") {
                var text = escapeHtml(child.path("text").asText())
                child.path("marks")?.takeIf { it.isArray }?.forEach { mark ->
                    when (mark.path("type").asText()) {
                        "bold" -> text = "<b>$text</b>"
                        "italic" -> text = "<i>$text</i>"
                        "underline" -> text = "<u>$text</u>"
                        "strike" -> text = "<strike>$text</strike>"
                        "code" -> text = "<code>$text</code>"
                        "link" -> {
                            val href = mark.path("attrs").path("href").asText()
                            if (href.isNotBlank()) {
                                text = "<a href=\"${href.replace("\"", "&quot;")}\">$text</a>"
                            }
                        }
                    }
                }
                sb.append(text)
            } else {
                renderTipTapNode(child, sb)
            }
        }
    }

    override suspend fun loadHtml(url: String): String {
        val response = app.get(url, headers = apiHeaders).parsed<ChapterContentResponse>()

        // api errors come back as { "message": ... }, notably the reader rate
        // limit (429 "Too Many Attempts." with rateLimit.retryAfterSeconds)
        val message = response.message
        if (message != null && response.content == null) {
            throw ErrorLoadingException("FenrirRealm: $message")
        }

        val format = response.contentFormat ?: "json"
        val body = response.content?.trim() ?: ""
        val price = response.locked?.price ?: 0

        // locked chapters only return a "..." body or a text teaser
        if (format == "locked" || body == "..." || (price > 0 && format == "text")) {
            throw ErrorLoadingException(
                if (price > 0) "This chapter is premium on FenrirRealm ($price runes), it can only be unlocked on the site"
                else "This chapter is premium on FenrirRealm, it can only be unlocked on the site"
            )
        }
        if (body.isEmpty()) throw ErrorLoadingException("Chapter has no text")

        return when (format) {
            "html" -> cleanChapterHtml(body)
            "text" -> textToHtml(body)
            else -> tiptapToHtml(body)
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$apiUrl/series?page=1&per_page=5&search=${
            android.net.Uri.encode(
                query.trim()
            )
        }"
        val document = app.get(url).parsed<FenrirMainPageResponse>()

        return document.data.map { novel ->
            val title = novel.title
            val novelUrl = mainUrl + "/series/" + novel.slug
            newSearchResponse(title, novelUrl) {
                posterUrl = fixUrlNull(novel.cover)
            }
        }
    }


    // ======================== DTOs ========================

    data class FenrirMainPageResponse(
        @JsonProperty("data")
        val data: List<Daum>,
    )

    data class Daum(
        // Novel inf
        @JsonProperty("title")
        val title: String,
        @JsonProperty("slug")
        val slug: String,
        @JsonProperty("cover")
        val cover: String,
    )

    data class ChapterInf(
        @JsonProperty("id")
        val id: Long?,
        @JsonProperty("slug")
        val slug: String?,
        @JsonProperty("name")
        val name: String,
        @JsonProperty("title")
        val title: String?,
        @JsonProperty("updated_at")
        val updatedAt: String?,
        @JsonProperty("locked")
        val locked: Locked?,
    )

    data class Locked(
        @JsonProperty("price")
        val price: Int,
    )

    data class SeriesDetails(
        @JsonProperty("id")
        val id: Long?,
        @JsonProperty("title")
        val title: String?,
        @JsonProperty("status")
        val status: String?,
        @JsonProperty("description")
        val description: String?,
        @JsonProperty("cover")
        val cover: String?,
        @JsonProperty("user")
        val user: SeriesUser?,
        @JsonProperty("genres")
        val genres: List<TagDto>?,
        @JsonProperty("tags")
        val tags: List<TagDto>?,
        @JsonProperty("stats")
        val stats: SeriesStats?,
    )

    data class SeriesUser(
        @JsonProperty("username")
        val username: String?,
        @JsonProperty("name")
        val name: String?,
    )

    data class TagDto(
        @JsonProperty("name")
        val name: String?,
    )

    data class SeriesStats(
        @JsonProperty("total_views")
        val totalViews: Int?,
    )

    data class ChapterContentResponse(
        @JsonProperty("content_format")
        val contentFormat: String? = null,
        @JsonProperty("content")
        val content: String? = null,
        @JsonProperty("locked")
        val locked: Locked? = null,
        @JsonProperty("message")
        val message: String? = null,
        @JsonProperty("name")
        val name: String? = null,
        @JsonProperty("title")
        val title: String? = null,
    )

    data class CommentsResponse(
        @JsonProperty("data")
        val data: List<CommentData>
    )

    data class CommentData(
        @JsonProperty("content")
        val content: String,
        @JsonProperty("created_at")
        val createdAt: String,
        @JsonProperty("user")
        val user: CommentUser
    )

    data class CommentUser(
        @JsonProperty("username")
        val username: String,
        @JsonProperty("avatar")
        val avatar: String?
    )
}
