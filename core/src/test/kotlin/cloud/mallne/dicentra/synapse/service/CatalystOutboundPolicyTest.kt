package cloud.mallne.dicentra.synapse.service

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CatalystOutboundPolicyTest {
    @Test
    fun `requires exact allowlisted host and HTTPS`() {
        val policy = publicHostPolicy("provider.example")
        policy.validate(listOf("https://provider.example/api"))

        assertFailsWith<IllegalArgumentException> {
            policy.validate(listOf("https://other.example/api"))
        }
        assertFailsWith<IllegalArgumentException> {
            policy.validate(listOf("http://provider.example/api"))
        }
        assertFailsWith<IllegalArgumentException> {
            policy.validate(listOf("https://user:secret@provider.example/api"))
        }
        assertFailsWith<IllegalArgumentException> {
            policy.validate(listOf("https://provider.example:8443/api"))
        }
    }

    @Test
    fun `empty allowlist and empty server list fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            publicHostPolicy("provider.example", emptyList()).validate(listOf("https://provider.example"))
        }
        assertFailsWith<IllegalArgumentException> {
            publicHostPolicy("provider.example").validate(emptyList())
        }
    }

    @Test
    fun `private IP destinations are rejected even when explicitly allowlisted`() {

        assertFailsWith<IllegalArgumentException> {
            CatalystOutboundPolicy(listOf("127.0.0.1")).validate(listOf("https://127.0.0.1/internal"))
        }
    }

    @Test
    fun `hostname resolving to any non-public address fails closed`() {
        val policy = CatalystOutboundPolicy(listOf("provider.example")) {
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.2"))
        }

        assertFailsWith<IllegalArgumentException> {
            policy.validate(listOf("https://provider.example/api"))
        }
    }

    @Test
    fun `connection-time DNS lookup rejects a rebinding answer`() {
        var resolution = 0
        val policy = CatalystOutboundPolicy(listOf("provider.example")) {
            resolution++
            listOf(InetAddress.getByName(if (resolution == 1) "8.8.8.8" else "10.0.0.2"))
        }
        policy.validate(listOf("https://provider.example/api"))

        assertFailsWith<IllegalArgumentException> {
            CatalystDnsResolver(policy).resolve("provider.example")
        }
    }

    private fun publicHostPolicy(host: String, allowed: List<String> = listOf(host)) =
        CatalystOutboundPolicy(allowed) { listOf(InetAddress.getByName("8.8.8.8")) }
}
