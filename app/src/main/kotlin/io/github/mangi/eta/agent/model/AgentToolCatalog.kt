package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        memoryWritable: Boolean = true,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch: Boolean = true,
        localProfile: LocalToolProfile = LocalToolProfile.FULL,
    ): JSONArray =
        capabilities.project(JSONArray().also { tools ->
            val keepAll = localProfile == LocalToolProfile.LIGHT || localProfile == LocalToolProfile.FULL
            val minimal = localProfile == LocalToolProfile.MINIMAL
            val dev = localProfile == LocalToolProfile.DEV
            AgentContextAppToolCatalog.appendTo(tools)
            if (!minimal) AgentGestureToolCatalog.appendTo(tools)
            if (!minimal) AgentTextSystemToolCatalog.appendTo(tools)
            if (keepAll) {
                AgentDeviceToolCatalog.appendTo(
                    tools,
                    directTools = deviceDirectTools,
                    sensitiveReadTools = deviceSensitiveReadTools,
                    sensitiveActionTools = deviceSensitiveActionTools,
                )
            } else if (dev && deviceDirectTools) {
                AgentDeviceToolCatalog.appendTo(
                    tools,
                    directTools = true,
                    sensitiveReadTools = false,
                    sensitiveActionTools = false,
                    devOnly = true,
                )
            }
            if (browserTools) {
                AgentWebToolCatalog.appendTo(tools, includeSearch = localWebSearch)
                if (keepAll) AgentBrowserToolCatalog.appendTo(tools)
            }
            if (keepAll) {
                AgentSkillToolCatalog.appendTo(
                    tools,
                    githubDiscovery = skillGitHubDiscovery,
                    githubInstall = skillGitHubInstall,
                    authoring = memoryWritable,
                )
            }
            if (memoryTools && !minimal) AgentMemoryToolCatalog.appendTo(tools, writable = memoryWritable)
            if (memoryWritable && keepAll) AgentTaskToolCatalog.appendTo(tools)
            if (deviceDirectTools && (keepAll || dev)) AgentVirtualScreenToolCatalog.appendTo(tools)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
                AgentFileToolCatalog.appendTo(tools)
            }
            AgentToolSchemaCompactor.compact(tools, localProfile)
        })
}
