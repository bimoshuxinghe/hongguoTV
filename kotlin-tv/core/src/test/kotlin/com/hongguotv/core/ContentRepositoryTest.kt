package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    private fun streamRepository(vararg variants: Triple<Int,String,String>) = repository { request ->
        if(request.url.host=="example.com") {
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else """{"data":{"123":{"video_model":{"fallback_api":"https://example.com/stream"}}}}"""
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceFailsBeforeStartingAudio() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        assertThrows(IOException::class.java) { repo.stream("123",720) }
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    /** 新版接口：video_list 直接是 video_model 上的 JSONArray，清晰度/编码在 video_meta，密钥在 encrypt_info。 */
    private fun newItem(url: String,definition: String,codec: String,withKey: Boolean=false) =
        JSONObject().put("main_url",url)
            .put("video_meta",JSONObject().put("definition",definition).put("codec_type",codec))
            .put("encrypt_info",JSONObject().put("encryption_method","cenc-aes-ctr")
                .put("spade_a",if(withKey) "lbwb8GG4KMRVvhvuU6kY4FSmKOh4oCnpUq4D1FOZAsh9gzWHhw==" else JSONObject.NULL))

    private fun arrayRepository(vararg items: JSONObject) = repository { """{"data":{"123":{"video_model":{"video_list":[${items.joinToString(",")}]}}}}""" }

    @Test fun arrayVideoListSelectsHighestCompatibleQualityAndDerivesKey() {
        val repo=arrayRepository(newItem("https://example.com/low","480p","bytevc1"),newItem("https://example.com/hevc","1080p","bytevc1",withKey=true))
        val result=repo.stream("123",1080)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P",result.quality)
        assertNotNull(result.key)
    }

    @Test fun arrayVideoListFiltersByteVc2HiddenInVideoMeta() {
        val repo=arrayRepository(newItem("https://example.com/unsupported","1080p","bytevc2"),newItem("https://example.com/avc","720p","bytevc1"))
        val result=repo.stream("123",1080)
        assertEquals("https://example.com/avc",result.url)
        assertEquals("720P",result.quality)
    }

    @Test fun arrayVideoListFallsBackToLowerGearBeyondQualityLimit() {
        val repo=arrayRepository(newItem("https://example.com/hevc","1080p","bytevc1"),newItem("https://example.com/low","480p","bytevc1"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/low",result.url)
        assertEquals("480P",result.quality)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsNotInventedAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.home(1,ContentType.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun shortRankingUsesRealDramaRankRouteAndParsesRanks() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",7).put("heatText","3210万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-real-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            script("mergeLoaderData",JSONArray()
                .put("rank_hot-real-drama/page").put(JSONArray().put(JSONObject().put("key","content")
                    .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))
        }
        val result=repo.shortRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(7,"3210万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertTrue(result.hasMore)
    }

    @Test fun rankingDispatchesByContentType() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            script("mergeLoaderData",JSONArray()
                .put(if(paths.size==1) "rank_hot-real-drama/page" else "rank_hot-comic-drama/page")
                .put(JSONArray().put(JSONObject().put("key","content")
                    .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(rankPayload().toString())))))
        }
        repo.ranking(1,ContentType.SHORT)
        repo.ranking(1,ContentType.COMIC)
        assertEquals(listOf("/rank/hot-real-drama","/rank/hot-comic-drama"),paths)
    }

    @Test fun comicGenreUsesComicCategoryRoute() {
        val repo=repository { request ->
            assertEquals("/category/comic-drama/fantasy",request.url.encodedPath)
            """<script>_ROUTER_DATA={"loaderData":{"category_page":{"recommendList":[{"series_id":"789","series_name":"分类漫剧"}],"pagination":{"totalPages":2}}}};</script>"""
        }
        val result=repo.genre("fantasy",1,ContentType.COMIC)
        assertEquals("789",result.items.single().id)
        assertTrue(result.hasMore)
    }

    @Test fun sameSlugGenreStaysOnRealDramaRouteForShort() {
        val repo=repository { request ->
            assertEquals("/category/real-drama/fantasy",request.url.encodedPath)
            """<script>_ROUTER_DATA={"loaderData":{"category_page":{"recommendList":[{"series_id":"456","series_name":"分类短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        assertEquals("456",repo.genre("fantasy",1,ContentType.SHORT).items.single().id)
        assertThrows(IllegalArgumentException::class.java) { repo.genre("romance",1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun shortSearchCarriesCursorAcrossPages() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            if(request.url.encodedPath=="/api/search/result") throw IOException("官网搜索接口不可用")
            requests+=request
            assertEquals("11",request.url.queryParameter("tab_type"))
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"999")
                .put("search_id","short-session").put("passback","short-cursor").put("next_offset",25))).toString()
        }
        repo.search("修仙",1,ContentType.SHORT)
        val second=repo.search("修仙",2,ContentType.SHORT)
        assertEquals("short-session",requests[1].url.queryParameter("search_id"))
        assertEquals("short-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        assertEquals(listOf("999"),second.items.map { it.id })
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) { request ->
            if(request.url.encodedPath=="/api/search/result") throw IOException("官网搜索接口不可用")
            requests++
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
        }
        repo.search("修仙",1,ContentType.SHORT)
        now=300001L
        repo.search("修仙",2,ContentType.SHORT)
        assertEquals(2,requests)
        repo.search("修仙",1,ContentType.SHORT)
        assertEquals(3,requests)
    }

    @Test fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo=repository { request ->
            assertTrue(request.url.encodedPath.endsWith("/category/real-drama"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    @Test fun detailParsesRelatedSeasonsFromMergeLoaderData() {
        val recommendations=JSONObject().put("recommendationGroups",JSONObject()
            .put("related_list",JSONArray()
                .put(JSONObject().put("series_id","777").put("series_name","禁止闪婚：甜桃烈酒2").put("episode_right_text","全51集"))
                .put(JSONObject().put("series_id","123").put("series_name","本剧自己").put("episode_right_text","全10集"))))
        val args=JSONArray().put("detail_page").put(JSONArray().put(
            JSONObject().put("key","recommendations").put("routerDataFnArgs",JSONArray().put(recommendations.toString()))))
        val html="""<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"123","series_name":"本剧","vid_list":["111","222"]}}}};</script>""" +
            script("mergeLoaderData",args)
        val repo=repository { html }
        val detail=repo.detail("123")
        assertEquals(listOf("111","222"),detail.episodes)
        assertEquals(listOf("777"),detail.related.map { it.id })
        assertEquals("禁止闪婚：甜桃烈酒2",detail.related.single().title)
        assertEquals("全51集",detail.related.single().badge)
    }

    @Test fun detailWithoutRelatedPayloadStillReturnsEpisodes() {
        val html="""<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"123","series_name":"本剧","vid_list":["111"]}}}};</script>"""
        val repo=repository { html }
        val detail=repo.detail("123")
        assertEquals(listOf("111"),detail.episodes)
        assertTrue(detail.related.isEmpty())
    }

    @Test fun detailParsesRelocatedVideoListRecommendations() {
        // 官网 2026-10 改版：推荐列表移到 mergeLoaderData 的 videoList，不再有 recommendationGroups.related_list
        val recommendations=JSONObject().put("videoList",JSONArray()
            .put(JSONObject().put("series_id","888").put("series_name","相似好剧").put("episode_right_text","全80集")))
        val args=JSONArray().put("detail_page").put(JSONArray().put(
            JSONObject().put("key","recommendations").put("routerDataFnArgs",JSONArray().put(recommendations.toString()))))
        val html="""<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"123","series_name":"本剧","vid_list":["111"]}}}};</script>""" +
            script("mergeLoaderData",args)
        val detail=repository { html }.detail("123")
        assertEquals(listOf("888"),detail.related.map { it.id })
        assertEquals("全80集",detail.related.single().badge)
    }

    @Test fun detailPrefersSortedSameFranchiseSeasons() {
        val html="""<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"13","series_name":"万妖图录传第十三季","vid_list":["9"]}}}};</script>"""
        val repo=repository { request ->
            if(request.url.encodedPath=="/reading/bookapi/search/tab/v") {
                JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"0")
                    .put("has_more",false)
                    .put("data",JSONArray()
                        .put(JSONObject().put("video_data",JSONArray().put(JSONObject().put("series_id","13").put("title","万妖图录传第十三季"))))
                        .put(JSONObject().put("video_data",JSONArray().put(JSONObject().put("series_id","3").put("title","万妖图录传第三季"))))
                        .put(JSONObject().put("video_data",JSONArray().put(JSONObject().put("series_id","0").put("title","万妖图录传"))))
                        .put(JSONObject().put("video_data",JSONArray().put(JSONObject().put("series_id","1").put("title","万妖图录传第一季"))))
                        .put(JSONObject().put("video_data",JSONArray().put(JSONObject().put("series_id","77").put("title","别的剧"))))))).toString()
            } else html
        }
        val detail=repo.detail("13")
        // 本剧自己被排除、无关剧被过滤，各季按季数排序（无季数的基础名最前）
        assertEquals(listOf("0","1","3"),detail.related.map { it.id })
    }

    @Test fun seasonHelpersParseChineseNumerals() {
        assertEquals("谁说纨绔不能当状元",ContentRepository.seasonBaseName("谁说纨绔不能当状元第五季"))
        assertNull(ContentRepository.seasonBaseName("万妖图录传"))
        assertEquals(5,ContentRepository.seasonNumber("谁说纨绔不能当状元第五季"))
        assertEquals(13,ContentRepository.seasonNumber("万妖图录传第十三季"))
        assertEquals(21,ContentRepository.seasonNumber("剧名第二十一季"))
        assertEquals(9,ContentRepository.seasonNumber("剧名 第9季"))
        assertNull(ContentRepository.seasonNumber("万妖图录传"))
        assertTrue(ContentRepository.sameFranchise("万妖图录传","万妖图录传第二季"))
        assertTrue(ContentRepository.sameFranchise("万妖图录传","万妖图录传"))
        assertFalse(ContentRepository.sameFranchise("万妖图录传","万妖图录传人"))
        assertFalse(ContentRepository.sameFranchise("万妖图录传","万妖图录传番外"))
    }

    @Test fun comicHomeContinuesIntoComicCategoryAfterFiveRankPages() {
        // 官网漫剧热播榜固定 5 页；第 6 页起直接续接 /category/comic-drama（页码-1 对齐）
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath+"?"+(request.url.queryParameter("page")?:"")
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"789","series_name":"分类漫剧"}],"pagination":{"totalPages":34,"pageNum":5}}}};</script>"""
        }
        val six=repo.home(6,ContentType.COMIC)
        assertEquals(listOf("/category/comic-drama?5"),paths)
        assertEquals("789",six.items.single().id)
        assertTrue(six.hasMore)
        assertTrue(repo.home(7,ContentType.COMIC).hasMore)
        assertEquals("/category/comic-drama?6",paths.last())
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    @Test fun unifiedSearchAlwaysUsesShortTab() {
        var requests=0
        val repo=repository { request ->
            if(request.url.encodedPath=="/api/search/result") throw IOException("官网搜索接口不可用")
            requests++
            assertEquals("11",request.url.queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.SHORT)
        assertEquals(listOf("111"),repo.search("修仙",2,ContentType.SHORT).items.map { it.id })
    }

    @Test fun webSearchUsesSignatureFreeSiteApiFirst() {
        val repo=repository { request ->
            when(request.url.encodedPath) {
                "/api/search/suggestion" -> """{"suggestionList":[]}"""
                else -> {
                    assertEquals("/api/search/result",request.url.encodedPath)
                    assertEquals("8662",request.url.queryParameter("aid"))
                    assertEquals("修仙",request.url.queryParameter("query"))
                    assertEquals("1",request.url.queryParameter("page"))
                    JSONObject().put("searchList",JSONArray().put(JSONObject()
                        .put("video_data",JSONObject().put("series_id","456").put("series_title","测试短剧").put("episode_cnt",8))))
                        .put("totalCount","111").put("totalPages",12).put("hasMore",true).toString()
                }
            }
        }
        val page=repo.search("修仙",1,ContentType.SHORT)
        assertEquals(listOf("456"),page.items.map { it.id })
        assertEquals("全 8 集",page.items.single().badge)
        assertTrue(page.hasMore)
    }

    @Test fun webSearchMergesTitleSuggestionsForNewSeasons() {
        // 官网搜索索引对短词召回不全：result 只召回第一二季，联想接口（剧库数据源）能带出刚上线的第三季
        val repo=repository { request ->
            when(request.url.encodedPath) {
                "/api/search/suggestion" -> """{"suggestionList":[
                    {"name":"反派拿捏！","word_type":"short_play_name","video_data":{"series_id":"100","series_title":"反派拿捏！","episode_cnt":155}},
                    {"name":"反派拿捏！第三季","word_type":"short_play_name","video_data":{"series_id":"300","series_title":"反派拿捏！第三季","episode_cnt":399}},
                    {"name":"反派拿捏！第四季","word_type":"common_query"},
                    {"name":"反派拿捏之孤灯遇暖第三季","word_type":"common_query"}]}"""
                else -> """{"searchList":[
                    {"video_data":{"series_id":"100","series_title":"反派拿捏！","episode_cnt":155}},
                    {"video_data":{"series_id":"200","series_title":"反派拿捏！第二季","episode_cnt":391}}],
                    "totalCount":"105","totalPages":11,"hasMore":true}"""
            }
        }
        val page=repo.search("反派拿捏",1,ContentType.SHORT)
        assertEquals(listOf("300","100","200"),page.items.map { it.id })
        assertTrue(page.hasMore)
    }

    @Test fun upstreamUnavailableFakeEmptyFallsBackToSignedApp() {
        // 上游偶发不可用时返回 HTTP 200 + code=upstream_unavailable 的假空，必须走签名搜索兜底而不是直接返回无结果
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) """{"code":"upstream_unavailable","message":"Search is temporarily unavailable."}"""
            else JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(11,"888"))).toString()
        }
        assertEquals(listOf("888"),repo.search("反派拿捏",1,ContentType.SHORT).items.map { it.id })
        assertEquals("/reading/bookapi/search/tab/v",paths[1])
    }

    @Test fun emptyWebSearchDoesNotFallBackToSignedApp() {
        var requests=0
        val repo=repository { request ->
            requests++
            when(request.url.encodedPath) {
                "/api/search/suggestion" -> """{"suggestionList":[]}"""
                else -> """{"searchList":[],"totalCount":"0","totalPages":1,"hasMore":false}"""
            }
        }
        assertTrue(repo.search("无结果",1,ContentType.SHORT).items.isEmpty())
        assertEquals(2,requests)
    }

    @Test fun missingShortTabFallsBackToWebsite() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) throw IOException("官网搜索接口不可用")
            if(paths.size==2) """{"code":0,"search_tabs":[{"tab_type":19,"data":[]}]}"""
            else """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}}}};</script>"""
        }
        assertEquals("456",repo.search("修仙",1,ContentType.SHORT).items.single().id)
        assertEquals("/api/search/result",paths[0])
        assertEquals("/reading/bookapi/search/tab/v",paths[1])
        assertTrue(paths[2].startsWith("/search/"))
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size<=2) throw IOException("offline")
            """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}}}};</script>"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(3,paths.size)
    }
}
