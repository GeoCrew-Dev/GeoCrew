package org.geocrew.android

import kotlin.test.Test
import kotlin.test.assertEquals

// A team server's address as typed on a phone, Chinese keyboards included.
class TeamAddressTest {
    @Test
    fun anAddressIsReadAsMeant() {
        assertEquals("https://geo.example.org", address(" geo.example.org "), "https when no scheme is given")
        assertEquals("http://10.0.2.2:8080", address("http://10.0.2.2:8080"))
        assertEquals("https://10.0.2.2:8443", address("HTTPS://10.0.2.2:8443"), "the scheme in lower case")
        assertEquals("http://10.0.2.2:8080", address("http\uFF1A\uFF0F\uFF0F10\u30020\u30022\u30022\uFF1A8080"), "full-width punctuation")
        assertEquals("https://geo.example.org:8443", address("geo.example.org\uFF1A\uFF18\uFF14\uFF14\uFF13"), "full-width digits")
    }
}
