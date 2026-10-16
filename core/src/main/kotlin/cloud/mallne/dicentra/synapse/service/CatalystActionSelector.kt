package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.model.ServiceLocator
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/** Encodes the universal Aviator service locator; repeated selectors intentionally identify a group. */
object CatalystActionSelector {
    fun encode(locator: ServiceLocator): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(locator.toString().toByteArray(UTF_8))

    fun decode(selector: String): ServiceLocator? = runCatching {
        val value = Base64.getUrlDecoder().decode(selector).toString(UTF_8)
        require(value.isNotBlank() && '>' in value)
        ServiceLocator(value).also { require(it.toString() == value && encode(it) == selector) }
    }.getOrNull()
}
