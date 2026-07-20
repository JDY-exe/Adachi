package com.adachi.lockdown.vpn

import java.nio.ByteBuffer

/**
 * Minimal DNS message codec — enough to parse a query's first question and to
 * build an NXDOMAIN response. Pure Kotlin, fully unit-testable.
 *
 * Only handles uncompressed names in the question section (standard for queries).
 */
object DnsCodec {

    data class Query(
        val id: Int,
        val name: String,       // fully-qualified, lowercased, no trailing dot
        val qtype: Int,
        val raw: ByteArray,     // original message bytes (for relaying)
    )

    /** Parse a DNS query. Returns null if malformed or not a query. */
    fun parseQuery(msg: ByteArray, length: Int = msg.size): Query? {
        if (length < 12) return null
        val buf = ByteBuffer.wrap(msg, 0, length)
        val id = buf.short.toInt() and 0xFFFF
        val flags = buf.short.toInt() and 0xFFFF
        if (flags and 0x8000 != 0) return null       // QR=1 -> this is a response
        val qdcount = buf.short.toInt() and 0xFFFF
        buf.short // ancount
        buf.short // nscount
        buf.short // arcount
        if (qdcount < 1) return null
        val name = readName(buf, length) ?: return null
        if (buf.remaining() < 4) return null
        val qtype = buf.short.toInt() and 0xFFFF
        buf.short // qclass
        return Query(id, name, qtype, msg.copyOf(length))
    }

    private fun readName(buf: ByteBuffer, limit: Int): String? {
        val sb = StringBuilder()
        while (true) {
            if (buf.position() >= limit) return null
            val len = buf.get().toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 != 0) return null         // compression not allowed in question
            if (buf.remaining() < len) return null
            val label = ByteArray(len)
            buf.get(label)
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(label, Charsets.US_ASCII).lowercase())
        }
        return sb.toString()
    }

    /** Build an NXDOMAIN response (rcode=3, no answers) echoing the question. */
    fun buildNxdomain(query: Query): ByteArray {
        val req = query.raw
        val out = req.copyOf(req.size)
        // flags: QR=1, RD copied from request, RA=1, rcode=3
        val reqFlags = ((req[2].toInt() and 0xFF) shl 8) or (req[3].toInt() and 0xFF)
        val rd = reqFlags and 0x0100
        val flags = 0x8000 or rd or 0x0080 or 0x0003
        out[2] = (flags shr 8).toByte()
        out[3] = (flags and 0xFF).toByte()
        // ancount/nscount/arcount = 0
        out[6] = 0; out[7] = 0
        out[8] = 0; out[9] = 0
        out[10] = 0; out[11] = 0
        return out
    }

    /** Extract the DNS ID from a response message (for correlating upstream replies). */
    fun responseId(msg: ByteArray, length: Int = msg.size): Int? {
        if (length < 2) return null
        return ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF)
    }

    /** Extract A/AAAA record IPs from a response (for domain->IP bookkeeping). Best effort. */
    fun extractAnswerIps(msg: ByteArray, length: Int = msg.size): List<String> {
        if (length < 12) return emptyList()
        val buf = ByteBuffer.wrap(msg, 0, length)
        buf.short // id
        buf.short // flags
        val qd = buf.short.toInt() and 0xFFFF
        val an = buf.short.toInt() and 0xFFFF
        buf.short // ns
        buf.short // ar
        repeat(qd) { if (skipName(buf, length) == null) return emptyList(); if (buf.remaining() < 4) return emptyList(); buf.int }
        val ips = mutableListOf<String>()
        repeat(an) {
            if (skipName(buf, length) == null) return ips
            if (buf.remaining() < 10) return ips
            val type = buf.short.toInt() and 0xFFFF
            buf.short // class
            buf.int   // ttl
            val rdlen = buf.short.toInt() and 0xFFFF
            if (buf.remaining() < rdlen) return ips
            val rdata = ByteArray(rdlen)
            buf.get(rdata)
            when {
                type == 1 && rdlen == 4 ->
                    ips.add(rdata.joinToString(".") { (it.toInt() and 0xFF).toString() })
                type == 28 && rdlen == 16 -> {
                    val sb = StringBuilder()
                    for (i in rdata.indices step 2) {
                        if (sb.isNotEmpty()) sb.append(':')
                        sb.append(((rdata[i].toInt() and 0xFF) shl 8 or (rdata[i + 1].toInt() and 0xFF)).toString(16))
                    }
                    ips.add(sb.toString())
                }
            }
        }
        return ips
    }

    /** Skip a possibly-compressed name. Returns Unit on success, null on truncation. */
    private fun skipName(buf: ByteBuffer, limit: Int): Unit? {
        while (true) {
            if (buf.position() >= limit) return null
            val len = buf.get().toInt() and 0xFF
            when {
                len == 0 -> return Unit
                len and 0xC0 == 0xC0 -> {            // pointer: 2 bytes total
                    if (buf.position() >= limit) return null
                    buf.get()
                    return Unit
                }
                buf.remaining() < len -> return null
                else -> buf.position(buf.position() + len)
            }
        }
    }
}
