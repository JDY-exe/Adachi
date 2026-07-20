package com.adachi.lockdown

import com.adachi.lockdown.vpn.DnsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCodecTest {

    /** Build a standard query for `name` (A record) with the given ID. */
    private fun buildQuery(id: Int, name: String, qtype: Int = 1): ByteArray {
        val out = mutableListOf<Byte>()
        out.add((id shr 8).toByte()); out.add((id and 0xFF).toByte())
        out.add(0x01); out.add(0x00)              // RD=1
        out.add(0); out.add(1)                    // qdcount=1
        out.add(0); out.add(0); out.add(0); out.add(0); out.add(0); out.add(0)
        for (label in name.split('.')) {
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0)
        out.add((qtype shr 8).toByte()); out.add((qtype and 0xFF).toByte())
        out.add(0); out.add(1)                    // qclass=IN
        return out.toByteArray()
    }

    @Test
    fun `parse basic query`() {
        val raw = buildQuery(0x1234, "Old.Reddit.com")
        val q = DnsCodec.parseQuery(raw)
        assertNotNull(q)
        assertEquals(0x1234, q!!.id)
        assertEquals("old.reddit.com", q.name)
        assertEquals(1, q.qtype)
    }

    @Test
    fun `parse rejects response packets`() {
        val raw = buildQuery(1, "example.com")
        raw[2] = (raw[2].toInt() or 0x80.toByte().toInt()).toByte() // set QR
        assertNull(DnsCodec.parseQuery(raw))
    }

    @Test
    fun `parse rejects truncated packets`() {
        val raw = buildQuery(1, "example.com")
        assertNull(DnsCodec.parseQuery(raw, 8))
        assertNull(DnsCodec.parseQuery(raw, raw.size - 3))
    }

    @Test
    fun `nxdomain echoes id and question, sets rcode 3`() {
        val raw = buildQuery(0xBEEF, "reddit.com")
        val q = DnsCodec.parseQuery(raw)!!
        val resp = DnsCodec.buildNxdomain(q)
        assertEquals(0xBEEF, DnsCodec.responseId(resp))
        assertEquals(resp.size, raw.size)
        // QR=1 and rcode=3
        assertTrue(resp[2].toInt() and 0x80 != 0)
        assertEquals(3, resp[3].toInt() and 0x0F)
        // question section identical
        assertTrue(resp.copyOfRange(12, resp.size).contentEquals(raw.copyOfRange(12, raw.size)))
        // and the response parses as a query-shaped name section is NOT expected; just check no crash
    }

    @Test
    fun `extract A records from response`() {
        // query for example.com + one answer: pointer name, A, 1.2.3.4
        val query = buildQuery(7, "example.com")
        val resp = query.copyOf(query.size + 16)
        resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte()       // standard response
        resp[7] = 1                                            // ancount=1
        var p = query.size
        resp[p++] = 0xC0.toByte(); resp[p++] = 0x0C            // name pointer to offset 12
        resp[p++] = 0; resp[p++] = 1                           // type A
        resp[p++] = 0; resp[p++] = 1                           // class IN
        resp[p++] = 0; resp[p++] = 0; resp[p++] = 0; resp[p++] = 60  // ttl
        resp[p++] = 0; resp[p++] = 4                           // rdlength
        resp[p++] = 1; resp[p++] = 2; resp[p++] = 3; resp[p] = 4
        val ips = DnsCodec.extractAnswerIps(resp)
        assertEquals(listOf("1.2.3.4"), ips)
    }

    @Test
    fun `extract returns empty on garbage`() {
        assertTrue(DnsCodec.extractAnswerIps(byteArrayOf(1, 2, 3)).isEmpty())
    }
}
