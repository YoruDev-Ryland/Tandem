package dev.tandem.app.ui.state

import dev.tandem.app.api.Api
import dev.tandem.app.api.ApiException
import dev.tandem.app.model.DeviceSummary
import dev.tandem.app.model.ServerInfo
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test fun plainHttpOutsideTailscaleAndLoopbackIsInsecure() {
        assertTrue(insecureUrl("http://192.168.1.20:5100"))
        assertTrue(insecureUrl("http://tandem.example.com"))
        assertTrue(insecureUrl("HTTP://nas.local:5100/"))
        assertFalse(insecureUrl("https://tandem.example.com"))
        assertFalse(insecureUrl("http://100.101.102.103:5100"))       // Tailscale CGNAT
        assertFalse(insecureUrl("http://100.64.0.1"))
        assertFalse(insecureUrl("http://100.127.255.255"))
        assertTrue(insecureUrl("http://100.128.0.1"))                  // outside 100.64.0.0/10
        assertTrue(insecureUrl("http://100.63.0.1"))
        assertFalse(insecureUrl("http://nas.tail1234.ts.net:5100"))
        assertFalse(insecureUrl("http://127.0.0.1:5100"))
        assertFalse(insecureUrl("http://localhost:5100"))
        assertFalse(insecureUrl("http://[::1]:5100"))
        assertTrue(insecureUrl("http://user@evil.example/@100.64.0.1"))
        assertFalse(insecureUrl(null))
        assertFalse(insecureUrl(""))
    }

    private val unused: Api = Proxy.newProxyInstance(Api::class.java.classLoader, arrayOf(Api::class.java)) { _, m, _ ->
        throw UnsupportedOperationException(m.name)
    } as Api

    private fun server(protocol: Int = 1, meId: String = "d1", me: (() -> DeviceSummary)? = null) = object : Api by unused {
        override suspend fun serverInfo() = ServerInfo("Home", protocol, false)
        override suspend fun me() = me?.invoke() ?: DeviceSummary(meId, "Thor", "android", isCurrent = true)
    }

    @Test fun theNewAddressMustBeTheSameServerWithTheSameDevice() = runBlocking {
        val asked = mutableListOf<String>()
        val ok = checkNewServer("https://t.example", "https://old.example", "d1", { asked += "anon $it"; server() }, { asked += "keyed $it"; server() })
        assertEquals(ServerChange.Done("https://t.example"), ok)
        assertEquals(listOf("anon https://t.example", "keyed https://t.example"), asked)

        val other = checkNewServer("https://t.example", "https://old.example", "d1", { server() }, { server(meId = "d2") })
        assertTrue(other is ServerChange.Failed)
        val proto = checkNewServer("https://t.example", "https://old.example", "d1", { server(protocol = 2) }, { server() })
        assertTrue(proto is ServerChange.Failed)
        val unknown = checkNewServer("https://t.example", "https://old.example", "d1", { server() }, { server(me = { throw ApiException(401, "unauthorized", "no") }) })
        assertTrue((unknown as ServerChange.Failed).message.contains("doesn't know this device"))
        val offline = checkNewServer("https://t.example", "https://old.example", "d1", { throw java.io.IOException("down") }, { server() })
        assertTrue((offline as ServerChange.Failed).message.startsWith("Couldn't reach"))
    }

    @Test fun plainHttpToANewHostIsVerifiedWithoutTheKeyAndNeedsASecondPress() = runBlocking {
        val asked = mutableListOf<String>()
        val anon = { u: String -> asked += "anon $u"; server() }
        val keyed = { u: String -> asked += "keyed $u"; server() }
        val first = checkNewServer("http://tandem.example.com", "https://t.example", "d1", anon, keyed)
        assertEquals(ServerChange.ConfirmInsecure("http://tandem.example.com"), first)
        assertEquals("the key is not sent before the confirm", listOf("anon http://tandem.example.com"), asked)
        asked.clear()
        val second = checkNewServer("http://tandem.example.com", "https://t.example", "d1", anon, keyed, insecureConfirmed = true)
        assertEquals(ServerChange.Done("http://tandem.example.com"), second)
        assertEquals(listOf("anon http://tandem.example.com", "keyed http://tandem.example.com"), asked)
        // An address that isn't a Tandem server is refused before any confirm is asked for.
        val notTandem = checkNewServer("http://tandem.example.com", "https://t.example", "d1", { throw java.io.IOException("down") }, keyed)
        assertTrue(notTandem is ServerChange.Failed)
    }

    @Test fun plainHttpNeedsNoConfirmWhereTheKeyAlreadyGoesThatWay() {
        assertFalse(needsInsecureConfirm("http://192.168.1.20:5200", "http://192.168.1.20:5100"))
        assertTrue(needsInsecureConfirm("http://192.168.1.21:5100", "http://192.168.1.20:5100"))
        assertTrue(needsInsecureConfirm("http://192.168.1.20:5100", "https://192.168.1.20"))
        assertFalse(needsInsecureConfirm("https://new.example", "http://192.168.1.20:5100"))
        assertFalse(needsInsecureConfirm("http://nas.tail1234.ts.net:5100", "https://t.example"))
    }
}
