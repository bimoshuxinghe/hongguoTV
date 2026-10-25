// SPDX-License-Identifier: GPL-3.0-only
// 红果扫码登录：网页版 passport（hongguoduanju.com，aid=2503）的二维码端点。
// 用户用「抖音 / 番茄小说 / 红果」任一 APP 扫码并在手机上确认即可完成登录，
// 全程无 App 签名、无设备注册 —— 是短信链路被账号级风控卡死时的官方等价出路。
//
// 链路（hongguo-sky 同源实证）：
//   GET /passport/web/get_qrcode/          → {token, qrcode(base64 PNG), expire_time, copywriting}
//   GET /passport/web/check_qrconnect/?token=…（UI 3 秒轮询）
//     → data.status: new / scanned / confirmed，confirmed 时附 redirect_url
//   confirmed 后手动逐跳跟随 redirect_url（OkHttp 关自动重定向）逐跳收集
//   Set-Cookie（sessionid 等账号级会话），再查 /passport/account/info/v2/ 取昵称。
//
// 鉴权形态：下发的 web 会话 cookie 直接注入 App 接口 Cookie（reading 系设备签名
// 照旧），x-tt-token 退化为 sid_tt/sessionid 值（hongguo-sky app_token 语义）。
// passport web 端点按浏览器形态校验：必须带浏览器 UA + Referer，App UA 反而被拒。
package com.hongguotv.core

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object QrLogin {
    const val SITE="https://hongguoduanju.com"
    internal const val AID="2503"
    internal const val SDK_VERSION="3.0.16"
    internal const val WEB_UA="Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    const val POLL_INTERVAL_MS=3000L
    private const val TTL_FALLBACK_MS=240_000L
    private const val MAX_REDIRECT_HOPS=12

    data class QrCode(val token: String,val png: ByteArray,val prompt: String,val ttlMs: Long)
    enum class State { WAITING, SCANNED, CONFIRMED, EXPIRED }
    data class Poll(val state: State,val redirectUrl: String)

    /** 取一张二维码；失败抛 IOException（UI 提示后重试） */
    fun newQrcode(http: OkHttpClient=defaultHttp()): QrCode {
        val body=get(http,query("$SITE/passport/web/get_qrcode/",baseQuery("$SITE/")+linkedMapOf("need_logo" to "true")))
        return parseQrcode(JSONObject(body).optJSONObject("data") ?: JSONObject())
    }

    /** 轮询一次扫码状态（UI 每 POLL_INTERVAL_MS 重发） */
    fun check(http: OkHttpClient=defaultHttp(),token: String): Poll {
        if(token.isEmpty()) return Poll(State.WAITING,"")
        val body=get(http,query("$SITE/passport/web/check_qrconnect/",baseQuery("/")+linkedMapOf("token" to token)))
        return parsePoll(JSONObject(body).optJSONObject("data") ?: JSONObject())
    }

    /** confirmed 后收割会话：逐跳跟随 redirect_url 收 Set-Cookie，再取用户资料。
     *  x-tt-token 退化为 sid_tt/sessionid 值，App 接口以 Cookie 鉴权为主。 */
    fun harvest(http: OkHttpClient=defaultHttp(),redirectUrl: String): AccountApi.Session {
        if(redirectUrl.isEmpty()) throw IOException("扫码确认响应缺少跳转地址")
        val cookies=followRedirects(http,redirectUrl)
        val hasSession=cookies.split("; ").any {
            it.startsWith("sessionid=")||it.startsWith("sid_tt=")||it.startsWith("sessionid_ss=")
        }
        if(!hasSession) throw IOException("扫码登录成功但未收到会话凭证，请重试")
        val user=runCatching {
            parseInfoV2(JSONObject(get(http,query("$SITE/passport/account/info/v2/",baseQuery()),cookie=cookies)).optJSONObject("data") ?: JSONObject())
        }.getOrDefault(AccountApi.PassportUser("","",""))
        val pairs=cookies.split("; ")
        val token=pairs.firstOrNull { it.startsWith("sid_tt=") }?.substringAfter('=')
            ?: pairs.firstOrNull { it.startsWith("sessionid=") }?.substringAfter('=').orEmpty()
        return AccountApi.Session(cookies=cookies,token=token,userId=user.userId,name=user.name,avatar=user.avatar)
    }

    // ── 网络管道 ────────────────────────────────────────────────

    internal fun baseQuery(next: String="/"): LinkedHashMap<String,String> = linkedMapOf(
        "aid" to AID,"language" to "zh","account_sdk_source" to "web",
        "passport_jssdk_version" to SDK_VERSION,"passport_jssdk_type" to "normal","next" to next)

    private fun query(url: String,params: Map<String,String>)=url+"?"+params.entries.joinToString("&") { (k,v)->Signer.encode(k)+"="+Signer.encode(v) }

    private fun get(http: OkHttpClient,url: String,cookie: String=""): String {
        val builder=Request.Builder().url(url).header("User-Agent",WEB_UA).header("Referer","$SITE/")
        if(cookie.isNotEmpty()) builder.header("Cookie",cookie)
        return RequestScope.execute(http.newCall(builder.build())) { response ->
            if(!response.isSuccessful) throw IOException("passport 请求失败（HTTP ${response.code}）")
            response.body?.string().orEmpty()
        }
    }

    /** 逐跳跟随重定向收集 Set-Cookie：OkHttp 自动重定向不保存中间跳的 cookie，必须手动跟 */
    private fun followRedirects(http: OkHttpClient,startUrl: String): String {
        var url=startUrl
        val pairs=linkedMapOf<String,String>()
        repeat(MAX_REDIRECT_HOPS) {
            val location=RequestScope.execute(http.newCall(
                Request.Builder().url(url).header("User-Agent",WEB_UA).header("Referer","$SITE/").build())) { response ->
                response.headers("Set-Cookie").forEach { line ->
                    val kv=line.substringBefore(';').trim(); val at=kv.indexOf('=')
                    if(at>0) pairs[kv.substring(0,at).trim()]=kv.substring(at+1)
                }
                if(response.isRedirect) response.header("Location") else null
            }
            if(location.isNullOrEmpty()) return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
            url=URI(url).resolve(location).toString()
        }
        throw IOException("扫码登录跳转次数过多，已中止")
    }

    /** 扫码专用客户端（app 壳持有单例复用）：关自动重定向以便逐跳收割 Set-Cookie */
    fun defaultHttp(): OkHttpClient=OkHttpClient.Builder()
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS)
        .followRedirects(false).dns(Ipv4FirstDns).build()

    // ── 纯解析（可单测）─────────────────────────────────────────

    internal fun parseQrcode(data: JSONObject): QrCode {
        val code=data.optLong("error_code",0)
        if(code!=0L) throw IOException("二维码获取失败 $code: ${data.str("description").ifEmpty { data.str("message").ifEmpty { "未知错误" } }}")
        val token=data.str("token")
        if(token.isEmpty()) throw IOException("二维码响应缺少 token")
        val png=runCatching { decode64(data.str("qrcode").substringAfter("base64,").trim()) }
            .getOrElse { throw IOException("二维码图片解码失败") }
        if(png.isEmpty()) throw IOException("二维码图片为空")
        val expire=data.optLong("expire_time",0)
        return QrCode(token,png,
            cleanCopywriting(data.str("copywriting")).ifEmpty { "请用「抖音 / 番茄小说 / 红果」APP 扫码，并在手机上确认" },
            if(expire in 30..3600) expire*1000 else TTL_FALLBACK_MS)
    }

    internal fun parsePoll(data: JSONObject): Poll {
        if(data.optLong("error_code",0)!=0L) return Poll(State.EXPIRED,"")
        val redirect=data.str("redirect_url")
        val status=data.str("status").lowercase()
        return when {
            redirect.isNotEmpty() || status in setOf("confirmed","complete","success") -> Poll(State.CONFIRMED,redirect)
            status.contains("scan") -> Poll(State.SCANNED,"")
            else -> Poll(State.WAITING,"")
        }
    }

    internal fun parseInfoV2(data: JSONObject): AccountApi.PassportUser {
        fun field(vararg keys: String): String {
            for(key in keys) { val value=data.str(key); if(value.isNotEmpty()) return value }
            return ""
        }
        return AccountApi.PassportUser(
            userId=field("user_id_str","user_id","uid").ifEmpty { (data.opt("user_id") as? Number)?.toString().orEmpty() },
            name=field("name","screen_name","nickname"),
            avatar=field("avatar_url","avatar"))
    }

    /** 服务端富文本标记（[color=red]…[/color]）清洗 */
    internal fun cleanCopywriting(raw: String): String {
        val desc=runCatching { JSONObject(raw).str("desc") }.getOrDefault("")
        return (if(desc.isNotEmpty()) desc else raw).replace(Regex("\\[[^\\[\\]]{0,24}\\]"),"").trim()
    }
}
