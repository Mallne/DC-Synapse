package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.aviator.core.plugins.AviatorPluginInstance
import cloud.mallne.dicentra.aviator.plugin.adapter.json.JsonAdapter
import cloud.mallne.dicentra.aviator.plugin.adapter.xml.XmlAdapter

/** Built-in Aviator wire adapters are part of Catalyst's execution runtime. */
internal class JsonAdapterCatalystContribution : CatalystPluginContribution {
    override val identity: String = JsonAdapter.identity
    override val activateByDefault: Boolean = true
    override fun create(): AviatorPluginInstance = JsonAdapter.install { }
}

internal class XmlAdapterCatalystContribution : CatalystPluginContribution {
    override val identity: String = XmlAdapter.identity
    override val activateByDefault: Boolean = true
    override fun create(): AviatorPluginInstance = XmlAdapter.install { }
}
