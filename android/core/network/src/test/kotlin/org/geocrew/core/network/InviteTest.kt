package org.geocrew.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteTest {
    @Test
    fun roundTripsThroughUri() {
        val i = Invite.of("geo.example.com:8443/crew", "abcd2345")!!
        assertEquals("https://geo.example.com:8443/crew", i.server)
        assertEquals("ABCD2345", i.code)
        assertEquals("geocrew://join?server=https%3A%2F%2Fgeo.example.com%3A8443%2Fcrew&code=ABCD2345", i.toUri())
        assertEquals(i, Invite.fromUri(i.toUri()))
    }

    @Test
    fun rejectsCleartextAndJunk() {
        assertNull(Invite.of("http://geo.example.com", "ABCD2345"))
        assertNull(Invite.of("geo.example.com", "AB"))
        assertNull(Invite.of("", "ABCD2345"))
        assertNull(Invite.fromUri("https://join?server=x&code=ABCD2345"))
        assertNull(Invite.fromUri("geocrew://other?server=geo.example.com&code=ABCD2345"))
        assertNull(Invite.find("今天天气不错"))
    }

    @Test
    fun findsInvitesInPastedText() {
        val shared = "加入同勘工作组「北山填图」\n服务器：https://geo.example.com\n邀请码：ABCD2345\n" +
            "已安装同勘可直接打开：geocrew://join?server=https%3A%2F%2Fgeo.example.com&code=ABCD2345"
        assertEquals(Invite("https://geo.example.com", "ABCD2345"), Invite.find(shared))
        assertEquals(Invite("https://geo.example.com", "WXYZ6789"), Invite.find("server: geo.example.com\ninvite code: wxyz6789"))
    }
}
