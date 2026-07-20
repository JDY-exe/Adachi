package com.adachi.lockdown.vpn

import java.nio.ByteBuffer

/**
 * Minimal IPv4/UDP packet parsing and crafting for the VPN tunnel.
 * Pure Kotlin, unit-testable.
 */
object IpPacket {

    const val PROTOCOL_UDP = 17

    data class UdpDatagram(
        val srcIp: String,
        val dstIp: String,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    /** Parse an IPv4 packet; returns the UDP datagram or null if not IPv4+UDP. */
    fun parseUdp(packet: ByteArray, length: Int = packet.size): UdpDatagram? {
        if (length < 28) return null
        val version = packet[0].toInt() shr 4 and 0xF
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (length < ihl + 8) return null
        if (packet[9].toInt() and 0xFF != PROTOCOL_UDP) return null
        val totalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (totalLen > length || totalLen < ihl + 8) return null

        val srcIp = ipToString(packet, 12)
        val dstIp = ipToString(packet, 16)
        val buf = ByteBuffer.wrap(packet, ihl, totalLen - ihl)
        val srcPort = buf.short.toInt() and 0xFFFF
        val dstPort = buf.short.toInt() and 0xFFFF
        val udpLen = buf.short.toInt() and 0xFFFF
        buf.short // checksum
        val payloadLen = minOf(udpLen - 8, buf.remaining())
        if (payloadLen < 0) return null
        val payload = ByteArray(payloadLen)
        buf.get(payload)
        return UdpDatagram(srcIp, dstIp, srcPort, dstPort, payload)
    }

    /** Build a full IPv4+UDP packet (IP checksum computed; UDP checksum zeroed — legal in IPv4). */
    fun buildUdpPacket(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)
        out[0] = 0x45                                   // version 4, IHL 5
        out[1] = 0                                      // DSCP
        out[2] = (totalLen shr 8).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[4] = 0; out[5] = 0                          // identification
        out[6] = 0x40; out[7] = 0                       // DF flag
        out[8] = 64                                     // TTL
        out[9] = PROTOCOL_UDP.toByte()
        writeIp(out, 12, srcIp)
        writeIp(out, 16, dstIp)
        val csum = ipChecksum(out, 0, 20)
        out[10] = (csum shr 8).toByte()
        out[11] = (csum and 0xFF).toByte()

        out[20] = (srcPort shr 8).toByte()
        out[21] = (srcPort and 0xFF).toByte()
        out[22] = (dstPort shr 8).toByte()
        out[23] = (dstPort and 0xFF).toByte()
        out[24] = (udpLen shr 8).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        out[26] = 0; out[27] = 0                        // UDP checksum: none
        payload.copyInto(out, 28)
        return out
    }

    private fun ipToString(packet: ByteArray, offset: Int): String =
        (0 until 4).joinToString(".") { (packet[offset + it].toInt() and 0xFF).toString() }

    private fun writeIp(out: ByteArray, offset: Int, ip: String) {
        ip.split('.').forEachIndexed { i, part -> out[offset + i] = part.toInt().toByte() }
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.toInt().inv() and 0xFFFF
    }
}
