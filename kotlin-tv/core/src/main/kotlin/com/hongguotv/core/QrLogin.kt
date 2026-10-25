// SPDX-License-Identifier: GPL-3.0-only
// 红果扫码登录：网页版 passport（hongguoduanju.com，aid=2503）的二维码端点。
// 用户用「番茄小说 / 抖音 / 红果」APP 扫码并在手机上确认即可完成登录，
// 全程无 App 签名、无设备注册 —— 是短信链路被账号级风控卡死时的官方等价出路。
// （实测抖音/红果的扫码器常不识别第三方域二维码，番茄小说 APP 必扫。）
//
// 链路（hongguo-sky 同源实证）：
//   GET /passport/web/get_qrcode/          → {token, qrcode(base64 PNG), expire_time, copywriting}
//   GET /passport/web/check_qrconnect/?token=…（UI 3 秒轮询）
//     → data.status: new / scanned / confirmed，confirmed 时附 redirect_url
//   confirmed 后跟随 redirect_url，最终从全会话累积的 cookie 里收割 sessionid，
//   再查 /passport/account/info/v2/ 取昵称。
//
// 关键形态：**全程共享同一份 cookie 罐**（参考实现 requests.Session 同款语义）——
// 取码、轮询、跳转链中 passport 持续下发审计 cookie，confirmed 换 sessionid
// 时必须带上；逐跳收割 Set-Cookie（OkHttp 自动重定向不保存中间跳的 cookie）。
//
// 鉴权形态：收割的 web 会话 cookie 直接注入 App 接口 Cookie（reading 系设备签名
// 照旧），x-tt-token 退化为 sid_tt/sessionid 值（hongguo-sky app_token 语义）。
// passport web 端点按浏览器形态校验：必须带浏览器 UA + Referer，App UA 反而被拒。
package com.hongguotv.core

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class QrLogin {
    /** passport web 会话 cookie 罐（自 get_qrcode 起持续累积，后值覆盖、首现位置） */
    private val jar=linkedMapOf<String,String>()

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

    /** confirmed 后收割会话：跟随 redirect_url（失败不致命——参考实现里 session 罐
     *  在 confirm 时往往已收到 sessionid），再取用户资料。
     *  身份查询直接走红果域（aid=8662）info/v2：字节各 APP 的 user_id 不同但同挂一个
     *  passport 账号，红果域返回的就是该手机号在红果的账号（name/userId）；
     *  红果域解析失败时回落 web 域（2503）仅作展示。 */
    fun harvest(http: OkHttpClient=defaultHttp(),redirectUrl: String): AccountApi.Session {
        if(redirectUrl.isNotEmpty()) runCatching { followRedirects(http,redirectUrl) }
            .onFailure { if(jar.isEmpty()) throw IOException("扫码跳转失败：${it.message}") }
        val cookies=cookieHeader()
        val hasSession=cookies.split("; ").any {
            it.startsWith("sessionid=")||it.startsWith("sid_tt=")||it.startsWith("sessionid_ss=")
        }
        if(!hasSession) {
            val got=jar.keys.joinToString(",").ifEmpty { "无" }
            throw IOException("未收到会话凭证（服务端下发: $got）")
        }
        // 红果域（8662）优先——失败（会话过期=13 等）回落 web 域，仅用于展示用户名
        val user=runCatching {
            parseInfoV2(JSONObject(get(http,query(APP_INFO_URL,appInfoQuery()))).optJSONObject("data") ?: JSONObject())
        }.getOrElse {
            runCatching {
                parseInfoV2(JSONObject(get(http,query("$SITE/passport/account/info/v2/",baseQuery()))).optJSONObject("data") ?: JSONObject())
            }.getOrDefault(AccountApi.PassportUser("","",""))
        }
        val pairs=cookies.split("; ")
        val token=pairs.firstOrNull { it.startsWith("sid_tt=") }?.substringAfter('=')
            ?: pairs.firstOrNull { it.startsWith("sessionid=") }?.substringAfter('=').orEmpty()
        return AccountApi.Session(cookies=cookies,token=token,userId=user.userId,name=user.name,avatar=user.avatar)
    }

    // ── 会话 cookie 罐 ─────────────────────────────────────────

    private fun absorb(lines: List<String>) {
        for(line in lines) {
            val kv=line.substringBefore(';').trim(); val at=kv.indexOf('=')
            if(at>0) { val key=kv.substring(0,at).trim(); if(key.isNotEmpty()) jar[key]=kv.substring(at+1) }
        }
    }

    private fun cookieHeader(): String=jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

    // ── 网络管道 ────────────────────────────────────────────────

    private fun query(url: String,params: Map<String,String>)=url+"?"+params.entries.joinToString("&") { (k,v)->Signer.encode(k)+"="+Signer.encode(v) }

    private fun get(http: OkHttpClient,url: String): String {
        val builder=Request.Builder().url(url).header("User-Agent",WEB_UA).header("Referer","$SITE/")
        val cookie=cookieHeader()
        if(cookie.isNotEmpty()) builder.header("Cookie",cookie)
        return RequestScope.execute(http.newCall(builder.build())) { response ->
            if(!response.isSuccessful) throw IOException("passport 请求失败（HTTP ${response.code}）")
            absorb(response.headers("Set-Cookie"))
            response.body?.string().orEmpty()
        }
    }

    /** 逐跳跟随重定向（每跳带罐内 cookie 并吸收新 Set-Cookie）：OkHttp 自动重定向
     *  既不保存中间跳的 cookie，也不会把前面跳收到的 cookie 带下去，必须手动跟 */
    private fun followRedirects(http: OkHttpClient,startUrl: String) {
        var url=startUrl
        repeat(MAX_REDIRECT_HOPS) {
            val builder=Request.Builder().url(url).header("User-Agent",WEB_UA).header("Referer","$SITE/")
            val cookie=cookieHeader()
            if(cookie.isNotEmpty()) builder.header("Cookie",cookie)
            val location=RequestScope.execute(http.newCall(builder.build())) { response ->
                absorb(response.headers("Set-Cookie"))
                if(response.isRedirect) response.header("Location") else null
            }
            if(location.isNullOrEmpty()) return
            url=URI(url).resolve(location).toString()
        }
        throw IOException("扫码登录跳转次数过多，已中止")
    }

    // ── 纯解析（可单测，静态入口）───────────────────────────────

    companion object {
        const val SITE="https://hongguoduanju.com"
        internal const val AID="2503"
        /** 红果域 passport（novel.snssdk.com，aid=8662）：web 会话跨域通用，
         *  用它查用户身份即得「红果账号」——UID 是红果域的，与番茄域不同 */
        internal const val APP_INFO_URL="https://novel.snssdk.com/passport/account/info/v2/"
        internal const val SDK_VERSION="3.0.16"
        internal const val WEB_UA="Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        const val POLL_INTERVAL_MS=3000L
        private const val TTL_FALLBACK_MS=240_000L
        private const val MAX_REDIRECT_HOPS=12

        internal fun baseQuery(next: String="/"): LinkedHashMap<String,String> = linkedMapOf(
            "aid" to AID,"language" to "zh","account_sdk_source" to "web",
            "passport_jssdk_version" to SDK_VERSION,"passport_jssdk_type" to "normal","next" to next)

        /** 红果域 info/v2 的 query（aid=8662） */
        private fun appInfoQuery(): LinkedHashMap<String,String> = linkedMapOf(
            "aid" to "8662","language" to "zh","account_sdk_source" to "web")

        /** 扫码专用客户端（app 壳持有单例复用）：关自动重定向以便逐跳收割 Set-Cookie */
        fun defaultHttp(): OkHttpClient=OkHttpClient.Builder()
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS)
            .followRedirects(false).dns(Ipv4FirstDns).build()

        internal fun parseQrcode(data: JSONObject): QrCode {
            val code=data.optLong("error_code",0)
            if(code!=0L) throw IOException("二维码获取失败 $code: ${data.str("description").ifEmpty { data.str("message").ifEmpty { "未知错误" } }}")
            val token=data.str("token")
            if(token.isEmpty()) throw IOException("二维码响应缺少 token")
            val png=runCatching { decode64(data.str("qrcode").substringAfter("base64,").trim()) }
                .getOrElse { throw IOException("二维码图片解码失败") }
            if(png.isEmpty()) throw IOException("二维码图片为空")
            val expire=data.optLong("expire_time",0)
            // 官方 copywriting 常写「抖音 APP 扫码验证」，实测抖音/红果不识别此码（服务端
            // 把 qrcode_index_url 标注为 outSideAppName=番茄小说），只有番茄小说 APP 能扫。
            val prompt=cleanCopywriting(data.str("copywriting"))
            val fallback="请用「番茄小说」APP 扫码并在手机上确认（抖音 / 红果 APP 不识别此码）"
            return QrCode(token,png, if(prompt.isEmpty() || prompt.contains("抖音")) fallback else prompt,
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

    data class QrCode(val token: String,val png: ByteArray,val prompt: String,val ttlMs: Long)
    enum class State { WAITING, SCANNED, CONFIRMED, EXPIRED }
    data class Poll(val state: State,val redirectUrl: String)
}
