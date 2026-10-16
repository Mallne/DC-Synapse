package cloud.mallne.dicentra.synapse.service

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Fail-closed URL policy for provider destinations declared by trusted OpenAPI documents. */
internal class CatalystOutboundPolicy(
    allowedHosts: List<String>,
    private val resolveHost: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    private val approvedHosts = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(allowedHosts.map(::normalizeHost))
    }

    init {
        require(approvedHosts.size == allowedHosts.size) { "Catalyst allowed_hosts contains duplicate hosts" }
    }

    fun validate(serverUrls: List<String>, allowServiceDefinitionHosts: Boolean = false) {
        require(serverUrls.isNotEmpty()) { "Provider OpenAPI definition has no server URL" }
        serverUrls.forEach { serverUrl ->
            val uri = try {
                URI(serverUrl)
            } catch (cause: Exception) {
                throw IllegalArgumentException("Provider server URL is invalid", cause)
            }
            require(uri.scheme.equals("https", ignoreCase = true)) { "Provider URLs must use HTTPS" }
            require(uri.rawUserInfo == null) { "Provider URLs must not embed user credentials" }
            require(uri.port == -1 || uri.port == 443) { "Provider URLs must use the standard HTTPS port" }
            val host = uri.host ?: throw IllegalArgumentException("Provider server URL has no valid host")
            if (allowServiceDefinitionHosts) approvedHosts.add(normalizeHost(host))
            resolvePublicHost(host)
        }
    }

    /** Used by the HTTP engine at connection time; the returned addresses are the ones it connects to. */
    fun resolvePublicHost(host: String): List<InetAddress> {
        require(normalizeHost(host) in approvedHosts) {
            "Provider host is neither deployment-allowlisted nor approved by a public service definition"
        }
        val addresses = try {
            resolveHost(host)
        } catch (cause: Exception) {
            throw IllegalArgumentException("Provider hostname could not be resolved", cause)
        }
        require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) {
            "Provider hostname resolves to a non-public network address"
        }
        return addresses
    }

    private fun isPublicAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress &&
                !address.isLoopbackAddress &&
                !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress &&
                !address.isMulticastAddress &&
                // IPv6 unique-local (fc00::/7) is not reported as site-local by every JDK.
                !(address is java.net.Inet6Address && address.address[0].toInt() and 0xfe == 0xfc)

    private fun normalizeHost(host: String): String {
        val normalized = host.trim().trimEnd('.').lowercase()
        require(normalized.isNotEmpty() && '*' !in normalized && '/' !in normalized) {
            "Catalyst allowlist entries must be exact hostnames or IP addresses"
        }
        if (':' in normalized) {
            val ipv6 = normalized.removeSurrounding("[", "]")
            require('%' !in ipv6) { "Scoped IPv6 addresses are not valid allowlist entries" }
            return InetAddress.getByName(ipv6).hostAddress.lowercase()
        }
        return IDN.toASCII(normalized)
    }
}
