package dev.tandem.app.ui.state

import dev.tandem.app.api.Api
import dev.tandem.app.api.ApiException
import dev.tandem.app.api.PROTOCOL_VERSION
import dev.tandem.app.api.isRevoked
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** One line under a plain-http address that leaves a private network. */
const val INSECURE_URL_WARNING =
    "Plain http: your password and this device's key cross the network unencrypted. Use https, or reach the server over Tailscale or your home network."

/**
 * Whether [url] is plain http to a host that isn't private by construction: not Tailscale (100.64.0.0/10, *.ts.net)
 * and not this device (loopback). A LAN address still counts as insecure: Wi-Fi elsewhere is not the home network.
 */
fun insecureUrl(url: String?): Boolean {
    val u = url?.trim() ?: return false
    if (!u.startsWith("http://", ignoreCase = true)) return false
    val host = hostOf(u.substring(7)).lowercase()
    if (host.isEmpty()) return false
    if (host == "localhost" || host == "::1" || host == "[::1]" || host.endsWith(".ts.net")) return false
    val ip = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
    if (ip.size == 4 && host.count { it == '.' } == 3) {
        if (ip[0] == 127) return false
        if (ip[0] == 100 && ip[1] in 64..127) return false   // 100.64.0.0/10, Tailscale's range
    }
    return true
}

private fun hostOf(rest: String): String {
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    return if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
}

/** How a check of a new server address ended. */
sealed interface ServerChange {
    data object Idle : ServerChange
    data object Checking : ServerChange
    data class Failed(val message: String) : ServerChange
    data class Done(val url: String) : ServerChange
}

/**
 * Checks that [url] is the same Tandem server under another address before this device switches to it: it must answer
 * /v1/server-info with this app's protocol ([anon]), and this device's key must work there ([keyed], GET
 * /v1/devices/me) as the same device ([deviceId], when known). Returns the outcome; nothing is stored here.
 */
suspend fun checkNewServer(url: String, deviceId: String?, anon: (String) -> Api, keyed: (String) -> Api): ServerChange {
    try {
        val info = anon(url).serverInfo()
        if (info.protocol.toString() != PROTOCOL_VERSION)
            return ServerChange.Failed("That server speaks another version of Tandem. Update the app or the server first.")
        val me = keyed(url).me()
        if (deviceId != null && me.id != deviceId)
            return ServerChange.Failed("That server knows this key as another device. Check the address.")
        return ServerChange.Done(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        return ServerChange.Failed(
            when {
                e.isRevoked -> "That server says this device was removed. Sign out and pair it again instead."
                e.status == 401 -> "That server doesn't know this device. Is it the same Tandem server, or another one?"
                e.status == 404 -> "There is no Tandem server at that address."
                else -> "The server answered with an error (${e.status}). Try again."
            },
        )
    } catch (e: IOException) {
        return ServerChange.Failed("Couldn't reach that address. Check it, and that this device is on the right network.")
    } catch (e: Exception) {
        return ServerChange.Failed("That address didn't answer like a Tandem server.")
    }
}
