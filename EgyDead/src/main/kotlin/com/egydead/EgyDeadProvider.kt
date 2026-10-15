package com.egydead

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class EgyDeadProvider : MainAPI() {
    override var mainUrl = "https://tv10.egydead.live"
    override var name = "EgyDead"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val hasMainPage = true
    override val hasQuickSearch = false

    override val mainPage = mainPageOf(
        "$mainUrl/h3/" to "المضاف حديثا",
        "$mainUrl/category/english-movies/" to "أفلام أجنبية",
        "$mainUrl/type/thriller/" to "إثارة",
        "$mainUrl/type/super-power/" to "قوة خارقة",
    )

    private fun detectType(catName: String?, href: String): TvType {
        val cat = catName.orEmpty()
        return when {
            cat.contains("انمي") || cat.contains("أنمي") || cat.contains("انميات") -> TvType.Anime
            cat.contains("مسلسل") || cat.contains("مسلسلات") || cat.contains("برامج") ||
                cat.contains("برنامج") -> TvType.TvSeries
            cat.contains("افلام") || cat.contains("أفلام") || cat.contains("فيلم") -> TvType.Movie
            href.contains("/episode/") || href.contains("/season/") ||
                href.contains("/serie/") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrl(attr("href"))
        if (!href.startsWith(mainUrl)) return null
        if (href.contains("/type/") || href.contains("/category/") ||
            href.contains("/year/") || href.contains("/quality/") ||
            href.contains("/country/") || href.contains("/language/")
        ) return null
        val titleEl = selectFirst("h1.BottomTitle") ?: selectFirst(".BottomTitle")
        val title = (titleEl?.text() ?: attr("title")).trim()
        if (title.isBlank()) return null
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))
        val catName = selectFirst("span.cat_name")?.text()
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        return when (detectType(catName, href)) {
            TvType.Anime -> newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            TvType.TvSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
            else -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun Document.parseCards(): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        select("li.movieItem > a, a:has(.BottomTitle)").forEach { a ->
            val item = runCatching { a.toCard() }.getOrNull() ?: return@forEach
            seen.putIfAbsent(item.url, item)
        }
        return seen.values.toList()
    }

    private fun Document.pageTitle(): String? =
        selectFirst(".singleTitle span em")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst(".singleTitle")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst("h1")?.text()?.trim()?.ifBlank { null }

    private fun Document.pagePoster(): String? =
        fixUrlNull(selectFirst(".single-thumbnail img")?.attr("src"))
            ?: fixUrlNull(selectFirst("meta[property=\"og:image\"]")?.attr("content"))

    private fun Document.pagePlot(): String? =
        selectFirst(".extra-content p")?.text()?.trim()?.ifBlank { null }
            ?: selectFirst(".singleStory")?.text()?.trim()?.ifBlank { null }

    private fun Document.pageYear(): Int? =
        selectFirst(".LeftBox a[href*=\"/year/\"]")?.text()?.trim()?.toIntOrNull()

    private fun Document.pageTags(): List<String> =
        select(".LeftBox a[href*=\"/type/\"]").map { it.text().trim() }.filter { it.isNotBlank() }

    private fun episodeNumberOf(href: String, text: String): Int? {
        Regex("""e(\d{1,4})""").find(href.substringAfter("/episode/", ""))?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return Regex("""\d{1,4}""").findAll(text).lastOrNull()?.value?.toIntOrNull()
    }

    private fun seasonNumberOf(href: String): Int? =
        Regex("""-s(\d{1,3})""").find(href)?.groupValues?.get(1)?.toIntOrNull()

    private fun Document.parseEpisodes(seasonNumber: Int?): List<Episode> {
        val byUrl = LinkedHashMap<String, Episode>()
        select("a[href*=\"/episode/\"]").forEach { a ->
            val href = fixUrl(a.attr("href"))
            if (!href.contains("/episode/")) return@forEach
            val epNum = episodeNumberOf(href, a.text())
            val ep = newEpisode(href, {
                this.name = a.text().trim().ifBlank { epNum?.let { n -> "الحلقة $n" } }
                this.season = seasonNumber
                this.episode = epNum
            })
            byUrl.putIfAbsent(href, ep)
        }
        return byUrl.values.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page/"
        val document = app.get(url).document
        val items = document.parseCards()
        val paginated = request.data.contains("/h3/") || request.data.contains("/category/")
        return newHomePageResponse(request.name, items, hasNext = paginated && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        return app.get(url).document.parseCards()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        return when {
            url.contains("/season/") -> {
                val title = document.pageTitle() ?: throw ErrorLoadingException("No title")
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, document.parseEpisodes(seasonNumberOf(url))) {
                    this.posterUrl = document.pagePoster()
                    this.plot = document.pagePlot()
                    this.year = document.pageYear()
                    this.tags = document.pageTags()
                }
            }
            url.contains("/serie/") -> {
                val title = document.pageTitle() ?: throw ErrorLoadingException("No title")
                val seasonLinks = LinkedHashMap<String, Int?>()
                document.select("a[href*=\"/season/\"]").forEach { a ->
                    val href = fixUrl(a.attr("href"))
                    if (href.contains("/season/")) seasonLinks.putIfAbsent(href, seasonNumberOf(href))
                }
                val episodes = mutableListOf<Episode>()
                if (seasonLinks.isEmpty()) {
                    episodes += document.parseEpisodes(1)
                } else {
                    seasonLinks.entries.forEachIndexed { index, (seasonUrl, seasonNum) ->
                        val seasonDoc = runCatching { app.get(seasonUrl).document }.getOrNull()
                            ?: return@forEachIndexed
                        episodes += seasonDoc.parseEpisodes(seasonNum ?: (index + 1))
                    }
                }
                newTvSeriesLoadResponse(
                    title, url, TvType.TvSeries,
                    episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: Int.MAX_VALUE }))
                ) {
                    this.posterUrl = document.pagePoster()
                    this.plot = document.pagePlot()
                    this.year = document.pageYear()
                    this.tags = document.pageTags()
                }
            }
            url.contains("/episode/") -> {
                val seasonUrl = document.select("a[href*=\"/season/\"]")
                    .map { fixUrl(it.attr("href")) }
                    .firstOrNull { it.contains("/season/") }
                val serieUrl = document.select("a[href*=\"/serie/\"]")
                    .map { fixUrl(it.attr("href")) }
                    .firstOrNull { it.contains("/serie/") }
                when {
                    seasonUrl != null && seasonUrl != url -> load(seasonUrl)
                    serieUrl != null && serieUrl != url -> load(serieUrl)
                    else -> {
                        val title = document.pageTitle() ?: throw ErrorLoadingException("No title")
                        val single = newEpisode(url, {
                            this.name = title
                            this.season = seasonNumberOf(url)
                            this.episode = episodeNumberOf(url, title)
                        })
                        newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(single)) {
                            this.posterUrl = document.pagePoster()
                            this.plot = document.pagePlot()
                            this.year = document.pageYear()
                            this.tags = document.pageTags()
                        }
                    }
                }
            }
            else -> {
                val title = document.pageTitle() ?: throw ErrorLoadingException("No title")
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = document.pagePoster()
                    this.plot = document.pagePlot()
                    this.year = document.pageYear()
                    this.tags = document.pageTags()
                }
            }
        }
    }

    private fun collectEmbeds(document: Document, into: LinkedHashSet<String>) {
        document.select(".serversList li").forEach { li ->
            li.attr("data-link").trim().takeIf { it.isNotBlank() }?.let { into.add(fixUrl(it)) }
        }
        document.selectFirst(".watchAreaMaster .holder iframe")?.attr("src")
            ?.trim()?.takeIf { it.isNotBlank() }?.let { into.add(fixUrl(it)) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embeds = LinkedHashSet<String>()
        runCatching {
            app.post(data, data = mapOf("View" to "1"), referer = data).document
        }.getOrNull()?.let { collectEmbeds(it, embeds) }
        if (embeds.isEmpty()) {
            runCatching { app.get(data).document }.getOrNull()?.let { collectEmbeds(it, embeds) }
        }
        if (embeds.isEmpty()) return false
        var found = false
        for (embed in embeds) {
            if (loadExtractor(embed, data, subtitleCallback, callback)) found = true
            val pageText = runCatching { app.get(embed, referer = data).text }.getOrNull()
            if (pageText != null) {
                val playlist = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(pageText)?.value
                val file = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(pageText)?.value
                when {
                    playlist != null -> {
                        callback(
                            newExtractorLink(name, name, playlist, ExtractorLinkType.M3U8) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                    file != null -> {
                        callback(
                            newExtractorLink(name, name, file, ExtractorLinkType.VIDEO) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                }
            }
        }
        return found
    }
}
