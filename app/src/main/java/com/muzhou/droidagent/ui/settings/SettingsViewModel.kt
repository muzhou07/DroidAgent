package com.muzhou.droidagent.ui.settings

import com.muzhou.droidagent.agent.mcp.McpServerConfig
import com.muzhou.droidagent.data.AppContainer
import com.muzhou.droidagent.data.AppSettings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val settings = container.settings.settings
    val mcpStates = container.mcp.states

    private val _mcpServers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val mcpServers: StateFlow<List<McpServerConfig>> = _mcpServers.asStateFlow()
    private var saveJob: Job? = null

    init {
        reloadServerList()
    }

    fun saveSettings(settings: AppSettings, immediate: Boolean = false) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (!immediate) delay(300)
            container.settings.save(settings)
        }
    }

    fun saveMcp(server: McpServerConfig) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.db.saveMcpServer(server) }
            reloadServerList()
            container.refreshMcp()
        }
    }

    fun deleteMcp(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.db.deleteMcpServer(id) }
            reloadServerList()
            container.refreshMcp()
        }
    }

    fun setMcpEnabled(server: McpServerConfig, enabled: Boolean) {
        saveMcp(server.copy(enabled = enabled))
    }

    fun reloadMcp() {
        container.refreshMcp()
    }

    private fun reloadServerList() {
        viewModelScope.launch {
            _mcpServers.value = withContext(Dispatchers.IO) { container.db.listMcpServers() }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
