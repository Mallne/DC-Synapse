package cloud.mallne.dicentra.synapse.service

import org.apache.hc.client5.http.DnsResolver
import java.net.InetAddress

/** Resolves only exact allowlisted hosts and returns those vetted addresses to Apache for connection. */
internal class CatalystDnsResolver(private val policy: CatalystOutboundPolicy) : DnsResolver {
    override fun resolve(host: String): Array<InetAddress> =
        policy.resolvePublicHost(host).toTypedArray()

    override fun resolveCanonicalHostname(host: String): String = host
}
