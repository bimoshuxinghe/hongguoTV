// SPDX-License-Identifier: GPL-3.0-only
// 设备注册链路：POST log.snssdk.com/service/2/device_register/
//
// 注册是 App API 身份链的起点：服务端按 query/body 里的 cdid/openudid 指纹发放
// device_id/install_id，此后业务请求的设备参数与匿名 Cookie（install_id/ttreq）
// 都从这来。passport 对「从未注册的设备」直接回 error_code=22「非法应用」，
// 所以登录链路前必须先完成注册。
//
// body 形态（抓包 + 逆向确认，与 hongguo-desktop 同源）：
//   明文 = AppLog 注册 JSON（magic_tag:"ss_app_log" + 47 字段 header + _gen_time）
//   → gzip（XFL/OS 覆写 00/00）→ TT-Encrypt V5（content-type: application/octet-stream;tt-data=a）
//
// 签名档案必须用静态兜底档案（含已知合法 device_id）：服务端要求注册请求带有
// 「已激活设备上下文」，全新/无 device_id 的档案签名会被静默拒绝（device_id=0）；
// 静态设备上下文 + body 新指纹 = 服务端按 body 指纹发放新设备号。
// Cookie 与 x-tt-token 一律不带（注册时还没有会话）。
package com.hongguotv.core

