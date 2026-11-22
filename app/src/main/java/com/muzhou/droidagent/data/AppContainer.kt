package com.muzhou.droidagent.data

import com.muzhou.droidagent.agent.llm.OpenAiClient
import com.muzhou.droidagent.agent.mcp.McpManager
import com.muzhou.droidagent.agent.web.WebTools
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val context: Context get() = appContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db = SessionStore(appContext)
    val settings = SettingsRepository(appContext, appScope)
    val client = OpenAiClient()
    val web = WebTools()
    val mcp = McpManager()
    val jobs = com.muzhou.droidagent.agent.shell.ShellJobManager(appContext, appScope)
    val runner = AgentRunner(appContext, db, mcp, web, client, jobs)

    init {
        appScope.launch {
            db.ensureDefaultWorkspace()
            runCatching { settings.migrateSecrets() }
            runCatching { db.migrateMcpSecrets() }
            runCatching { mcp.reload(db.listMcpServers()) }
        }
    }

    fun refreshMcp() {
        appScope.launch {
            runCatching { mcp.reload(db.listMcpServers()) }
        }
    }
}
