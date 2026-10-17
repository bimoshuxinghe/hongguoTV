// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/** Short-lived LAN transfer. Uploaded records are validated here, but only the TV may apply them. */
class LibraryTransferServer(address: String,private val backup: String,private val onImport: (BackupData)->Unit,
    private val onExpired: ()->Unit={},ttlMillis: Long=300_000): Closeable {
    private val stopped=AtomicBoolean(false)
    private val expiry=Timer("hongguotv-transfer-expiry",true)
    private val local=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }
    private val listener=ServerSocket(0,4,local).apply { soTimeout=250 }
    @Volatile private var active: Socket?=null
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,300_000)*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="${local.hostAddress}:${listener.localPort}"
    val url="http://$host/$token"
    init {
        require(backup.toByteArray(Charsets.UTF_8).size<=LibraryBackup.MAX_BYTES)
        expiry.schedule(object: TimerTask() { override fun run()=expire() },ttlMillis.coerceIn(1,300_000))
        Thread({ serve() },"hongguotv-library-transfer").apply { isDaemon=true; start() }
    }
    private fun expire() { if(stopped.compareAndSet(false,true)) { close(); onExpired() } }
    override fun close() { stopped.set(true); expiry.cancel(); runCatching { listener.close() }; runCatching { active?.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<deadline) {
                val socket=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                active=socket
                socket.use { it.soTimeout=2000; if(!stopped.get()) runCatching { handle(it) } }
                active=null
            }
        } catch(_: Exception) { /* Closing the TV dialog interrupts accept/read. */ }
        finally { if(!stopped.get()) expire() else close() }
    }
    private fun line(input: InputStream,max: Int): String {
        val out=ByteArrayOutputStream()
        while(out.size()<max) {
            val next=input.read(); if(next<0) throw java.io.EOFException()
            if(next==10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw java.io.IOException("Header too large")
    }
    private fun handle(socket: Socket) {
        if(!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input=socket.getInputStream().buffered(); val request=line(input,1024).split(' ')
        if(request.size!=3) return
        val headers=mutableMapOf<String,String>(); var size=0
        while(true) {
            val next=line(input,4096); size+=next.length+2
            if(size>8192) return respond(socket,431,"请求过大")
            if(next.isEmpty()) break
            val at=next.indexOf(':'); if(at<=0) return respond(socket,400,"请求格式错误")
            if(headers.put(next.substring(0,at).lowercase(Locale.ROOT),next.substring(at+1).trim())!=null) return respond(socket,400,"重复请求头")
        }
        if(stopped.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已过期")
        if(headers["host"]!=host || request[1] !in listOf("/$token","/$token/download","/$token/import")) return respond(socket,404,"页面不存在")
        if(request[0]=="GET" && request[1]=="/$token") return respond(socket,200,page())
        if(request[0]=="GET" && request[1]=="/$token/download") {
            // 允许同会话内重复下载：部分手机浏览器会先探测再取文件，或用户需要重新下载；
            // 会话安全边界由一次性随机 token 和 5 分钟有效期保证，不依赖下载次数。
            return respond(socket,200,backup,"application/octet-stream","Content-Disposition: attachment; filename=\"hongguotv-library.json\"\r\n")
        }
        if(request[0]!="POST" || request[1]!="/$token/import") return respond(socket,405,"不支持的操作")
        if(!sameSiteRequest(headers,host,local.hostAddress,listener.localPort)) return respond(socket,403,"来源不匹配")
        if(headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';')!="application/json") return respond(socket,400,"请选择 JSON 备份文件")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..LibraryBackup.MAX_BYTES } ?: return respond(socket,413,"备份不能超过 2 MB")
        val bytes=ByteArray(length); var offset=0
        // A total deadline also bounds slow clients that keep individual reads below the socket timeout.
        val uploadDeadline=minOf(deadline,System.nanoTime()+10_000_000_000)
        while(offset<length) {
            if(stopped.get() || System.nanoTime()>=uploadDeadline) return respond(socket,408,"上传超时，请重试")
            val count=input.read(bytes,offset,minOf(8192,length-offset)); if(count<0) return; offset+=count
        }
        val data=runCatching { LibraryBackup.decode(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return respond(socket,400,"备份格式无效，请选择本应用导出的 JSON 文件")
        if(stopped.get() || System.nanoTime()>=deadline) return respond(socket,410,"二维码已过期")
        try { respond(socket,200,"已发送到电视，请用遥控器确认恢复。确认前不会更改记录。") }
        finally { close(); onImport(data) }
    }
    private fun page()=run {
        // 备份 JSON 直接嵌入页面文本框：下载被手机浏览器（如微信内置浏览器）拦截时，
        // 仍可复制文本保存为文件，或把文本粘贴到恢复框直接发回电视。
        val escaped=backup.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果 TV · 记录备份</title><style>body{font:18px sans-serif;background:#10131b;color:#f4f5f8;max-width:540px;margin:32px auto;padding:20px;line-height:1.6}a,button{display:block;background:#ff634c;color:white;border:0;border-radius:8px;padding:14px;text-align:center;text-decoration:none;font:inherit;margin:16px 0}input{max-width:100%;font:inherit}button{width:100%}textarea{box-sizing:border-box;width:100%;font:14px/1.5 monospace;color:#f4f5f8;background:#1a1e2a;border:1px solid #333a4d;border-radius:8px;padding:10px;margin:8px 0}h2{font-size:19px;margin:18px 0 4px}p{margin:8px 0}</style><h1>备份与恢复</h1><p>保存本机收藏、观看位置、稍后看、隐藏推荐、搜索记录和播放设置。文件仅在手机与电视之间传输，不包含平台账号。</p><a href="/$token/download" download>下载电视备份</a><p>若下载失败：可在下方复制备份内容，保存为 .json 文件；恢复时也支持直接粘贴。建议用系统浏览器（而非微信）打开本页。</p><h2>备份内容</h2><textarea id="backup" readonly rows="8" wrap="off">$escaped</textarea><button id="copy">复制备份内容</button><p id="copyStatus"></p><h2>从备份恢复</h2><p>方式一：选择备份文件；方式二：把备份 JSON 粘贴到下方直接发送。</p><input id="file" type="file" accept=".json,application/json"><button id="send">发送文件到电视确认</button><textarea id="paste" rows="6" placeholder="也可把备份 JSON 粘贴到这里"></textarea><button id="sendPaste">发送粘贴内容到电视确认</button><p id="status">最多 2 MB；需要在电视上确认后才会合并。</p><p>关闭电视窗口即失效，最长 5 分钟。</p><script nonce="$token">function sendJson(text,button){var status=document.getElementById('status');if(!text||!text.trim()){status.textContent='请先选择文件或粘贴备份内容';return}if(text.length>2097152){status.textContent='备份不能超过 2 MB';return}button.disabled=true;fetch('/$token/import',{method:'POST',headers:{'Content-Type':'application/json'},body:text}).then(function(r){return r.text().then(function(t){status.textContent=t;if(!r.ok)button.disabled=false})}).catch(function(){status.textContent='连接已失效，请在电视上重新打开二维码';button.disabled=false})}document.getElementById('copy').onclick=function(){var t=document.getElementById('backup'),s=document.getElementById('copyStatus');t.focus();t.select();var ok=false;try{ok=document.execCommand('copy')}catch(e){}s.textContent=ok?'已复制。可粘贴到备忘录、文件管理器或微信文件助手，另存为 .json 文件。':'复制失败，请在上方文本框长按后全选手动复制。'};document.getElementById('send').onclick=function(){var file=document.getElementById('file').files[0],status=document.getElementById('status'),button=this;if(!file){status.textContent='请先选择备份文件';return}if(file.size>2097152){status.textContent='备份不能超过 2 MB';return}button.disabled=true;var reader=new FileReader();reader.onerror=function(){status.textContent='文件读取失败';button.disabled=false};reader.onload=function(){sendJson(reader.result,button)};reader.readAsText(file)};document.getElementById('sendPaste').onclick=function(){sendJson(document.getElementById('paste').value,this)};</script></html>"""
    }
    private fun respond(socket: Socket,code: Int,message: String,type: String=if(message.startsWith("<!doctype")) "text/html; charset=utf-8" else "text/plain; charset=utf-8",extra: String="") {
        val body=message.toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-$token'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n$extra\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
