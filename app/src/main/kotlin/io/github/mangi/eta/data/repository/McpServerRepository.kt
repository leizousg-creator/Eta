package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpServerSetting
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object McpServerRepository {
    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) {
            applicationContext = context.applicationContext
        }
    }

    fun serversFlow(): Flow<List<McpServerSetting>> = dao().serversFlow().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun servers(): List<McpServerSetting> = dao().servers().map { it.toDomain() }

    suspend fun enabledServers(): List<McpServerSetting> = servers().filter(McpServerSetting::enabled)

    suspend fun serverById(id: String): McpServerSetting? = dao().serverById(id)?.toDomain()

    suspend fun add(server: McpServerSetting, bearerToken: String): McpServerSetting {
        val added = server.copy(
            id = server.id.ifBlank { UUID.randomUUID().toString() },
            sortOrder = (servers().maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        val secretStore = secrets()
        updateSecret(secretStore, added, bearerToken)
        try {
            dao().insert(added.toEntity())
        } catch (failure: Throwable) {
            runCatching { secretStore.clear(added.id) }
            throw failure
        }
        return added
    }

    suspend fun update(server: McpServerSetting, bearerToken: String? = null) {
        val previous = requireNotNull(serverById(server.id)) { "MCP 服务器不存在" }
        val secretStore = secrets()
        val previousToken = secretStore.bearerToken(server.id)
        val changesSecret = bearerToken != null || server.authorizationType == McpAuthorizationType.NONE
        val updated = if (bearerToken != null) {
            server.copy(lastProtocolVersion = null, toolsExpireAt = null)
        } else {
            server
        }
        if (changesSecret) updateSecret(secretStore, updated, bearerToken.orEmpty())
        try {
            require(dao().update(updated.toEntity()) == 1) { "MCP 服务器不存在" }
        } catch (failure: Throwable) {
            if (changesSecret) runCatching {
                updateSecret(secretStore, previous, previousToken.orEmpty())
            }
            throw failure
        }
    }

    suspend fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean) {
        val server = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        val names = server.enabledToolNames.toMutableSet()
        if (enabled) names += toolName else names -= toolName
        update(server.copy(enabledToolNames = names))
    }

    /**
     * 批量启用/停用某个服务器的全部工具，返回本次生效的工具数。
     *
     * 语义与单工具开关一致：`enabledToolNames` 是白名单，空集即全部停用；
     * 全开只覆盖服务器当前已发布的工具，刷新后新工具仍需手动确认（危险工具不该被静默带开）。
     */
    suspend fun setAllToolsEnabled(serverId: String, enabled: Boolean): Int {
        val server = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        val names: Set<String> = if (enabled) {
            server.tools.mapTo(LinkedHashSet()) { it.name }
        } else {
            emptySet()
        }
        if (names != server.enabledToolNames) update(server.copy(enabledToolNames = names))
        return names.size
    }

    suspend fun delete(serverId: String) {
        val previous = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        val secretStore = secrets()
        val previousToken = secretStore.bearerToken(serverId)
        secretStore.clear(serverId)
        try {
            require(dao().delete(serverId) == 1) { "MCP 服务器不存在" }
        } catch (failure: Throwable) {
            runCatching { updateSecret(secretStore, previous, previousToken.orEmpty()) }
            throw failure
        }
    }

    fun bearerToken(serverId: String): String? = secrets().bearerToken(serverId)

    private fun updateSecret(
        secretStore: McpSecretStore,
        server: McpServerSetting,
        bearerToken: String,
    ) {
        if (server.authorizationType == McpAuthorizationType.BEARER) {
            secretStore.setBearerToken(server.id, bearerToken)
        } else {
            secretStore.clear(server.id)
        }
    }

    private fun dao() = EtaDatabase.get(context()).mcpServerDao()

    private fun secrets() = McpSecretStore(context())

    private fun context(): Context {
        check(::applicationContext.isInitialized) {
            "McpServerRepository.init(context) must be called in Application.onCreate()"
        }
        return applicationContext
    }
}
