// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A one-use, short-lived LAN form. No cookies, platform credentials or external resources. */
class PhoneSearchServer(
    address: String,
    private val onQuery: (String)->Unit,
    private val onExpired: ()->Unit = {},
    ttlMillis: Long = 300_000
): Closeable {
    private val stopped=AtomicBoolean(false)
    private val consumed=AtomicBoolean(false)
    private val localAddress=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }.hostAddress
    private val listener=ServerSocket(0,4).apply { soTimeout=500 }
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,300_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="$localAddress:${listener.localPort}"
    val url="http://$host/$token"
    private val thread=Thread({ serve() },"hongguotv-phone-input").apply { isDaemon=true }
    init {
        thread.start()
    }
    override fun close() { stopped.set(true); runCatching { listener.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val client=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                client.use { socket ->
                    socket.soTimeout=2000
                    try { handle(socket) } catch(_: Exception) { /* Discard malformed or disconnected requests. */ }
                }
            }
        } catch(_: Exception) { /* Closing the dialog interrupts accept(). */ }
        finally {
            val expired=!stopped.get() && !consumed.get()
            close()
            if(expired) onExpired()
        }
    }
    private fun line(input: InputStream, max: Int): String {
        val out=ByteArrayOutputStream()
        while(out.size()<max) {
            val next=input.read()
            if(next<0) throw java.io.EOFException()
            if(next==10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw java.io.IOException("Header too large")
    }
    private fun handle(socket: Socket) {
        if(!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input=socket.getInputStream().buffered()
        val request=line(input,1024).split(' ')
        if(request.size!=3) return
        val headers=mutableMapOf<String,String>()
        var bytes=0
        while(true) {
            val next=line(input,4096); bytes+=next.length+2
            if(bytes>8192) return respond(socket,431,"请求过大")
            if(next.isEmpty()) break
            val at=next.indexOf(':'); if(at<=0) return respond(socket,400,"请求格式错误")
            val key=next.substring(0,at).lowercase(Locale.ROOT)
            if(headers.put(key,next.substring(at+1).trim())!=null) return respond(socket,400,"重复请求头")
        }
        if(stopped.get() || consumed.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已失效，请在电视上重新打开手机输入")
        if(headers["host"]!=host || request[1] !in listOf("/$token","/$token/submit")) return respond(socket,404,"页面不存在")
        if(request[0]=="GET" && request[1]=="/$token") {
            return respond(socket,200,"""<h1>在电视上搜索</h1><p>输入剧名，电视会按当前选择的漫剧或短剧分类搜索。</p><form method="post" action="/$token/submit"><input name="query" maxlength="80" required autofocus placeholder="输入剧名或关键词" autocomplete="off"><button>发送到电视</button></form><p>页面仅在电视的手机输入窗口打开期间有效，最长 5 分钟。</p>""")
        }
        if(request[0]!="POST" || request[1]!="/$token/submit") return respond(socket,405,"不支持的操作")
        if(!sameSite(headers["origin"])) return respond(socket,403,"来源不匹配")
        if(headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';')!="application/x-www-form-urlencoded") return respond(socket,400,"请求格式错误")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..4096 } ?: return respond(socket,413,"输入内容过长")
        val body=ByteArray(length); var offset=0
        while(offset<body.size) { val n=input.read(body,offset,body.size-offset); if(n<0) return; offset+=n }
        val fields=String(body,Charsets.UTF_8).split('&').map { it.split('=',limit=2) }
        if(fields.size!=1 || fields[0].size!=2 || fields[0][0]!="query") return respond(socket,400,"请填写剧名")
        val query=runCatching { URLDecoder.decode(fields[0][1],"UTF-8").trim() }.getOrNull()
        if(query.isNullOrBlank() || query.length>80 || query.any { it.isISOControl() }) return respond(socket,400,"请输入 1—80 个字符的剧名")
        if(stopped.get() || System.nanoTime()>=deadline || !consumed.compareAndSet(false,true)) return respond(socket,410,"二维码已失效")
        try { respond(socket,200,"<h1>已发送到电视</h1><p>请回到电视查看搜索结果。</p>") }
        finally { close(); onQuery(query) }
    }

    /**
     * 只放行由本机表单页发起的提交：按 host:port（authority）比对而不是整串字符串。
     * 部分手机浏览器的“HTTPS 优先 / 安全上网”模式会把页面来源标成 https 或带尾斜杠，
     * 按整串比对会误伤成“来源不匹配”；authority 一致即同源，跨站来源依旧拒绝。
     */
    private fun sameSite(origin: String?)=sameSiteOrigin(origin,host,localAddress,listener.localPort)

    private fun respond(socket: Socket, code: Int, message: String) {
        val body="""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果 TV · 手机输入</title><style>body{font:18px sans-serif;background:#10131b;color:#f4f5f8;max-width:500px;margin:40px auto;padding:20px}input,button{box-sizing:border-box;width:100%;font:inherit;padding:16px;margin:10px 0;border-radius:8px}button{background:#ff634c;color:white;border:0}p{line-height:1.6}</style></head><body>$message</body></html>""".toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}

/** 只放行由本机页面发起的请求：按 host:port（authority）比对而不是整串字符串。
 *  部分手机浏览器的“HTTPS 优先 / 安全上网”模式会把页面来源标成 https 或带尾斜杠，
 *  整串比对会误伤成“来源不匹配”；authority 一致即同源，跨站来源依旧拒绝。 */
internal fun sameSiteOrigin(origin: String?,host: String,address: String,port: Int): Boolean {
    if(origin.isNullOrBlank()) return true
    if(origin.equals("http://$host",ignoreCase=true)) return true
    return runCatching {
        val uri=URI(origin)
        uri.host?.equals(address,ignoreCase=true)==true
            && (if(uri.port==-1) 80 else uri.port)==port
            && (uri.scheme.equals("http",true) || uri.scheme.equals("https",true))
    }.getOrDefault(false)
}
