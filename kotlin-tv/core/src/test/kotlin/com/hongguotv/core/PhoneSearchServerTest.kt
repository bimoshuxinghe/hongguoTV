package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneSearchServerTest {
    private fun request(server: PhoneSearchServer,method: String="GET",path: String?=null,body: String="",headers: String="",host: String?=null): String {
        val uri=URI(server.url)
        return Socket("127.0.0.1",uri.port).use { socket ->
            socket.soTimeout=3000
            val bytes=body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(("$method ${path ?: uri.path} HTTP/1.1\r\nHost: ${host ?: uri.authority}\r\n"+headers+if(method=="POST") "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${bytes.size}\r\n\r\n" else "\r\n").toByteArray()+bytes)
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }
    @Test fun `phone form accepts one unicode search without reflecting it in HTML`() {
        val received=CountDownLatch(1); var query=""
        PhoneSearchServer("127.0.0.1",{ query=it;received.countDown() }).use { server ->
            val page=request(server)
            assertTrue(page.startsWith("HTTP/1.1 200")); assertTrue(page.contains("Cache-Control: no-store")); assertTrue(page.contains("form-action 'self'"))
            val value="测试 <script>alert(1)</script>"
            val reply=request(server,"POST",URI(server.url).path+"/submit","query="+URLEncoder.encode(value,"UTF-8"))
            assertTrue(reply.startsWith("HTTP/1.1 200")); assertFalse(reply.contains(value))
            assertTrue(received.await(2,TimeUnit.SECONDS)); assertEquals(value,query)
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
    @Test fun `wrong token host and cross site posts cannot submit`() {
        var submitted=false
        PhoneSearchServer("127.0.0.1",{ submitted=true }).use { server ->
            assertTrue(request(server,path="/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,host="evil.example").startsWith("HTTP/1.1 404"))
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query=test","Origin: http://evil.example\r\n").startsWith("HTTP/1.1 403"))
            assertFalse(submitted)
        }
    }
    @Test fun `secure mode and trailing slash origins still submit`() {
        // 部分手机浏览器的“HTTPS 优先/安全模式”会把来源标成 https 或带尾斜杠，不应误报“来源不匹配”
        val received=CountDownLatch(1)
        PhoneSearchServer("127.0.0.1",{ received.countDown() }).use { server ->
            val path=URI(server.url).path+"/submit"
            val authority=URI(server.url).rawAuthority
            assertTrue(request(server,"POST",path,"query=测试","Origin: https://$authority\r\n").startsWith("HTTP/1.1 200"))
        }
        assertTrue(received.await(2,TimeUnit.SECONDS))
        PhoneSearchServer("127.0.0.1",{}).use { server ->
            val path=URI(server.url).path+"/submit"
            val authority=URI(server.url).rawAuthority
            assertTrue(request(server,"POST",path,"query=测试","Origin: http://$authority/\r\n").startsWith("HTTP/1.1 200"))
        }
    }
    @Test fun `privacy mode and nonstandard origins still submit`() {
        // 无痕/隐私模式会发送 Origin: null 或直接省略 Origin，部分内置浏览器注入自定义 scheme，
        // 这些都无法判定为跨站，不应误报“来源不匹配”；会话安全由一次性 token 保证。
        val received=CountDownLatch(1)
        PhoneSearchServer("127.0.0.1",{ received.countDown() }).use { server ->
            val path=URI(server.url).path+"/submit"
            assertTrue(request(server,"POST",path,"query=测试","Origin: null\r\n").startsWith("HTTP/1.1 200"))
        }
        assertTrue(received.await(2,TimeUnit.SECONDS))
        PhoneSearchServer("127.0.0.1",{}).use { server ->
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query=测试","Origin: app://hongguo\r\n").startsWith("HTTP/1.1 200"))
        }
        PhoneSearchServer("127.0.0.1",{}).use { server ->
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query=测试","Sec-Fetch-Site: same-origin\r\n").startsWith("HTTP/1.1 200"))
        }
    }
    @Test fun `cross site fetch metadata is rejected even without origin`() {
        var submitted=false
        PhoneSearchServer("127.0.0.1",{ submitted=true }).use { server ->
            val path=URI(server.url).path+"/submit"
            assertTrue(request(server,"POST",path,"query=test","Sec-Fetch-Site: cross-site\r\n").startsWith("HTTP/1.1 403"))
            assertFalse(submitted)
        }
    }
    @Test fun `https origin on another host is still rejected`() {
        var submitted=false
        PhoneSearchServer("127.0.0.1",{ submitted=true }).use { server ->
            val path=URI(server.url).path+"/submit"
            assertTrue(request(server,"POST",path,"query=test","Origin: https://evil.example:443\r\n").startsWith("HTTP/1.1 403"))
            assertFalse(submitted)
        }
    }
    @Test fun `invalid and oversized input does not consume the session`() {
        PhoneSearchServer("127.0.0.1",{}).use { server ->
            for(body in listOf("query=","query="+"x".repeat(81),"query=a&query=b","query=hello%0Aworld","query=%zz")) {
                val response=request(server,"POST",URI(server.url).path+"/submit",body)
                assertTrue(response,response.startsWith("HTTP/1.1 400"))
            }
            assertTrue(request(server,"POST",URI(server.url).path+"/submit","query="+"x".repeat(4096)).startsWith("HTTP/1.1 413"))
            assertTrue(request(server).startsWith("HTTP/1.1 200"))
        }
    }
    @Test fun `closing or expiry makes a session unreachable`() {
        val closed=PhoneSearchServer("127.0.0.1",{}); closed.close()
        assertTrue(runCatching { request(closed) }.isFailure)
        val expired=CountDownLatch(1)
        PhoneSearchServer("127.0.0.1",{ fail("expired session submitted") },{ expired.countDown() },1).use { server ->
            assertTrue(expired.await(2,TimeUnit.SECONDS))
            assertTrue(runCatching { request(server) }.isFailure)
        }
    }
}
