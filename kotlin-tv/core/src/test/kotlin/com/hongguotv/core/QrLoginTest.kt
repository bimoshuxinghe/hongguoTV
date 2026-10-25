// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrLoginTest {
    /** web passport 基础参数：aid 必须是红果短剧PC站 513755（官网实抓值）——
     *  回退到 2503 会让码落到番茄域、同步不到红果记录，是致命回归 */
    @Test fun baseQueryUsesWebPassportParams() {
        val query=QrLogin.baseQuery("https://hongguoduanju.com/")
        assertEquals("513755",query["aid"])
        assertEquals(QrLogin.AID,query["aid"])
        assertEquals("web",query["account_sdk_source"])
        assertEquals("2.4.13",query["passport_jssdk_version"])
        assertEquals(QrLogin.SDK_VERSION,query["passport_jssdk_version"])
        assertEquals("normal",query["passport_jssdk_type"])
        assertEquals("1",query["is_from_ttaccountsdk"])
        assertEquals("https://hongguoduanju.com/",query["next"])
        assertEquals("https://hongguoduanju.com",QrLogin.SITE)
    }

    /** get_qrcode 解析：token、PNG base64（兼容 data URL 前缀）、富文本文案、TTL 兜底 */
    @Test fun parseQrcodeExtractsTokenPngPromptAndTtl() {
        val pngBase64=java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47))
        val data=JSONObject()
            .put("token","qr-token-1")
            .put("qrcode","data:image/png;base64,$pngBase64")
            .put("copywriting","""{"desc":"[color=red]请扫码[/color]并在手机上确认"}""")
            .put("expire_time",300)
        val qr=QrLogin.parseQrcode(data)
        assertEquals("qr-token-1",qr.token)
        assertArrayEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47),qr.png)
        assertEquals("请扫码并在手机上确认",qr.prompt)
        assertEquals(300_000L,qr.ttlMs)
        // expire_time 缺失/离谱 → 240s 兜底；文案缺省 → 默认提示
        val fallback=QrLogin.parseQrcode(JSONObject().put("token","t").put("qrcode",pngBase64))
        assertEquals(240_000L,fallback.ttlMs)
        assertTrue(fallback.prompt.contains("扫码"))
    }

    @Test fun parseQrcodeRejectsErrorPayload() {
        val failure=runCatching { QrLogin.parseQrcode(JSONObject().put("error_code",105).put("description","太频繁")) }
            .exceptionOrNull()!!
        assertTrue(failure.message!!.contains("105"))
        assertTrue(failure.message!!.contains("太频繁"))
        val missing=runCatching { QrLogin.parseQrcode(JSONObject()) }.exceptionOrNull()!!
        assertTrue(missing.message!!.contains("token"))
    }

    /** 官方 copywriting（抖音 APP 扫码验证）在 aid=513755 红果域码下即官网原文，保留；
     *  缺省时兜底红果短剧 App 扫码指引 */
    @Test fun parseQrcodeKeepsOfficialCopywriting() {
        val pngBase64=java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47))
        val official=QrLogin.parseQrcode(JSONObject()
            .put("token","t").put("qrcode","data:image/png;base64,$pngBase64")
            .put("copywriting","""{"desc":"请使用[color=red]「抖音 APP」[/]扫码验证"}"""))
        assertEquals("请使用「抖音 APP」扫码验证",official.prompt)
        val fallback=QrLogin.parseQrcode(JSONObject().put("token","t").put("qrcode","data:image/png;base64,$pngBase64"))
        assertTrue(fallback.prompt.contains("红果短剧"))
    }

    /** check_qrconnect 状态归一：redirect_url / confirmed 同义、scanned 提示、error_code 过期 */
    @Test fun parsePollMapsStates() {
        assertEquals(QrLogin.State.CONFIRMED,QrLogin.parsePoll(JSONObject().put("status","new").put("redirect_url","https://x/y")).state)
        assertEquals(QrLogin.State.CONFIRMED,QrLogin.parsePoll(JSONObject().put("status","confirmed")).state)
        assertEquals(QrLogin.State.CONFIRMED,QrLogin.parsePoll(JSONObject().put("status","complete")).state)
        assertEquals(QrLogin.State.SCANNED,QrLogin.parsePoll(JSONObject().put("status","scanned")).state)
        assertEquals(QrLogin.State.WAITING,QrLogin.parsePoll(JSONObject().put("status","new")).state)
        assertEquals(QrLogin.State.WAITING,QrLogin.parsePoll(JSONObject()).state)
        assertEquals(QrLogin.State.EXPIRED,QrLogin.parsePoll(JSONObject().put("error_code",108)).state)
    }

    /** info/v2 用户字段：user_id 数字/字符串双形态、昵称多键名 fallback */
    @Test fun parseInfoV2ReadsUserFields() {
        val stringForm=QrLogin.parseInfoV2(JSONObject().put("user_id_str","7100012345678").put("screen_name","小明").put("avatar_url","http://a/1.png"))
        assertEquals("7100012345678",stringForm.userId)
        assertEquals("小明",stringForm.name)
        assertEquals("http://a/1.png",stringForm.avatar)
        val numericForm=QrLogin.parseInfoV2(JSONObject().put("user_id",7100012345678L).put("name","小红"))
        assertEquals("7100012345678",numericForm.userId)
        assertEquals("小红",numericForm.name)
        val empty=QrLogin.parseInfoV2(JSONObject())
        assertTrue(empty.userId.isEmpty() && empty.name.isEmpty() && empty.avatar.isEmpty())
    }

    /** 富文本清洗：desc JSON 提取 + [color=red] 标记剥离 */
    @Test fun cleanCopywritingStripsRichText() {
        assertEquals("请扫码确认",QrLogin.cleanCopywriting("""{"desc":"[color=red]请扫码[/color]确认"}"""))
        assertEquals("纯文本",QrLogin.cleanCopywriting("纯文本"))
        assertEquals("确认",QrLogin.cleanCopywriting("[color=red]确认"))
    }
}
