package me.malm.realmdo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    @Test
    fun parsesTheWebUisLink() {
        val p = Pairing.parse("realmdo://pair?url=https%3A%2F%2Frealmdo.example.org&token=rdo_abc123")
        assertEquals(Pairing("https://realmdo.example.org", "rdo_abc123"), p)
    }

    @Test
    fun trimsATrailingSlash() {
        val p = Pairing.parse("realmdo://pair?url=http%3A%2F%2F10.0.2.2%3A3000%2F&token=rdo_x")
        assertEquals("http://10.0.2.2:3000", p?.baseUrl)
    }

    @Test
    fun rejectsAnythingElse() {
        assertNull(Pairing.parse(null))
        assertNull(Pairing.parse("not a uri at all ://"))
        assertNull(Pairing.parse("https://realmdo.example.org/pair?url=x&token=rdo_x"))
        assertNull(Pairing.parse("realmdo://other?url=https%3A%2F%2Fa&token=rdo_x"))
        assertNull(Pairing.parse("realmdo://pair?url=https%3A%2F%2Fa"))
        assertNull(Pairing.parse("realmdo://pair?token=rdo_x"))
        assertNull(Pairing.parse("realmdo://pair?url=ftp%3A%2F%2Fa&token=rdo_x"))
        assertNull(Pairing.parse("realmdo://pair?url=https%3A%2F%2Fa&token=pub_x"))
    }
}