import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object DeviceRegister {
    const val REGISTER_ORIGIN="https://log.snssdk.com"
    const val REGISTER_PATH="/service/2/device_register/"
    /** 注册退避：一次注册（无论成败）后 24h 内不再尝试，避免高频注册触发指纹风控 */
    const val BACKOFF_MS=24*60*60*1000L
    /** 首次安装时间回拨（拟真参数：设备「刚装不久」，apk_first_install_time 与 query 共用） */
    private const val FIRST_INSTALL_BACKDATE_MS=12_000L
    private const val TT_DATA_TYPE="application/octet-stream;tt-data=a"

    private val random=SecureRandom()

    /** 新设备身份：cdid/clientudid/req_id 为 uuid v4，openudid 为 8 字节 hex */
    data class Identity(val cdid: String,val openudid: String,val clientudid: String,val reqId: String)
    /** 注册成功后的设备档案（install_id 即匿名 Cookie 的 install_id） */
    data class Profile(val deviceId: String,val installId: String,val cdid: String,val openudid: String,val ttreq: String)

    fun freshIdentity(): Identity {
        fun uuidV4(): String {
            val b=ByteArray(16).also(random::nextBytes)
            b[6]=(b[6].toInt() and 0x0f or 0x40).toByte()
            b[8]=(b[8].toInt() and 0x3f or 0x80).toByte()
            val hex=b.joinToString("") { "%02x".format(it) }
            return "${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}"
        }
        return Identity(
            cdid=uuidV4(),
            openudid=ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) },
            clientudid=uuidV4(),
            reqId=uuidV4())
    }

    /** 注册 query 的 40 个公共参数（顺序即签名 query 追加顺序；ts/_rticket 由 Signer 重签时覆盖） */
    internal fun registerQuery(cdid: String,openudid: String,nowMs: Long): LinkedHashMap<String,String> = linkedMapOf(
        "_rticket" to nowMs.toString(),
        "ac" to "wifi",
        "aid" to "8662",
        "appTheme" to "light",
        "app_name" to "novelread",
        "app_type" to "normal",
        "cdid" to cdid,
        "channel" to "xiaomi_8662_64",
        "cpu_support64" to "true",
        "cronet_version" to "8d40f833_2026-03-03",
        "device_brand" to "xiaomi",
        "device_platform" to "android",
        "device_type" to "23127PN0CC",
        "dpi" to "460",
        "first_launch_timestamp" to (nowMs/1000).toString(),
        "host_abi" to "arm64-v8a",
        "is_android_fold" to "0",
        "is_android_pad" to "0",
        "is_guest_mode" to "0",
        "is_preinstall" to "0",
        "language" to "zh",
        "last_deeplink_update_version_code" to "0",
        "manifest_version_code" to "73932",
        "md" to "0",
        "minor_status" to "0",
        "need_personal_recommend" to "1",
        "openudid" to openudid,
        "os" to "android",
        "os_api" to "34",
        "os_version" to "14",
        "package" to "com.phoenix.read",
        "resolution" to "1200*2670",
        "ssmix" to "a",
        "ts" to (nowMs/1000).toString(),
        "tt_data" to "a",
        "ttnet_version" to "4.2.243.31-douyin",
        "update_version_code" to "73932",
        "use_store_region_cookie" to "1",
        "version_code" to "73932",
        "version_name" to "7.3.9.32")

    /** AppLog 注册 JSON（抓包模板：47 个指纹字段，设备身份替换为新生成的） */
    internal fun applogRegisterJson(identity: Identity,nowMs: Long): JSONObject {
        val h=JSONObject()
        h.put("access","wifi")
        h.put("aid",8662)
        h.put("apk_first_install_time",nowMs-FIRST_INSTALL_BACKDATE_MS)
        h.put("app_version","7.3.9.32")
        h.put("app_version_minor","")
        h.put("cdid",identity.cdid)
        h.put("channel","xiaomi_8662_64")
        h.put("clientudid",identity.clientudid)
        h.put("cpu_abi","arm64-v8a")
        h.put("custom",JSONObject()
            .put("client_ipv4","116.130.67.90")
            .put("is_android_fold",0)
            .put("is_android_pad",0)
            .put("filter_warn",0)
            .put("web_ua","Mozilla/5.0 (Linux; Android 14; Xiaomi 14 Build/UKQ1.230804.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/122.0.0.0 Mobile Safari/537.36"))
        h.put("density_dpi",460)
        h.put("device_brand","xiaomi")
        h.put("device_category","phone")
        h.put("device_manufacturer","Xiaomi")
        h.put("device_model","Xiaomi 14")
        h.put("device_platform","android")
        h.put("display_density","xxhdpi")
        h.put("display_name","红果短剧")
        h.put("git_hash","fe95b95")
        h.put("guest_mode",0)
        h.put("ipv6_list",JSONArray())
        h.put("is_system_app",0)
        h.put("language","zh")
        h.put("manifest_version_code",73932)
        h.put("not_request_sender",0)
        h.put("oaid_may_support",true)
        h.put("openudid",identity.openudid)
        h.put("os","Android")
        h.put("os_api",34)
        h.put("os_version","14")
        h.put("package","com.phoenix.read")
        h.put("region","CN")
        h.put("release_build","UKQ1.230804.001_1701234567")
        h.put("req_id",identity.reqId)
        h.put("resolution","2670x1200")
        h.put("rom","hyperos")
        h.put("rom_version","OS1.0.32.0.UNCCNXM")
        h.put("sdk_flavor","china")
        h.put("sdk_target_version",29)
        h.put("sdk_version","3.7.3-rc.116-douyin")
        h.put("sig_hash","aea615ab910015038f73c47e45d21466")
        h.put("sim_serial_number",JSONArray())
        h.put("timezone",8)
        h.put("tz_name","Asia/Shanghai")
        h.put("tz_offset",28800)
        h.put("update_version_code",73932)
        h.put("version_code",73932)
        return JSONObject().put("magic_tag","ss_app_log").put("header",h).put("_gen_time",nowMs)
    }

    /** 解析注册响应（数字/字符串双形态并存；device_id=0 = 加密或指纹形态被静默拒绝） */
    internal fun parseRegister(body: String,identity: Identity,ttreq: String): Profile {
        val o=JSONObject(body)
        val code=o.optLong("code",0)
        if(code!=0L) throw IOException("设备注册失败 $code: ${o.str("message").ifEmpty { "未知错误" }}")
        fun valueOf(vararg keys: String): String {
            for(key in keys) { val value=o.opt(key); if(value!=null && value!=JSONObject.NULL) return value.toString() }
            return ""
        }
        val deviceId=valueOf("device_id_str","device_id")
        val installId=valueOf("install_id_str","install_id")
        if(deviceId.isEmpty()) throw IOException("设备注册响应缺少 device_id")
        if(deviceId=="0") throw IOException("设备注册被服务端静默拒绝（device_id=0），加密或指纹形态可能过期")
        return Profile(deviceId,installId,identity.cdid,identity.openudid,ttreq)
    }

    /** 注册一台全新设备；失败抛 IOException，调用方回落静态兜底档案并记 24h 退避 */
    fun register(http: OkHttpClient=defaultHttp(),clock: ()->Long=System::currentTimeMillis): Profile {
        val nowMs=clock()
        val identity=freshIdentity()
        val query=registerQuery(identity.cdid,identity.openudid,nowMs)
        val plain=applogRegisterJson(identity,nowMs).toString().toByteArray(Charsets.UTF_8)
        val body=TtEncryptV5.encrypt(plain)
        // 签名档案用现役 VendorConstants.device（静态兜底或上次注册的档案——
        // 服务端要求「已激活设备上下文」），cdid/openudid 由 query 覆盖为新指纹
        val signed=Signer().sign("$REGISTER_ORIGIN$REGISTER_PATH",VendorConstants.device+query,body)
        val builder=Request.Builder().url(signed.url)
        signed.headers.forEach { (k,v) -> if(!k.equals("Content-Type",true)) builder.header(k,v) }
        builder.header("Content-Type",TT_DATA_TYPE)
        val raw=RequestScope.execute(http.newCall(builder.post(body.toRequestBody(TT_DATA_TYPE.toMediaType())).build())) { response ->
            if(!response.isSuccessful) throw IOException("设备注册请求失败（HTTP ${response.code}）")
            val bytes=response.body?.bytes() ?: ByteArray(0)
            if(bytes.isEmpty()) throw IOException("设备注册返回空响应")
            Pair(String(bytes,Charsets.UTF_8),response.headers("Set-Cookie"))
        }
        // Set-Cookie 下发本设备自己的 ttreq 票（按新 install_id 发放）——静态兜底票
        // 绑定的是静态 iid，配新 iid 会出现「install_id 与票不同源」的 Cookie
        val ttreq=raw.second.firstOrNull { it.substringBefore(';').trim().startsWith("ttreq=") }
            ?.substringBefore(';')?.trim()?.substringAfter('=') ?: ""
        return parseRegister(raw.first,identity,ttreq)
    }

    /** 独立短超时客户端：注册在启动链路上，网络不可达时尽快回落静态档案 */
    internal fun defaultHttp(): OkHttpClient=OkHttpClient.Builder()
        .connectTimeout(8,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS)
        .dns(Ipv4FirstDns).build()
}
