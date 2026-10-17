// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/** Read the website's serialized data only; never execute its scripts. */
internal object ComicRank {
    /** 官网热播榜路由 → 页面数据里的 rankKey（漫剧榜 comic、真人短剧榜 real） */
    val routes: Map<ContentType,String> = linkedMapOf(
        ContentType.COMIC to "rank_hot-comic-drama/page",
        ContentType.SHORT to "rank_hot-real-drama/page")

    fun parse(html: String, page: Int, type: ContentType = ContentType.COMIC): CatalogPage {
        val route=routes.getValue(type)
        try {
            val document=Jsoup.parse(html)
            val updatedText=runCatching { ContentRepository.extractRouter(html).optJSONObject("loaderData")
                ?.optJSONObject(route)?.str("updatedText") }.getOrNull().orEmpty()
            for(tag in document.select("script[data-fn-name][data-fn-args]")) {
                val name=tag.attr("data-fn-name")
                if(name!="r" && name!="mergeLoaderData") continue
                val args=JSONArray(tag.attr("data-fn-args"))
                if(args.optString(0)!=route) continue
                val raw=if(name=="r" && args.optString(1)=="content") args.opt(2) else if(name=="mergeLoaderData") {
                    args.optJSONArray(1)?.objects()?.firstOrNull { it.str("key")=="content" && it.str("routerDataFnName")=="p" }
                        ?.optJSONArray("routerDataFnArgs")?.opt(0)
                } else null
                val payload=when(raw) { is JSONObject -> raw; is String -> JSONObject(raw); else -> continue }
                if(!payload.optBoolean("isSuccess")) throw IOException("热播榜暂不可用")
                val rows=payload.optJSONArray("rankList") ?: throw IOException("热播榜格式已变化")
                val pagination=payload.optJSONObject("pagination") ?: throw IOException("热播榜分页信息缺失")
                val totalPages=pagination.optInt("totalPages",0)
                if(totalPages<1 || pagination.optInt("pageNum",page)!=page) throw IOException("热播榜分页信息异常")
                val positions=linkedMapOf<String,RankPosition>()
                val items=rows.objects().map { row ->
                    val count=row.optInt("episodeCount",row.optJSONArray("episodeVids")?.length() ?: 0)
                    val tags=row.optJSONArray("tags") ?: JSONArray()
                    val id=row.str("seriesId").ifEmpty { row.str("id") }
                    if(id !in positions) positions[id]=RankPosition(row.optInt("rank").takeIf { it>0 },row.str("heatText"))
                    Series(id,row.str("title"),cleanUrl(row.str("cover")),
                        row.str("description"),if(count>0) "全 $count 集" else row.str("heatText"),
                        (0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optString(it) })
                }.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
                return CatalogPage(items,items.isNotEmpty() && page<minOf(totalPages,100),
                    ComicRanking(positions.filterKeys { id -> items.any { it.id==id } },minOf(totalPages,100),updatedText))
            }
            return renderedPage(document,html,page,type)
        } catch(e: IOException) { throw e } catch(e: Exception) { throw IOException("热播榜格式已变化",e) }
    }

    private fun renderedPage(document: Document, html: String, page: Int, type: ContentType): CatalogPage {
        // Some responses contain rendered cards while the deferred JSON is still unresolved.
        val route=routes.getValue(type)
        val rankKey=route.removePrefix("rank_hot-").removeSuffix("-drama/page")
        val routeData=ContentRepository.extractRouter(html).optJSONObject("loaderData")
            ?.optJSONObject(route) ?: throw IOException("页面没有热播榜数据")
        if(routeData.str("rankKey")!=rankKey || routeData.optInt("pageNum")!=page) throw IOException("热播榜分页信息异常")
        val positions=linkedMapOf<String,RankPosition>()
        val items=document.select("article[aria-labelledby^=rank-title-]").mapNotNull { article ->
            val heading=article.attr("aria-labelledby")
            val id=heading.removePrefix("rank-title-")
            val title=article.getElementById(heading)?.text().orEmpty()
            if(!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
            if(id !in positions) positions[id]=RankPosition(
                article.selectFirst("[class*=badge-number-]")?.text()?.toIntOrNull()?.takeIf { it>0 },
                article.selectFirst("p[class*=metrics-]")?.text().orEmpty())
            Series(id,title,article.selectFirst("img[src]")?.attr("src").orEmpty(),
                article.selectFirst("p[class*=description-]")?.text().orEmpty(),
                article.selectFirst("p[class*=metrics-]")?.text().orEmpty(),
                article.select("p[class*=categories-] > span").take(5).joinToString(" · ") { it.text() })
        }.distinctBy { it.id }
        if(items.isEmpty()) throw IOException("页面没有热播榜数据")
        val paging=document.selectFirst("nav[aria-label=榜单分页]") ?: throw IOException("热播榜分页信息缺失")
        if(paging.selectFirst("[aria-current=page]")?.text()?.toIntOrNull()!=page) throw IOException("热播榜分页信息异常")
        val lastPage=maxOf(page,paging.select("a[href]").mapNotNull { it.attr("href").substringAfter("?page=","").substringBefore('&').toIntOrNull() }.maxOrNull() ?: page)
        return CatalogPage(items,page<minOf(lastPage,100),ComicRanking(positions,minOf(lastPage,100),routeData.str("updatedText")))
    }
}
