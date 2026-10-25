// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 内容域名（hongguoduanju.com、fqnovel.com、douyinvod.com 等字节系 CDN）同时发布
 * A 与 AAAA 记录。新版 64 位电视普遍默认开启 IPv6，系统按 RFC 6724 会把 IPv6 地址
 * 排在前面；一旦路由器的 IPv6 上游不通（很常见），OkHttp 会对每个失联的 IPv6 地址
 * 等满连接超时后才轮到 IPv4，请求容易撞上整体超时，表现为首页/搜索"加载不出来数据"。
 * 旧款 32 位电视的系统多未启用 IPv6，直连 IPv4 反而正常。
 *
 * 这里把 IPv4 结果排到最前（组内保持系统返回的原顺序），让首个连接尝试直接走 IPv4，
 * IPv6 仅在没有 IPv4 记录时使用，因此纯 IPv6 网络不受影响。
 */
object Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        ipv4First(Dns.SYSTEM.lookup(hostname))

    /** 稳定排序：Inet4Address 在前，其余（IPv6 等）在后；同组内顺序不变 */
    internal fun ipv4First(addresses: List<InetAddress>): List<InetAddress> =
        addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
}
