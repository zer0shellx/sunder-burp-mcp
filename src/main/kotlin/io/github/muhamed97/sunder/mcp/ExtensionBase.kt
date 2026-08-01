package io.github.muhamed97.sunder.mcp

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import io.github.muhamed97.sunder.mcp.config.ConfigUi
import io.github.muhamed97.sunder.mcp.config.McpConfig
import io.github.muhamed97.sunder.mcp.providers.ClaudeDesktopProvider
import io.github.muhamed97.sunder.mcp.providers.ManualProxyInstallerProvider
import io.github.muhamed97.sunder.mcp.providers.ProxyJarManager
import io.github.muhamed97.sunder.mcp.traffic.LiveTrafficRecorder
import io.github.muhamed97.sunder.mcp.traffic.LiveTrafficPanel
import io.github.muhamed97.sunder.mcp.traffic.InMemoryTrafficStore

@Suppress("unused")
class ExtensionBase : BurpExtension {

    override fun initialize(api: MontoyaApi) {
        api.extension().setName("SUNDER")

        val config = McpConfig(api.persistence().extensionData(), api.logging())
        val trafficStore = InMemoryTrafficStore { config.maxSessionEvents }
        val trafficRecorder = LiveTrafficRecorder(api, trafficStore, config)
        trafficRecorder.start()
        val serverManager = KtorServerManager(api, trafficStore, trafficRecorder)
        api.logging().logToOutput("SUNDER session-only capture initialized (no traffic database)")

        val proxyJarManager = ProxyJarManager(api.logging())

        val configUi = ConfigUi(
            config = config, providers = listOf(
                ClaudeDesktopProvider(api.logging(), proxyJarManager),
                ManualProxyInstallerProvider(api.logging(), proxyJarManager),
            )
        )

        configUi.onEnabledToggled { enabled ->
            configUi.getConfig()

            if (enabled) {
                serverManager.start(config) { state ->
                    configUi.updateServerState(state)
                }
            } else {
                serverManager.stop { state ->
                    configUi.updateServerState(state)
                }
            }
        }

        api.userInterface().registerSuiteTab("SUNDER", configUi.component)
        val liveTrafficPanel = LiveTrafficPanel(api, config, trafficStore, trafficRecorder)
        api.userInterface().registerSuiteTab("SUNDER Traffic", liveTrafficPanel)

        api.extension().registerUnloadingHandler {
            serverManager.shutdown()
            trafficRecorder.close()
            liveTrafficPanel.close()
            trafficStore.close()
            configUi.cleanup()
            config.cleanup()
        }

        if (config.enabled) {
            serverManager.start(config) { state ->
                configUi.updateServerState(state)
            }
        }
    }
}
