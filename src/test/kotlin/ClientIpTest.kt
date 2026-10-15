package com.milinko

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientIpTest {

    @Test
    fun `the address Cloudflare saw wins over a forged X-Forwarded-For`() {
        assertEquals(
            "109.245.225.100",
            clientIpFrom("109.245.225.100", "203.0.113.7,109.245.225.100, 162.158.41.220, 10.198.24.149", "localhost")
        )
    }

    @Test
    fun `without Cloudflare the entry of the nearest proxy is used, never the client's own`() {
        assertEquals("10.0.0.5", clientIpFrom(null, "203.0.113.7, 10.0.0.5", "localhost"))
        assertEquals("127.0.0.1", clientIpFrom(" ", null, "127.0.0.1"))
    }
}
