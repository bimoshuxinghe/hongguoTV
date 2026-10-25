package com.hongguotv.core
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
class SignerTest {
    @Test fun matchesPinnedJavaScriptOracleAcrossTimestampVariants() {
        val rows = JSONArray(javaClass.getResource("/signing.json")!!.readText())
        // medusa 由设备档案派生（version_name + device_id）：oracle 抓包于 71332 档案，断言前临时切回，跑完复位
        val device = VendorConstants.device
        val oracleDevice = linkedMapOf(
            "device_id" to "1905892595378490", "version_name" to "7.1.3.32")
        for (i in 0 until rows.length()) {
            val row=rows.getJSONObject(i); val ts=row.getInt("ts"); val url=row.getString("url"); val query=url.substringAfter('?')
            val body=if(row.isNull("body")) null else row.getString("body").toByteArray()
            var seed=17L
            val signer=Signer({ts.toLong()*1000}) { seed=(seed*1664525+1013904223) and 0xffffffffL; seed/4294967296.0 }
            assertEquals("branch $i",0,signer.branch(query,body,ts))
            assertEquals("hash $i",row.getString("hash"),signer.hashF13(sm3(query.toByteArray()),body?.let{digest("MD5",it)}?:ByteArray(16),ts).hex())
            assertEquals("gorgon $i",row.getString("gorgon"),signer.gorgon(query,body,ts,42131))
            assertEquals("helios $i",row.getString("helios"),signer.helios(ts))
            device.putAll(oracleDevice)
            try { assertEquals("medusa $i",row.getString("medusa"),signer.medusa(url,body,ts)) }
            finally { device.clear(); device.putAll(com.hongguotv.core.VendorConstants.defaultDevice()) }
        }
    }
}
