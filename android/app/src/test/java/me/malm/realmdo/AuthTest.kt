package me.malm.realmdo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class AuthTest {
    @Test
    fun homeIsTheOriginOfWhatWasTypedOrScanned() {
        assertEquals("https://home.example.eu", Auth.home("home.example.eu"))
        assertEquals("https://home.example.eu", Auth.home("  Home.Example.EU/ "))
        assertEquals("https://home-a3f.w1.yourrealm.me", Auth.home("https://home-a3f.w1.yourrealm.me/apps?x=1"))
        assertEquals("https://home.example.eu:8443", Auth.home("home.example.eu:8443"))
    }

    @Test
    fun homeRefusesWhatIsNotAnHttpsOrigin() {
        assertNull(Auth.home(""))
        assertNull(Auth.home("http://home.example.eu"))
        assertNull(Auth.home("realm://enroll?url=https%3A%2F%2Fhome.example.eu&token=t"))
        assertNull(Auth.home("https://user@home.example.eu"))
        assertNull(Auth.home("not a host at all"))
    }

    @Test
    fun challengeIsS256OfTheVerifier() {
        // RFC 7636, appendix B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Auth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val v = Auth.verifier()
        assertEquals(43, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun authorizeUrlCarriesTheAppAndPkce() {
        val url = Auth.authorizeUrl("https://home.example.eu/api/auth/authorize", "c1", "s1", "ch", "Pixel 9")
        val query = URI(url).query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("code", query["response_type"])
        assertEquals("c1", query["client_id"])
        assertEquals("me.malm.realmdo:/callback", query["redirect_uri"])
        assertEquals("s1", query["state"])
        assertEquals("ch", query["code_challenge"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("realmdo", query["app"])
        assertEquals("Pixel 9", query["device_name"])
        assertTrue(Auth.authorizeUrl("https://h/a?x=1", "c", "s", "ch", "d").startsWith("https://h/a?x=1&response_type="))
    }

    @Test
    fun redirectNeedsOurPathAndThisAttemptsState() {
        assertEquals(Auth.Redirect.Code("abc"), Auth.redirect("me.malm.realmdo:/callback?code=abc&state=s1", "s1"))
        assertEquals(
            Auth.Redirect.Failed("access_denied"),
            Auth.redirect("me.malm.realmdo:/callback?error=access_denied&state=s1", "s1"),
        )
        assertNull(Auth.redirect("me.malm.realmdo:/callback?code=abc&state=other", "s1"))
        assertNull(Auth.redirect("me.malm.realmdo:/callback?code=abc&state=", ""))
        assertNull(Auth.redirect("me.malm.realmdo:/elsewhere?code=abc&state=s1", "s1"))
        assertNull(Auth.redirect("realmdo://pair?url=x&token=rdo_x", "s1"))
        assertNull(Auth.redirect(null, "s1"))
    }

    @Test
    fun tokensTakeTheApiServiceAndTheExpiry() {
        val body = """{"access_token":"realm_at_1","refresh_token":"realm_rt_1","token_type":"Bearer","expires_in":900,
            "app":{"id":"realmdo-abc","name":"realmdo"},"services":{"api":"https://realmdo-abc-api.w1.example.eu/"}}"""
        val t = Auth.Tokens.from(JSONObject(body), 1_000)
        assertEquals(Auth.Tokens("realm_at_1", "realm_rt_1", 901_000, "https://realmdo-abc-api.w1.example.eu"), t)
    }

    @Test
    fun onlyInvalidGrantDisconnects() {
        val ok = """{"access_token":"a","refresh_token":"r","expires_in":900,"services":{"api":"https://x"}}"""
        assertTrue(Auth.refreshed(200, ok, 0) is Auth.Refreshed.Done)
        assertEquals(Auth.Refreshed.Disconnected, Auth.refreshed(400, """{"error":"invalid_grant"}""", 0))
        assertEquals(Auth.Refreshed.TryLater, Auth.refreshed(400, """{"error":"access_denied"}""", 0))
        assertEquals(Auth.Refreshed.TryLater, Auth.refreshed(503, """{"error":"temporarily_unavailable"}""", 0))
        assertEquals(Auth.Refreshed.TryLater, Auth.refreshed(502, "<html>", 0))
    }
}
