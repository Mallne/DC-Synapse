package cloud.mallne.dicentra.synapse.service

import cloud.mallne.dicentra.synapse.model.Configuration
import io.r2dbc.spi.ConnectionFactoryOptions
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.core.StdOutSqlLogger
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.koin.core.annotation.Factory

@Factory
class DatabaseService(config: Configuration) {
    val scm = Schema(config.data.schema)
    val dataConfig = config.data

    init {
        R2dbcDatabase.connect {
            setUrl("r2dbc:${dataConfig.url}?schema=${dataConfig.schema}")
            connectionFactoryOptions {
                option(ConnectionFactoryOptions.USER, dataConfig.user)
                option(ConnectionFactoryOptions.PASSWORD, dataConfig.password)
            }
        }
    }

    /**
     * Executes a suspended transaction within the database context, providing a DSL
     * for database operations. This function is a shorthand operator for invoking
     * `dbQuery` with the given transactional block.
     *
     * @param T the type of result produced by the provided transaction block.
     * @param block the transactional block to be executed. It operates within the
     *              context of the `Transaction`.
     * @return the result of the provided transaction block after execution.
     */
    suspend operator fun <T> invoke(block: suspend R2dbcTransaction.() -> T): T = dbQuery(block)

    /**
     * Executes a suspended transaction within the database context, allowing operations
     * on the database schema set by the service.
     *
     * @param T the type of result expected from the database transaction.
     * @param block the suspendable lambda containing the transaction operations to be executed.
     *              It operates within the context of the `Transaction`.
     * @return the result of the suspended transaction block.
     */
    suspend fun <T> dbQuery(block: suspend R2dbcTransaction.() -> T): T {
        return suspendTransaction {
            addLogger(StdOutSqlLogger)
            SchemaUtils.setSchema(scm)
            block()
        }
    }
}