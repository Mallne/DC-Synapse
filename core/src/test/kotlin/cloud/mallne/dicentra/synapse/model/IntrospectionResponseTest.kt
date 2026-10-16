package cloud.mallne.dicentra.synapse.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class IntrospectionResponseTest {
    @Test
    fun `realm role matches configured group path`() {
        val response = Json.decodeFromString<IntrospectionResponse>(
            """
            {
              "exp": 2000000000,
              "iat": 1900000000,
              "scope": "openid email profile",
              "acr": "1",
              "email_verified": true,
              "name": "Test User",
              "preferred_username": "test-user",
              "active": true,
              "token_type": "Bearer",
              "email": "test@example.invalid",
              "given_name": "Test",
              "family_name": "User",
              "client_id": "areaassist-client",
              "realm_access": {
                "roles": ["DC-Codex-Superadmin"]
              }
            }
            """.trimIndent()
        )

        val user = response.toUser(
            Security(
                enabled = true,
                groups = SecurityGroups(
                    user = "/DC-Codex-User",
                    superadmin = "/DC-Codex-Superadmin",
                ),
            ),
        )

        assertTrue(user.access.superAdmin)
    }
}
