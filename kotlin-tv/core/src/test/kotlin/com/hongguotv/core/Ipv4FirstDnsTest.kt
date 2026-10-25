package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class Ipv4FirstDnsTest {
    private fun v4(vararg octets: Int) = InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())
    private fun v6(vararg groups: Int) = InetAddress.getByAddress(ByteArray(16).also { bytes ->
        groups.forEachIndexed { index, value -> bytes[index * 2] = (value shr 8).toByte(); bytes[index * 2 + 1] = value.toByte() }
    })

    @Test fun ipv4AddressesAreMovedAheadOfIpv6() {
        val input = listOf(v6(0x2001, 0xdb8), v4(1, 2, 3, 4), v6(0xfe80), v4(5, 6, 7, 8))
        val ordered = Ipv4FirstDns.ipv4First(input)
        assertTrue(ordered[0] is java.net.Inet4Address)
        assertTrue(ordered[1] is java.net.Inet4Address)
        assertFalse(ordered.drop(2).any { it is java.net.Inet4Address })
    }

    @Test fun relativeOrderInsideEachGroupIsPreserved() {
        val first = v4(9, 9, 9, 9); val second = v4(8, 8, 8, 8)
        val v6a = v6(1); val v6b = v6(2)
        assertEquals(listOf(first, second, v6a, v6b), Ipv4FirstDns.ipv4First(listOf(v6a, v6b, first, second)))
        assertEquals(listOf(second, first, v6b, v6a), Ipv4FirstDns.ipv4First(listOf(v6b, v6a, second, first)))
    }

    @Test fun ipv4OnlyAndIpv6OnlyListsStayUnchanged() {
        val only4 = listOf(v4(10, 0, 0, 1), v4(10, 0, 0, 2))
        val only6 = listOf(v6(0x20), v6(0x21))
        assertEquals(only4, Ipv4FirstDns.ipv4First(only4))
        assertEquals(only6, Ipv4FirstDns.ipv4First(only6))
    }

    @Test fun lookupOfRealHostnameStillResolves() {
        val resolved = Ipv4FirstDns.lookup("localhost")
        assertFalse(resolved.isEmpty())
    }
}
