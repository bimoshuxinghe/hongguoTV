// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRegisterTest {
    @Test
    fun registerQueryHasFortyFingerprintParams() {
        val q=DeviceRegister.registerQuery("abc-cdid","0123abcd",1_760_000_000_000)
        assertEquals(40,q.size)
        assertEquals("_rticket",q.keys.first())
        assertEquals("version_name",q.keys.last())
        assertEquals("abc-cdid",q["cdid"])
        assertEquals("0123abcd",q["openudid"])
        assertEquals("8662",q["aid"])
        assertEquals("a",q["tt_data"])
        assertEquals("1760000000",q["ts"])
        assertEquals("1760000000",q["first_launch_timestamp"])
        assertEquals("73932",q["version_code"])
        // query 指纹必须覆盖静态档案的旧 cdid/openudid；device_id（已激活上下文）保留
        val merged=VendorConstants.device+q
        assertEquals("abc-cdid",merged["cdid"])
        assertEquals("0123abcd",merged["openudid"])
        assertEquals(VendorConstants.defaultDevice()["device_id"],merged["device_id"])
    }

    @Test
    fun applogJsonHasFortySevenHeaderFields() {
        val now=1_760_000_000_000L
        val json=DeviceRegister.applogRegisterJson(DeviceRegister.Identity("c","o","cu","r"),now)
        assertEquals("ss_app_log",json.getString("magic_tag"))
        assertEquals(now,json.getLong("_gen_time"))
        val h=json.getJSONObject("header")
        assertEquals(47,h.length())
        assertEquals(now-12_000,h.getLong("apk_first_install_time"))
        assertEquals("c",h.getString("cdid"))
        assertEquals("o",h.getString("openudid"))
        assertEquals("cu",h.getString("clientudid"))
        assertEquals("r",h.getString("req_id"))
        assertEquals(73932,h.getInt("version_code"))
        assertEquals("红果短剧",h.getString("display_name"))
        assertTrue(h.getJSONObject("custom").has("web_ua"))
    }

    @Test
    fun parseRegisterPrefersStringForms() {
        val raw="{\"server_time\":1791016810,\"device_id\":2715158266032906,\"install_id\":2715158266282762,\"new_user\":1,\"device_id_str\":\"2715158266032906\",\"install_id_str\":\"2715158266282762\",\"device_token\":\"AAAY\"}"
        val p=DeviceRegister.parseRegister(raw,DeviceRegister.Identity("c","o","cu","r"),"1\$abc")
        assertEquals("2715158266032906",p.deviceId)
        assertEquals("2715158266282762",p.installId)
        assertEquals("c",p.cdid)
        assertEquals("o",p.openudid)
        assertEquals("1\$abc",p.ttreq)
    }

    @Test
    fun parseRegisterAcceptsNumericOnlyForms() {
        val p=DeviceRegister.parseRegister("{\"device_id\":123,\"install_id\":456}",DeviceRegister.Identity("c","o","cu","r"),"")
        assertEquals("123",p.deviceId)
        assertEquals("456",p.installId)
    }

    @Test
    fun silentRejectionAndErrorsSurface() {
        val rejected=runCatching { DeviceRegister.parseRegister("{\"device_id\":0}",DeviceRegister.Identity("c","o","cu","r"),"") }
        assertTrue(rejected.exceptionOrNull()?.message?.contains("device_id=0")==true)
        val illegal=runCatching { DeviceRegister.parseRegister("{\"code\":110,\"message\":\"ILLEGAL_ACCESS\"}",DeviceRegister.Identity("c","o","cu","r"),"") }
        assertTrue(illegal.exceptionOrNull()?.message?.contains("110")==true)
        val missing=runCatching { DeviceRegister.parseRegister("{\"server_time\":1}",DeviceRegister.Identity("c","o","cu","r"),"") }
        assertTrue(missing.exceptionOrNull()?.message?.contains("device_id")==true)
    }

    @Test
    fun freshIdentityShapes() {
        val id=DeviceRegister.freshIdentity()
        assertTrue(id.cdid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        assertTrue(id.openudid.matches(Regex("[0-9a-f]{16}")))
        assertTrue(id.clientudid!=id.cdid)
        assertTrue(id.reqId!=id.cdid)
    }

    @Test
    fun registerBodyWireShapeIsTtEncryptV5() {
        // 注册 body 的加密形态：TT-Encrypt V5（magic + salt + iv + AES-CBC），解密必须还原 AppLog JSON
        val identity=DeviceRegister.Identity("cdid-x","openudid-y","clientudid-z","reqid-w")
        val now=1_760_000_000_000L
        val plain=DeviceRegister.applogRegisterJson(identity,now).toString().toByteArray(Charsets.UTF_8)
        val encrypted=TtEncryptV5.encrypt(plain)
        for((index,expected) in listOf(0x74,0x63,0x05,0x10).withIndex()) assertEquals(expected,encrypted[index].toInt() and 255)
        val decoded=TtEncryptV5.decrypt(encrypted)
        val json=JSONObject(String(decoded.first,Charsets.UTF_8))
        assertEquals("ss_app_log",json.getString("magic_tag"))
        assertEquals("cdid-x",json.getJSONObject("header").getString("cdid"))
    }
}
