package cloud.mallne.dicentra.synapse.di

import cloud.mallne.dicentra.synapse.service.APIDBService
import cloud.mallne.dicentra.synapse.service.CatalystGenerator
import cloud.mallne.dicentra.synapse.service.DatabaseService
import cloud.mallne.dicentra.synapse.service.DiscoveryGenerator
import cloud.mallne.dicentra.synapse.service.ScopeService
import cloud.mallne.dicentra.synapse.statics.Serialization
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

@Module
@ComponentScan(
    "cloud.mallne.dicentra.synapse.di",
    "cloud.mallne.dicentra.synapse.service"
)
class AppModule

val DI = module {
    singleOf(::APIDBService)
    singleOf(::CatalystGenerator)
    factoryOf(::DatabaseService)
    singleOf(::DiscoveryGenerator)
    singleOf(::ScopeService)
    single { Serialization() }
}