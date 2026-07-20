package com.adachi.lockdown

import com.adachi.lockdown.vpn.IpPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpPacketTest {

    @Test
    fun `round trip build and parse`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = IpPacket.buildUdpPacket("10.0.2.2", "10.0.2.1", 53, 12345, payload)
        val parsed = IpPacket.parseUdp(packet)
        assertEquals("10.0.2.2", parsed!!.srcIp)
        assertEquals("10.0.2.1", parsed.dstIp)
        assertEquals(53, parsed.srcPort)
        assertEquals(12345, parsed.dstPort)
        assertEquals(5, parsed.payload.size)
        assertTrue(payload.contentEquals(parsed.payload))
    }

    @Test
    fun `rejects non udp and non ipv4`() {
        val tcp = IpPacket.buildUdpPacket("1.1.1.1", "2.2.2.2", 1, 2, byteArrayOf(0))
        tcp[9] = 6 // TCP
        assertNull(IpPacket.parseUdp(tcp))
        val v6 = ByteArray(40) { 0 }
        v6[0] = 0x60
        assertNull(IpPacket.parseUdp(v6))
    }

    @Test
    fun `rejects truncated packet`() {
        val packet = IpPacket.buildUdpPacket("1.1.1.1", "2.2.2.2", 1, 2, ByteArray(20) { it.toByte() })
        assertNull(IpPacket.parseUdp(packet, 10))
    }
}
