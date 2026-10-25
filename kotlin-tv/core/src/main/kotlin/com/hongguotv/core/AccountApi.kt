// SPDX-License-Identifier: GPL-3.0-only
// 红果账号登录与云端播放记录同步（与手机官方 App 双端互通）。
// 接口规格逆向自 hgplayer 1.1.3/1.1.5/1.1.8 抓包（hongguo-desktop 同源）。
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** passport 业务拒绝（error_code 在 data 里）。22=非法应用：设备档案被服务端清理，
 *  唯一出路是轮换设备档案（重新注册）后重试，重试登录本身无意义。 */
class PassportReject(val code: Long,message: String): IOException(message)

/**
 * - 登录：passport 短信验证码（novel.snssdk.com，全签名 + form 表单；
 *   手机号/验证码 XOR(0x05) hex 密文，mix_mode=1，type=3731 登录场景）。
 *   风控触发 MFA 时走 upsms 上行短信轮询，再补一次带 retry_tag 的登录。
 * - 拉取/回传：reading 系 lq 域（轻签名头 x-ss-dp/lc/x-reading-request）
 *   + 会话 cookie + x-tt-token 短形式（前 56 位）；GET 带设备档案 query，
 *   POST body 一律 gzip（签名覆盖 gzip 后字节）。
 * - 会话 cookie 全家桶 + x-tt-token 由调用方持久化（60 天有效期），本类只存内存态。
 * - 服务端在签名/登录态失效时返回 HTTP 200 + 0 字节，必须检查响应体长度。
 */
class AccountApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).dns(Ipv4FirstDns).build(),
    private val clock: ()->Long = System::currentTimeMillis,
    private val random: ()->Double = Math::random,
) {
    /** 登录会话：cookie 串（k=v; k=v）+ x-tt-token 长凭据 + 用户资料 */
    data class Session(val cookies: String,val token: String,val userId: String,val name: String,val avatar: String)
    data class PassportUser(val userId: String,val name: String,val avatar: String)
    data class SendCode(val message: String,val csrf: String,val retrySeconds: Int)
    /** MFA 上行短信验证上下文（风控触发时下发，channel 已替换为可回复的真实通道号） */
    data class MfaContext(val retryTag: String,val smsCodeKey: String,val mfaToken: String,val encryptUid: String,val logId: String,val verifyReason: String,val verifyScene: String,val copywritingKey: String,val diversionTag: String,val channel: String,val smsContent: String,val tips: String)
    sealed class LoginResult {
        data class Success(val session: Session): LoginResult()
        data class Mfa(val context: MfaContext): LoginResult()
    }
    sealed class UpsmsState {
        object Waiting: UpsmsState()
        data class Registered(val mfaToken: String): UpsmsState()
    }
    /** 云端一条播放记录；episodeIndex 与线上 vid_index 同为 0 基（第 1 集=0），与本应用一致 */
    data class HistoryItem(val seriesId: String,val title: String,val cover: String,val episodeIndex: Int,val episodeId: String,val positionMs: Long,val durationMs: Long,val episodeCount: Int,val updatedAtMs: Long)
    data class HistoryPage(val items: List<HistoryItem>,val hasMore: Boolean,val nextOffset: Long,val total: Long)

    /** 当前会话；由应用层在启动时回填、登录/退出时更新（多线程读写） */
    @Volatile var session: Session? = null

    private val signer=Signer()

    // ── 对外操作 ────────────────────────────────────────────────

    /** 发送短信验证码；返回文案与 passport_csrf_token（登录时要带回） */
    fun sendCode(mobile: String): SendCode {
        val form=passportDeviceForm()+listOf(
            "mobile" to encodeMobile(mobile),"mix_mode" to "1","account_sdk_source" to "app",
            "passport_support_flow" to "captcha,verify","type" to "3731","auto_read" to "0","unbind_exist" to "34")
        val raw=passportCall("/passport/mobile/send_code/v1/",form)
        val o=JSONObject(raw.body)
        requireOk(o,"发送验证码失败")
        val data=o.optJSONObject("data") ?: JSONObject()
        val csrf=raw.setCookies.map { it.substringBefore(';').trim() }.firstOrNull { it.startsWith("passport_csrf_token=") }?.substringAfter('=').orEmpty()
        return SendCode(data.str("description").ifEmpty { o.str("message") }.ifEmpty { "验证码已发送" },csrf,if(data.has("retry_time")) data.optInt("retry_time",60) else 60)
    }

    /**
     * 短信验证码登录。[csrf] 传发码响应的 passport_csrf_token；MFA 重登时
     * 传 [mfaContext] 与 upsms registered 下发的新 [mfaToken]（cookie 绑定用）。
     */
    fun smsLogin(mobile: String,code: String,csrf: String="",mfaContext: MfaContext?=null,mfaToken: String=""): LoginResult {
        var form=passportDeviceForm()+listOf(
            "mobile" to encodeMobile(mobile),"mix_mode" to "1","code" to encodeCode(code),
            "account_sdk_source" to "app","passport_support_flow" to "captcha,verify")
        if(mfaContext!=null) form+=listOf("passport_mfa_retry_tag" to mfaContext.retryTag,"sms_code_key" to encodeCode(mfaContext.smsCodeKey))
        var cookie=emptyList<String>()
        if(csrf.isNotEmpty()) cookie=cookie+listOf("passport_csrf_token=$csrf","passport_csrf_token_default=$csrf")
        val token=mfaToken.ifEmpty { mfaContext?.mfaToken.orEmpty() }
        if(token.isNotEmpty()) cookie=cookie.filterNot { it.startsWith("passport_mfa_token=") }+"passport_mfa_token=$token"
        val raw=passportCall("/passport/mobile/sms_login/",form,cookie=cookie.joinToString("; "))
        return parseLoginBody(raw.body,raw.setCookies,raw.token)
    }

    /** 当前登录用户信息（也用于登录后补全昵称/头像） */
    fun userInfo(): PassportUser {
        val o=JSONObject(readingCall("/reading/user/info/v1/"))
        requireCodeOk(o,"读取用户信息失败")
        val data=o.optJSONObject("data") ?: JSONObject()
        val inner=data.optJSONObject("user_info") ?: data
        return PassportUser(
            fieldOf(inner,"user_id","userId","uid").ifEmpty { fieldOf(data,"user_id","userId","uid").ifEmpty { numericReqId(data) } },
            fieldOf(inner,"name","user_name","username","nick_name"),
            fieldOf(inner,"avatar_url","avatar"))
    }

    /** 拉取一页云端观看历史（官方 App「历史」的数据源，book_type=2 过滤短剧） */
    fun watchHistory(offset: Long=0): HistoryPage {
        val o=JSONObject(readingCall("/reading/bookapi/read_history/list/v",linkedMapOf(
            "book_type" to "2","full_field" to "false","is_first_load" to "true",
            "last_min_read_timestamp_ms" to "0","limit" to "0","offset" to offset.toString(),
            "query_soft_deleted" to "false")))
        requireCodeOk(o,"拉取云端观看记录失败")
        return parseHistoryPage(o.optJSONObject("data") ?: throw IOException("云端观看记录响应缺少数据"))
    }

    /**
     * 观看进度回传（官方客户端播片时同时打的双接口，约每分钟一次 + 切集触发）。
     * [episodeIndex] 传本应用 0 基集序（线上协议同为 0 基）。
     */
    fun reportProgress(seriesId: String,episodeId: String,episodeIndex: Int,positionMs: Long) {
        val session=session ?: throw IOException("未登录，不能上报观看进度")
        if(session.cookies.isEmpty()) throw IOException("登录状态已失效，请重新登录")
        val now=clock(); val pos=positionMs.coerceAtLeast(0); val wire=episodeIndex.coerceAtLeast(0)
        val update=JSONObject().put("update_datas",JSONArray().put(JSONObject()
            .put("book_id",seriesId.toLongOrNull() ?: seriesId).put("book_type",2).put("chapter_index",0)
            .put("current_play_position",pos).put("digged_count",0).put("duration",0).put("episode_cnt",0)
            .put("is_delete",false).put("is_interactive_game",false).put("is_listen_mode",false)
            .put("is_multi_season",0).put("meet_guide_comment_tag",false).put("origin_novel_book_id",0)
            .put("player_accumulate_total_time",pos).put("read_timestamp_ms",now).put("recent_reads",0)
            .put("retain_video_play_time",0).put("season_index",0).put("series_play_cnt",0).put("tone_id",0)
            .put("update_timestamp_ms",now).put("use_soft_delete",false).put("user_digg",false)
            .put("user_playlet_comment_flag",false).put("vid",episodeId.toLongOrNull() ?: episodeId).put("vid_index",wire)))
        val first=JSONObject(readingCall("/reading/bookapi/read_history/update/v",body=update))
        requireCodeOk(first,"观看进度上报失败")
        val fails=first.optJSONObject("data")?.optJSONArray("update_fail_datas")
        if(fails!=null && fails.length()>0) throw IOException("观看进度被服务端拒绝（${fails.length()} 条）")
        val upload=JSONObject().put("books",JSONArray().put(JSONObject()
            .put("book_id",seriesId).put("book_type",2).put("channel_id",0).put("check_timestamp",false)
            .put("cur_channel_id",0).put("current_play_time",pos).put("is_listen_mode",false)
            .put("is_local_book",false).put("item_id",episodeId).put("listen_and_read",false)
            .put("page_index",0).put("page_progress_rate",0).put("paragraph_offset",0)
            .put("player_cumulative_total_duration",pos).put("progress_type",0)
            .put("read_timestamp_ms",now).put("tone_id",0).put("vid_index",wire)))
        requireCodeOk(JSONObject(readingCall("/reading/bookapi/read_progress/upload/v",body=upload)),"观看进度上传失败")
    }

    /** MFA 上行短信验证轮询（单次调用；UI 每 3 秒重发）。
     *  [csrf] 传发码响应的 passport_csrf_token——参考实现 with_csrf 注释实证：
     *  「sms_login / 轮询都必须带上，否则服务端不认这次会话」。
     *  mfa_token 缺了服务端永远回 1045；csrf 缺了直接报会话错误。
     *  用户按短信提示回复后 registered=true 表示通过，新 token 随 Set-Cookie 下发。 */
    fun upsmsVerify(context: MfaContext,csrf: String=""): UpsmsState {
        val biz=JSONObject().put("passport_mfa_retry_tag",context.retryTag).put("sms_code_key",context.smsCodeKey)
        val form=listOf(
            "biz_params" to biz.toString(),"copywriting_key" to context.copywritingKey,
            "encrypt_uid" to context.encryptUid,"ies_safety_diversion_tag" to context.diversionTag,
            "new_authn_sdk_log_id" to context.logId,"new_authn_sdk_verify_reason" to context.verifyReason,
            "new_authn_sdk_verify_scene" to context.verifyScene,"new_authn_sdk_version" to "1.1.31",
            "request_tag_from" to "h5","verify_reason" to context.verifyReason,"verify_scene" to context.verifyScene,
            "new_verify_flow" to "","verify_ticket" to "")
        val cookie=buildList {
            if(csrf.isNotEmpty()) { add("passport_csrf_token=$csrf"); add("passport_csrf_token_default=$csrf") }
            if(context.mfaToken.isNotEmpty()) add("passport_mfa_token=${context.mfaToken}")
        }.joinToString("; ")
        val raw=passportCall("/passport/upsms/verify/",form,sdkRom=false,cookie=cookie)
        return interpretUpsms(JSONObject(raw.body),raw.setCookies,context.mfaToken)
    }

    // ── 纯解析（可单测）─────────────────────────────────────────

    internal companion object {
        internal const val PASSPORT_ORIGIN="https://novel.snssdk.com"
        internal const val LQ_ORIGIN="https://api5-normal-lq.fqnovel.com"
        private const val FORM_TYPE="application/x-www-form-urlencoded; charset=UTF-8"
        /** VendorConstants.device 缺 cdid（passport 设备档案需要）：固定的 16 位客户端设备号，风控参考字段 */
        private const val CDID="4396155240216857"
        /** API 下发的 95 通道号服务端收不到回复，替换为真实 106 网关通道（hgplayer 同款修正） */
        private const val REAL_UPSMS_CHANNEL="10691859839103"
        private const val STUB_UPSMS_CHANNEL="9515211003"

        internal fun xor05Hex(value: String)=value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format((it.toInt() and 255) xor 5) }
        /** 手机号：+86 国码前缀后逐字节 XOR 0x05 转小写 hex */
        internal fun encodeMobile(mobile: String)=xor05Hex("+86${mobile.trim()}")
        internal fun encodeCode(code: String)=xor05Hex(code.trim())
        internal fun realUpsmsChannel(channel: String)=if(channel==STUB_UPSMS_CHANNEL) REAL_UPSMS_CHANNEL else channel

        /** Set-Cookie 原始行洗成 k=v; k=v：剥属性、后值覆盖、保持首现位置 */
        internal fun extractCookiePairs(lines: List<String>): String {
            val pairs=linkedMapOf<String,String>()
            for(line in lines) {
                val kv=line.substringBefore(';').trim()
                val at=kv.indexOf('=')
                if(at<=0) continue
                val key=kv.substring(0,at).trim()
                if(key.isNotEmpty()) pairs[key]=kv.substring(at+1)
            }
            return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        /** 匿名底座与显式/会话 cookie 合并：后值覆盖、保持首现位置（与 extractCookiePairs 同口径） */
        internal fun mergeCookies(base: String,extra: String): String {
            val pairs=linkedMapOf<String,String>()
            for(raw in listOf(base,extra)) for(line in raw.split("; ")) {
                val at=line.indexOf('=')
                if(at<=0) continue
                val key=line.substring(0,at).trim()
                if(key.isNotEmpty()) pairs[key]=line.substring(at+1)
            }
            return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        internal fun formUrlencoded(pairs: List<Pair<String,String>>) = pairs.joinToString("&") { (k,v) -> Signer.encode(k)+"="+Signer.encode(v) }

        internal fun parseLoginBody(body: String,setCookies: List<String>,tokenHeader: String): LoginResult {
            val o=JSONObject(body)
            val data=o.optJSONObject("data")
            // MFA 特征：passport_mfa_retry_tag 与 sms_code_key 成对出现（data.biz_params 嵌套或顶层）
            val biz=data?.optJSONObject("biz_params") ?: o.optJSONObject("biz_params")
            val retryTag=biz?.str("passport_mfa_retry_tag").orEmpty()
            val smsKey=biz?.str("sms_code_key").orEmpty()
            if(retryTag.isNotEmpty() && smsKey.isNotEmpty()) {
                var channel=""; var smsContent=""
                for(way in (data?.optJSONArray("verify_ways") ?: o.optJSONArray("verify_ways")).objects()) {
                    if(way.str("verify_way")=="mobile_up_sms_verify") { channel=realUpsmsChannel(way.str("channel_mobile")); smsContent=way.str("sms_content"); break }
                }
                val events=data?.optJSONObject("event_params")
                val common=data?.optJSONObject("common_params")
                val mfaToken=extractCookiePairs(setCookies).split("; ").firstOrNull { it.startsWith("passport_mfa_token=") }?.substringAfter('=').orEmpty()
                return LoginResult.Mfa(MfaContext(
                    retryTag=retryTag,smsCodeKey=smsKey,mfaToken=mfaToken,
                    encryptUid=fieldOf(data,"encrypt_uid"),logId=fieldOf(events,"log_id"),
                    verifyReason=fieldOf(events,"verify_reason").ifEmpty { "ato" },
                    verifyScene=fieldOf(events,"verify_scene").ifEmpty { "sms_login" },
                    copywritingKey=fieldOf(common,"copywriting_key").ifEmpty { "sms_login" },
                    diversionTag=fieldOf(common,"ies_safety_diversion_tag").ifEmpty { "mfa" },
                    channel=channel,smsContent=smsContent,
                    tips=fieldOf(data,"verify_scene_desc","description").ifEmpty { o.str("message") }))
            }
            requireOk(o,"登录失败")
            val cookies=extractCookiePairs(setCookies)
            if(cookies.isEmpty()) throw IOException("登录成功但未返回会话 cookie")
            val inner=data?.optJSONObject("user_info") ?: data ?: o
            return LoginResult.Success(Session(
                cookies=cookies,token=tokenHeader,
                userId=fieldOf(inner,"user_id","userId","uid").ifEmpty { fieldOf(data,"user_id","userId","uid") },
                name=fieldOf(inner,"name","user_name","username","nick_name"),
                avatar=fieldOf(inner,"avatar_url","avatar")))
        }

        internal fun parseHistoryPage(data: JSONObject): HistoryPage {
            val items=data.optJSONArray("data_list").objects().mapNotNull { row ->
                // vid/book_id 在响应里可能是 JSON 数字，统一字符串化避免精度失真
                val seriesId=row.str("book_id_str").ifEmpty { row.opt("book_id")?.toString().orEmpty() }
                if(!seriesId.matches(Regex("[0-9]{1,20}"))) return@mapNotNull null
                HistoryItem(
                    seriesId=seriesId,title=row.str("book_name"),cover=cleanUrl(row.str("thumb_url")),
                    episodeIndex=row.optInt("vid_index",0).coerceAtLeast(0),
                    episodeId=row.str("vid_str").ifEmpty { row.opt("vid")?.toString().orEmpty() },
                    positionMs=row.optLong("current_play_position",0).coerceAtLeast(0),
                    durationMs=row.optLong("duration",0).coerceAtLeast(0),
                    episodeCount=row.optInt("episode_cnt",0).coerceAtLeast(0),
                    updatedAtMs=row.optLong("read_timestamp_ms",0))
            }
            return HistoryPage(items,data.optBoolean("has_more",false),data.optLong("next_offset",0),data.optLong("total",0))
        }

        internal fun interpretUpsms(o: JSONObject,setCookies: List<String>,contextToken: String): UpsmsState {
            val code=errorCode(o)
            if(code==1045L) return UpsmsState.Waiting
            if(code==0L) {
                val data=o.optJSONObject("data") ?: JSONObject()
                if(data.optBoolean("registered",false)) {
                    val token=extractCookiePairs(setCookies).split("; ").firstOrNull { it.startsWith("passport_mfa_token=") }?.substringAfter('=').orEmpty()
                    return UpsmsState.Registered(token.ifEmpty { contextToken })
                }
                return UpsmsState.Waiting  // 防御形态：code=0 但缺 registered，按等待处理
            }
            throw PassportReject(code,"MFA 验证失败 $code: ${o.str("message").ifEmpty { "未知错误" }}")
        }

        private fun fieldOf(o: JSONObject?,vararg keys: String): String {
            if(o==null) return ""
            for(key in keys) { val value=o.str(key); if(value.isNotEmpty()) return value }
            return ""
        }

        /** user_info 的 req_id 是伪装字段名，实为数字 uid；加密串/空值不收 */
        private fun numericReqId(data: JSONObject): String=when(val rid=data.opt("req_id")) {
            is Number -> rid.toString()
            is String -> if(rid.isNotEmpty() && rid.all { it in '0'..'9' }) rid else ""
            else -> ""
        }

        private fun errorCode(o: JSONObject): Long {
            val data=o.optJSONObject("data")
            if(data!=null && data.has("error_code")) return data.optLong("error_code",0)
            return o.optLong("error_code",0)
        }

        /** passport 惯例：error_code 在 data 里，顶层只有 message；文案优先 data.description */
        private fun requireOk(o: JSONObject,prefix: String) {
            val code=errorCode(o)
            if(code==0L) return
            val data=o.optJSONObject("data")
            val message=sequenceOf(data?.str("description"),data?.str("message"),o.str("message")).firstOrNull { !it.isNullOrEmpty() } ?: "未知错误"
            throw PassportReject(code,"$prefix $code: $message")
        }

        private fun requireCodeOk(o: JSONObject,prefix: String) {
            val code=o.optLong("code",o.optLong("Code",0))
            if(code!=0L) throw IOException("$prefix ${o.str("message").ifEmpty { "错误 $code" }}")
        }
    }

    // ── 网络管道 ────────────────────────────────────────────────

    private class Raw(val body: String,val setCookies: List<String>,val token: String)
    private fun call(request: Request): Raw=RequestScope.execute(http.newCall(request)) { response ->
        if(!response.isSuccessful) throw IOException("账号接口请求失败（HTTP ${response.code}）")
        val bytes=response.body?.bytes() ?: ByteArray(0)
        Raw(String(bytes,Charsets.UTF_8),response.headers("Set-Cookie"),response.header("x-tt-token").orEmpty())
    }
    private fun callText(request: Request): String {
        val raw=call(request)
        if(raw.body.isEmpty()) throw IOException("接口返回空响应（签名或登录态可能已失效）")
        return raw.body
    }

    /** passport 系公共 query（抓包逐项对齐；ts/_rticket 由 Signer 补齐） */
    private fun passportSdkQuery(withRom: Boolean): Map<String,String> {
        val query=linkedMapOf(
            "cronet_version" to "8d40f833_2026-03-03","passport-sdk-version" to "5051452",
            "ttnet_version" to "4.2.243.31-douyin","use_new_token_expire_rule" to "true",
            "use_store_region_cookie" to "1")
        if(withRom) query["rom_version"]="miui_V12_V12.0.8.0.RKHCNXM"
        return query
    }

    /** passport 设备档案键（form 键与档案一致）+ 运行时状态常量（抓包原值） */
    private fun passportDeviceForm(): List<Pair<String,String>> {
        val device=VendorConstants.device
        val form=ArrayList<Pair<String,String>>(58)
        form+="_rticket" to clock().toString()
        for(key in listOf("ac","aid","app_name","cdid","channel","device_brand","device_id","device_platform","device_type","dpi","host_abi","iid","language","manifest_version_code","os","os_api","os_version","resolution","ssmix","update_version_code","version_code","version_name")) {
            form+=key to (device[key] ?: if(key=="cdid") CDID else "")
        }
        form+=listOf(
            "app_dark_mode" to "0","app_mini_window" to "0","battery_pct" to "72","charging" to "1",
            "cold_start_session_cnt_in_day" to "100","cold_start_session_cnt_in_life" to "76",
            "cold_start_session_id" to "4357f7af-60c0-4a52-af34-473c59155de9","compliance_status" to "0",
            "current_volume" to "1","down_speed" to "311565","dragon_device_type" to "phone",
            "font_scale" to "100","gender" to "2","har_status" to "0","is_android_pad_screen" to "0",
            "is_power_save_mode" to "0","need_personal_recommend" to "1","network_type" to "4",
            "normal_session_cnt_in_day" to "182","normal_session_cnt_in_life" to "270",
            "normal_session_id" to "23661b98-eac9-4960-81a4-d8837826b43f%23160","player_so_load" to "1",
            "pv_player" to "73932","screen_brightness" to "963","sys_dark_mode" to "0","sys_mini_window" to "1")
        return form
    }

    private fun passportCall(path: String,form: List<Pair<String,String>>,sdkRom: Boolean=true,cookie: String=""): Raw {
        val body=formUrlencoded(form).toByteArray(Charsets.UTF_8)
        val signed=signer.sign("$PASSPORT_ORIGIN$path",passportSdkQuery(sdkRom),body)
        val builder=Request.Builder().url(signed.url)
        signed.headers.forEach { (k,v) -> if(!k.equals("Content-Type",true)) builder.header(k,v) }
        builder.header("Content-Type",FORM_TYPE)
        builder.header("Cookie",cookieHeader(cookie))
        return call(builder.post(body.toRequestBody(FORM_TYPE.toMediaType())).build())
    }

    /** reading 系轻签名头（抓包实证只要这族；多带全签名头也能过，Signer 已生成） */
    private fun readingHeaders(): List<Pair<String,String>> {
        val ticket=(random()*4294967296.0).toLong()
        return listOf("x-ss-dp" to "8662","lc" to "101","x-reading-request" to "${clock()}-$ticket")
    }

    /** Cookie 三层合并：匿名底座（install_id/ttreq/store-region + 空 csrf 占位，reading 系
     *  按 install_id 风控、passport 缺 csrf 空值会走换绑分支报 1001）→ 本次调用显式
     *  cookie（csrf/mfa token）→ 已登录会话。设备级字段必须始终在场。 */
    private fun cookieHeader(explicit: String): String {
        val base=VendorConstants.anonymousCookie()
        val session=session ?: return if(explicit.isEmpty()) base else mergeCookies(base,explicit)
        return mergeCookies(if(explicit.isEmpty()) base else mergeCookies(base,explicit),session.cookies)
    }

    private fun sessionHeaders(): List<Pair<String,String>> {
        val current=session
        val headers=mutableListOf("Cookie" to cookieHeader(""))
        if(current!=null && current.token.isNotEmpty()) headers+="x-tt-token" to current.token.take(56)
        return headers
    }

    private fun gzip(raw: ByteArray): ByteArray=ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()

    /** reading 系统一调用：GET/POST 都带设备档案 query 与轻签名头；POST body gzip */
    private fun readingCall(path: String,query: Map<String,String> = emptyMap(),body: JSONObject? = null): String {
        val gzipped=body?.let { gzip(it.toString().toByteArray(Charsets.UTF_8)) }
        val signed=if(body==null) signer.sign("$LQ_ORIGIN$path",VendorConstants.device+query)
            else signer.sign("$LQ_ORIGIN$path",VendorConstants.device,gzipped)
        val builder=Request.Builder().url(signed.url)
        signed.headers.forEach { (k,v) -> builder.header(k,v) }
        (readingHeaders()+sessionHeaders()+if(gzipped!=null) listOf("Content-Encoding" to "gzip") else emptyList()).forEach { (k,v) -> builder.header(k,v) }
        val request=if(body==null) builder.get().build()
            else builder.post(gzipped!!.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return callText(request)
    }
}
