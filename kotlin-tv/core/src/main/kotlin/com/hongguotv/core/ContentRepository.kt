// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>, val related: List<Series> = emptyList())
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)

/** 一条弹幕：offsetMillis 为弹幕在视频中的位置，digg 为点赞数 */
data class Danmaku(val offsetMillis: Long, val text: String, val digg: Int)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    private fun execute(request: Request): String {
        return RequestScope.execute(http.newCall(request)) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return execute(request.build())
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"),page)
    }

    /** 官网真人短剧热播榜（/rank/hot-real-drama），结构与漫剧榜一致，每页 20 部带名次 */
    fun shortRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-real-drama?page=$page"),page,ContentType.SHORT)
    }

    fun ranking(page: Int = 1, type: ContentType = ContentType.COMIC): CatalogPage = when(type) {
        ContentType.COMIC -> comicRanking(page)
        ContentType.SHORT -> shortRanking(page)
    }

    /** 官网题材分类（/category/real-drama/{slug}），实测每页 24 条、深翻页可用 */
    object Genres {
        val items: Map<String,String> = linkedMapOf(
            "romance" to "爱情","comeback" to "逆袭","suspense" to "悬疑","costume" to "古装","urban" to "都市",
            "fantasy" to "玄幻","comedy" to "喜剧","family" to "家庭","period" to "年代","youth" to "青春",
            "legend" to "传奇","growth" to "成长","clan" to "家族","cute-kids" to "萌宝","sci-fi" to "科幻",
            "supernatural" to "志怪","wonder" to "奇幻","thriller" to "惊悚","horror" to "恐怖","disaster" to "灾难",
            "action-adventure" to "动作冒险","war" to "战争","variety" to "综艺","drama" to "剧情")
    }

    /** 官网漫剧题材分类（/category/comic-drama/{slug}），结构与短剧分类一致 */
    object ComicGenres {
        val items: Map<String,String> = linkedMapOf(
            "fantasy" to "玄幻","wonder" to "奇幻","adventure" to "冒险","apocalypse" to "末世",
            "sci-fi" to "科幻","wealthy-family" to "豪门","creative" to "脑洞","drama" to "剧情")
    }

    fun genres(type: ContentType): Map<String,String> = when(type) {
        ContentType.COMIC -> ComicGenres.items
        ContentType.SHORT -> Genres.items
    }

    fun genre(slug: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(slug in genres(type) && page in 1..100)
        val prefix=if(type==ContentType.COMIC) "comic-drama" else "real-drama"
        return parseCategory("$site/category/$prefix/$slug",page)
    }
    private fun parseCategory(url: String, page: Int): CatalogPage {
        val data=router(url+if(page>1) "?page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("分类数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: JSONArray()
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.COMIC) return comicHome(page)
        // 直连新版分类路由，避免旧 tab 参数多一跳 301 重定向
        return parseCategory("$site/category/real-drama",page)
    }

    /**
     * 漫剧片库分页：第 1—5 页用官网热播榜（每页 20 部，带榜单名次）。
     * 官网榜单固定 5 页（100 部，page>5 时官网直接不返回榜单数据），
     * 之后的页码无缝续接漫剧分类 /category/comic-drama（每页 24 部、
     * 共 34 页、可深翻页）。第 6 页起按“页码-1”对齐，与榜单末页仅重叠约 4 部。
     */
    private fun comicHome(page: Int): CatalogPage {
        if(page<=5) return comicRanking(page)
        val category=parseCategory("$site/category/comic-drama",page-1)
        return CatalogPage(category.items,category.hasMore)
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        // 首选：官网新版免签名搜索 JSON 接口（2026-10 搜索页改 CSR 后前端实际使用的数据源）。
        // 空结果视为真实无结果，直接返回；接口异常再走签名 App 搜索与旧版 SSR 兜底。
        try { return webSearch(keyword,page) } catch (_: Exception) { /* 兜底继续 */ }
        // 漫剧 tab 的搜索接口不可用；短剧 tab 的搜索会同时返回短剧与漫剧内容，统一走短剧搜索。
        try { val result=appSearch(keyword,page,ContentType.SHORT); if(result.items.isNotEmpty() || !result.hasMore) return result } catch (e: Exception) { if(e is SearchSessionExpiredException) throw e; /* Website fallback. */ }
        val data=router("$site/search/${Signer.encode(keyword)}?page=$page")
        // 自适应：官网改版后 loaderData 下的键名可能变化，直接找含 searchList 的 section
        var section: JSONObject?=null
        for(key in data.keys()) { val v=data.optJSONObject(key); if(v!=null && v.has("searchList")) { section=v; break } }
        val rows=section?.optJSONArray("searchList") ?: throw IOException("搜索数据暂不可用")
        val items=valid(rows.objects().map(::card))
        return CatalogPage(items,items.isNotEmpty() && page<100)
    }

    /** 官网新版搜索接口（/api/search/result，免签名），返回结构同旧 SSR searchList；
     *  上游偶发不可用时返回 HTTP 200 + code=upstream_unavailable 的假空结果，必须抛错走兜底 */
    private fun webSearch(keyword: String, page: Int): CatalogPage {
        val query="aid=8662&query="+Signer.encode(keyword)+"&page=$page"
        val data=JSONObject(text("$site/api/search/result?$query"))
        if(data.optString("code")=="upstream_unavailable") throw IOException("搜索接口暂不可用")
        val rows=data.optJSONArray("searchList") ?: JSONArray()
        val items=valid(rows.objects().map(::card))
        // 首页结果合并剧名联想（联想=剧库精确匹配，新剧即时收录；result 的索引对短词召回不全，
        // 实测搜"反派拿捏"仅召回第一二季，联想接口能带出刚上线的第三季）
        val merged=if(page==1) mergeSuggestions(keyword,items) else items
        val totalPages=data.optInt("totalPages",1)
        return CatalogPage(merged,merged.isNotEmpty() && data.optBoolean("hasMore",false) && page<minOf(totalPages,100))
    }

    /** 剧名联想接口（/api/search/suggestion），仅取 word_type=short_play_name 且带剧集数据的条目，按 id 去重后前置 */
    private fun mergeSuggestions(keyword: String, items: List<Series>): List<Series> {
        return try {
            val data=JSONObject(text("$site/api/search/suggestion?aid=8662&query="+Signer.encode(keyword)))
            val rows=data.optJSONArray("suggestionList") ?: JSONArray()
            val seen=items.map{it.id}.toSet()
            val extra=valid(rows.objects().filter{ it.optString("word_type")=="short_play_name" }.map(::card))
                .filter{ it.id !in seen }
            extra+items
        } catch (_: Exception) { items }
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=VendorConstants.device.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(type==ContentType.COMIC && page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values)
        val data=JSONObject(text(signed.url,signed))
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val html=text("$site/detail?series_id=$id")
        val loader=extractRouter(html).optJSONObject("loaderData")?.optJSONObject("detail_page") ?: throw IOException("剧集详情暂不可用")
        val o=loader.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        val series=card(o).copy(id=id)
        // 相关推荐：详情页 mergeLoaderData 脚本里的站点推荐做容错解析，取不到就只留同系列各季
        val siteRelated=runCatching { extractRelated(html) }.getOrDefault(emptyList())
        // 观看的若是“第 N 季”，用基础名搜索同系列各季（如第五季 → 1—4 季；万妖图录传等长系列）排最前
        val seasons=runCatching { seasonRelated(series.title,id) }.getOrDefault(emptyList())
        val related=(seasons+siteRelated).filterNot { it.id==id }.distinctBy { it.id }.take(16)
        return Detail(series,episodes,related)
    }

    /** 从详情页的 mergeLoaderData 脚本里取站点推荐列表（新版 videoList，旧版 recommendationGroups） */
    private fun extractRelated(html: String): List<Series> {
        var from=0
        while(true) {
            val marker=html.indexOf("mergeLoaderData",from); if(marker<0) return emptyList()
            val attr=html.indexOf("data-fn-args=\"",marker); if(attr<0) return emptyList()
            val start=attr+"data-fn-args=\"".length
            val end=html.indexOf('"',start); if(end<0) return emptyList()
            from=end
            val args=JSONArray(decodeEntities(html.substring(start,end)))
            // 形如 ["detail_page", [{key:"recommendations", routerDataFnArgs:["{json}"]}]]
            val entries=(0 until args.length()).flatMap { i ->
                when(val v=args.opt(i)) {
                    is JSONObject -> listOf(v)
                    is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
                    else -> emptyList()
                }
            }
            val inner=entries.firstOrNull { it.str("key")=="recommendations" }
                ?.optJSONArray("routerDataFnArgs") ?: continue
            val payload=inner.optString(0,""); if(payload.isEmpty()) continue
            // 2026-10 改版后推荐直接放在 videoList；旧版 related_list 与 hot_list 依次兜底
            val json=JSONObject(payload)
            val groups=json.optJSONObject("recommendationGroups")
            val rows=json.optJSONArray("videoList")
                ?: groups?.optJSONArray("related_list")
                ?: groups?.optJSONArray("hot_list")
                ?: continue
            return valid(rows.objects().map(::card)).take(16)
        }
    }

    /** 同剧其他季：当前标题带季数时，用基础名搜索，把同系列各季按季数排好 */
    private fun seasonRelated(title: String,currentId: String): List<Series> {
        val base=seasonBaseName(title) ?: return emptyList()
        return search(base,1,ContentType.SHORT).items
            .filter { it.id!=currentId && sameFranchise(base,it.title) }
            .sortedBy { seasonNumber(it.title) ?: 0 }
            .take(10)
    }

    private fun decodeEntities(s: String)=s.replace("&quot;","\"").replace("&#x27;","'").replace("&#39;","'")
        .replace("&lt;","<").replace("&gt;",">").replace("&amp;","&")

    /**
     * 拉取 video_model。
     *
     * 上游拒绝带签名的请求（返回 Code 110001），因此先按可工作的 Python 实现的方式尝试：
     * 无签名 + 每次随机 device_id/iid + 多域名轮询；全部失败再回退原带签名请求。
     * 随机设备号保持 18 位（10^17 ~ 10^18），避免被上游当作异常设备。
     */
    private fun randomDevice(): Map<String,String> {
        val id=(900000000000000000L..999999999999999999L).random().toString()
        return VendorConstants.device + mapOf("device_id" to id, "iid" to id)
    }
    private val videoApiCandidates = listOf(
        "https://api5-normal-sinfonlinec.fqnovel.com/novel/player/multi_video_model/v1/",
        "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/",
        "https://api5-normal-lf.fqnovel.com/novel/player/multi_video_model/v1/",
        "https://api5-normal.fqnovel.com/novel/player/multi_video_model/v1/",
    )
    private fun videoModelOk(body: String): Boolean = runCatching {
        val o=JSONObject(body)
        o.optInt("Code",o.optInt("code",0))==0 && o.optJSONObject("data") != null
    }.getOrDefault(false)

    private fun unsignedVideoModel(body: ByteArray): JSONObject? {
        for (base in videoApiCandidates) {
            val url="$base?"+Signer.encode(randomDevice())
            val headers=linkedMapOf(
                "User-Agent" to VendorConstants.VIDEO_MODEL_UA,
                "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                "Content-Type" to "application/json; charset=UTF-8",
                "x-xs-from-web" to "0",
                "x-ss-req-ticket" to clock().toString(),
                "x-tt-request-tag" to "t=0;n=0",
                "sdk-version" to "2",
                "passport-sdk-version" to "50561",
                "x-vc-bdturing-sdk-version" to "3.7.2.cn",
            )
            try {
                val request=Request.Builder().url(url)
                headers.forEach { (k,v) -> request.header(k,v) }
                val raw=execute(request.post(body.toRequestBody("application/json; charset=UTF-8".toMediaType())).build())
                if (videoModelOk(raw)) return JSONObject(raw)
            } catch (_: IOException) {
                // 网络异常或上游非 2xx，换下一个域名继续
            }
        }
        return null
    }

    private fun videoModel(payload: ByteArray): JSONObject {
        unsignedVideoModel(payload)?.let { return it }
        // 回退：原带签名请求（部分网络环境下可能仍被接受）
        val signed=signer.sign(videoApiCandidates[1],VendorConstants.device,payload)
        val raw=text(signed.url,signed)
        if (videoModelOk(raw)) return JSONObject(raw)
        throw IOException("播放接口暂不可用")
    }

    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val payload="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
        val data=videoModel(payload)
        if(data.optInt("Code",data.optInt("code",0))!=0) throw IOException("播放接口暂不可用")
        val map=data.optJSONObject("data") ?: throw IOException("播放信息为空")
        val entry=map.optJSONObject(id) ?: map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull() ?: throw IOException("播放信息为空")
        val model=entry.optJSONObject("video_model") ?: JSONObject(entry.str("video_model"))
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() && it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }),k,o) }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality)
    }

    /**
     * 拉取一集的弹幕（官方客户端把弹幕复用评论列表接口：
     * POST /novel/commentapi/comment/list/{vid}/v1/，group_type=30、comment_source=601、
     * comment_type=20、server_channel=1000、count=90）。评论接口必须携带设备签名
     * （无签名返回 110001），且与播放接口同样拒绝 branch=1 的签名变体，
     * Signer.sign 内部已做 ticket 抖动。按 offset_time（毫秒）升序返回。
     */
    fun danmaku(seriesId: String, episodeId: String, maxItems: Int = 900, maxPages: Int = 12): List<Danmaku> {
        require(seriesId.matches(Regex("[0-9]{1,30}")) && episodeId.matches(Regex("[0-9]{1,30}")))
        val base="https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/$episodeId/v1/"
        val out=ArrayList<Danmaku>()
        var cursor=""
        var page=0
        while(page<maxPages) {
            page++
            if(out.size>=maxItems) return out.sortedBy { it.offsetMillis }
            val body=JSONObject()
                .put("business_param",JSONObject().put("book_id",seriesId).put("playlet_item_duration",0).put("start_offset_time",0))
                .put("comment_source",601).put("comment_type",20).put("count",90)
                .put("group_id",episodeId).put("group_type",30).put("server_channel",1000).put("sort",1)
            if(cursor.isNotEmpty()) body.put("cursor",cursor)
            val signed=signer.sign(base,VendorConstants.device,body.toString().toByteArray())
            val raw=text(signed.url,signed)
            val o=JSONObject(raw)
            if(o.optInt("Code",o.optInt("code",0))!=0) throw IOException("弹幕接口暂不可用")
            val data=o.optJSONObject("data") ?: break
            data.optJSONArray("data_list")?.objects()?.forEach { entry ->
                val comment=entry.optJSONObject("comment") ?: return@forEach
                val label=comment.optJSONObject("common")?.optJSONObject("content")?.str("text") ?: return@forEach
                if(label.isEmpty()) return@forEach
                val expand=comment.optJSONObject("expand") ?: return@forEach
                val offset=if(expand.isNull("offset_time")) -1L else expand.optLong("offset_time",-1L)
                if(offset<0) return@forEach
                val digg=comment.optJSONObject("stat")?.optInt("digg_count",0) ?: 0
                out+=Danmaku(offset,label,digg)
            }
            val info=data.optJSONObject("common_list_info") ?: break
            cursor=info.str("cursor")
            if(!info.optBoolean("has_more",false) || cursor.isEmpty()) break
        }
        return out.sortedBy { it.offsetMillis }
    }

    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0

        /** 去掉标题里的“第 N 季/部”得到基础名：“谁说纨绔不能当状元第五季”→“谁说纨绔不能当状元” */
        internal fun seasonBaseName(title: String): String? {
            val match=Regex("""^(.*?)\s*第\s*[0-9０-９一二两三四五六七八九十]+\s*[季部]\s*(.*)$""").find(title.trim()) ?: return null
            return (match.groupValues[1]+" "+match.groupValues[2]).trim().takeIf { it.isNotEmpty() }
        }

        /** 标题里的季数：“第一季”=1、“第13季”=13、“第十三季”=13；没有季数返回 null */
        internal fun seasonNumber(title: String): Int? {
            val match=Regex("""第\s*([0-9０-９一二两三四五六七八九十]+)\s*[季部]""").find(title) ?: return null
            val digits=mapOf("一" to 1,"二" to 2,"两" to 2,"三" to 3,"四" to 4,"五" to 5,"六" to 6,"七" to 7,"八" to 8,"九" to 9)
            val full=match.groupValues[1]
            val raw=full.map { c -> if(c in '０'..'９') ('0'+(c-'０')) else c }.joinToString("")
            raw.toIntOrNull()?.let { return it }
            if(raw=="十") return 10
            val at=raw.indexOf('十')
            if(at<0) return digits[raw]
            val tens=if(at==0) 1 else digits[raw.substring(0,at)] ?: return null
            val ones=if(at+1==raw.length) 0 else digits[raw.substring(at+1)] ?: return null
            return tens*10+ones
        }

        /** 同系列判定：完整等于基础名，或基础名后紧跟“第 N 季/部” */
        internal fun sameFranchise(base: String,title: String): Boolean {
            if(!title.startsWith(base)) return false
            val rest=title.removePrefix(base).trim()
            return rest.isEmpty() || Regex("""^第\s*[0-9０-９一二两三四五六七八九十]+\s*[季部]""").containsMatchIn(rest)
        }
    }
}
