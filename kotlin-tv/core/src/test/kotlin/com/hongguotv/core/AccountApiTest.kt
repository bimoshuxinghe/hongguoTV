// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountApiTest {
    /** XOR(0x05) hex：抓包测试向量（'+'→2e、'8'→3d、'6'→33、'1'→34…） */
    @Test fun mobileAndCodeUseXor05Hex() {
        assertEquals("2e3d3334363d353534363d353535",AccountApi.encodeMobile("13800138000"))
        assertEquals("3d353137",AccountApi.encodeCode("8042"))
        assertEquals("343736313033",AccountApi.encodeCode("123456"))
    }

    /** Set-Cookie 清洗：剥属性、后值覆盖且保持首现位置、跳过非法行 */
    @Test fun cookiesAreCleanedAndDeduped() {
        val lines=listOf(
            "sessionid=abc123; Path=/; Domain=.snssdk.com; HttpOnly",
            "x-tt-token=xx|yy; Path=/",
            "odin_tt=aaa; Path=/",
            "odin_tt=bbb; Path=/",
            "badline-no-equals")
        assertEquals("sessionid=abc123; x-tt-token=xx|yy; odin_tt=bbb",AccountApi.extractCookiePairs(lines))
    }

    /** form 编码：JSON 串整体转义、空格按 %20 */
    @Test fun formEncodingEscapesJsonValues() {
        val out=AccountApi.formUrlencoded(listOf("biz_params" to """{"a":"1","b":"x/y"}""","copywriting_key" to "sms login"))
        assertTrue("大括号/引号要转义: $out".let { out.startsWith("biz_params=%7B%22a%22") })
        assertTrue("空格按 %20: $out".let { out.contains("copywriting_key=sms%20login") })
    }

    /** 成功登录响应：cookie + x-tt-token 响应头 + 宽松用户字段（user_id 为 JSON 数字） */
    @Test fun loginSuccessCarriesCookiesTokenAndUser() {
        val body="""{"error_code":0,"message":"success","data":{"user_id":7100012345678,"name":"红果用户","mobile":"138****0000"}}"""
        val result=AccountApi.parseLoginBody(body,listOf("sessionid=s1; Path=/"),"00tok--abc-3.0.3")
        val session=(result as AccountApi.LoginResult.Success).session
        assertEquals("sessionid=s1",session.cookies)
        assertEquals("00tok--abc-3.0.3",session.token)
        assertEquals("7100012345678",session.userId)
        assertEquals("红果用户",session.name)
    }

    /** 常规失败（嵌套 error_code 形态，1.1.3 真机抓包样本） */
    @Test fun loginErrorSurfacesNestedCode() {
        val body="""{"data":{"description":"手机号错误，请重新填写","error_code":1003},"message":"error"}"""
        val error=runCatching { AccountApi.parseLoginBody(body,emptyList(),"") }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("1003"))
        assertTrue(error.message!!.contains("手机号错误"))
    }

    /** MFA 分支：biz_params 嵌套特征 + 95 通道号替换为真实 106 通道 + mfa_token 提取 */
    @Test fun mfaResponseIsDetectedWithRealChannel() {
        val body="""{"data":{"error_code":2046,"description":"为保证账号安全，暂不支持此操作",
            "biz_params":{"passport_mfa_retry_tag":"1","sms_code_key":"fb2825d4c17fad783d28724dd8aebe5e"},
            "encrypt_uid":"9bRhVfo5rymE","event_params":{"log_id":"2026100422LOG","verify_reason":"ato","verify_scene":"sms_login"},
            "common_params":{"copywriting_key":"sms_login","ies_safety_diversion_tag":"mfa"},
            "verify_ways":[{"channel_mobile":"9515211003","sms_content":"YZ","verify_way":"mobile_up_sms_verify"}],
            "verify_scene_desc":"为保证帐号安全，请完成身份验证"},"message":"error"}"""
        val cookies=listOf("passport_mfa_token=CjeRkKbStbWgOEQpGsdgfE0qpV8RtNI1eV77042U15V; Path=/; HttpOnly")
        val mfa=(AccountApi.parseLoginBody(body,cookies,"") as AccountApi.LoginResult.Mfa).context
        assertEquals("1",mfa.retryTag)
        assertEquals("fb2825d4c17fad783d28724dd8aebe5e",mfa.smsCodeKey)
        assertEquals("CjeRkKbStbWgOEQpGsdgfE0qpV8RtNI1eV77042U15V",mfa.mfaToken)
        assertEquals("10691859839103",mfa.channel)
        assertEquals("9bRhVfo5rymE",mfa.encryptUid)
        assertEquals("2026100422LOG",mfa.logId)
        assertTrue(mfa.tips.contains("完成身份验证"))
    }

    /** upsms 轮询解读：1045 等待 / registered+新 token / 防御形态 / 其他码报错 */
    @Test fun upsmsResponsesAreInterpreted() {
        assertEquals(AccountApi.UpsmsState.Waiting,AccountApi.interpretUpsms(JSONObject("""{"error_code":1045,"message":"waiting"}"""),emptyList(),"old"))
        val registered=AccountApi.interpretUpsms(JSONObject("""{"data":{"registered":true,"ticket":"tk"}}"""),listOf("passport_mfa_token=new-tok; Path=/"),"old")
        assertEquals("new-tok",(registered as AccountApi.UpsmsState.Registered).mfaToken)
        assertEquals(AccountApi.UpsmsState.Waiting,AccountApi.interpretUpsms(JSONObject("""{"data":{}}"""),emptyList(),"old"))
        val rejected=runCatching { AccountApi.interpretUpsms(JSONObject("""{"data":{"error_code":1003},"message":"拒绝"}"""),emptyList(),"old") }.exceptionOrNull()!!
        assertTrue(rejected.message!!.contains("1003"))
    }

    /** 云端历史行：线上 vid_index 0 基与本应用集序一致（直接透传）、数字 id 字符串化、非法 id 过滤 */
    @Test fun cloudHistoryMapsWireIndexesDirectly() {
        val data=JSONObject("""{"data_list":[
            {"book_id_str":"111","book_name":"剧A","thumb_url":"https:\/\/p3-sign.example\/a.jpg","vid_index":0,"current_play_position":35000,"duration":120000,"read_timestamp_ms":1000},
            {"book_id":222,"book_name":"剧B","vid_index":7,"vid":9900099999},
            {"book_id_str":"not-numeric","vid_index":3}],"has_more":false,"total":2}""")
        val page=AccountApi.parseHistoryPage(data)
        assertEquals(listOf("111","222"),page.items.map { it.seriesId })
        assertEquals(listOf(0,7),page.items.map { it.episodeIndex })
        assertEquals("9900099999",page.items[1].episodeId)
        assertEquals(35000L,page.items[0].positionMs)
        assertEquals(120000L,page.items[0].durationMs)
        assertEquals(1000L,page.items[0].updatedAtMs)
        assertEquals("https://p3-sign.example/a.jpg",page.items[0].cover)
        assertEquals(false,page.hasMore)
        assertEquals(2L,page.total)
    }
}
