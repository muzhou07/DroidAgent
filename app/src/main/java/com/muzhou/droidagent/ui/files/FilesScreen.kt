package com.muzhou.droidagent.ui.files

import com.muzhou.droidagent.agent.workspace.FsEntry
import com.muzhou.droidagent.agent.workspace.VirtualFileSystem
import com.muzhou.droidagent.agent.workspace.Workspace
import com.muzhou.droidagent.agent.workspace.WorkspaceFactory
import com.muzhou.droidagent.agent.util.formatBytes
import com.muzhou.droidagent.ui.components.MiuixTextField
import com.muzhou.droidagent.ui.theme.DsTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun FilesScreen(
    workspace: Workspace,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vfs = remember(workspace.id, workspace.uri, workspace.path) {
        runCatching { WorkspaceFactory.create(context, workspace) }.getOrNull()
    }
    var path by remember(workspace.id) { mutableStateOf(".") }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var deleteTarget by remember { mutableStateOf<FsEntry?>(null) }
    var newFileName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vfs, path, refresh) {
        loading = true
        error = null
        try {
            entries = vfs?.list(path) ?: emptyList()
        } catch (e: Exception) {
            entries = emptyList()
            error = e.message
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = if (path == ".") onBack else ({ path = path.substringBeforeLast('/', ".") })) {
                Icon(
                    if (path == ".") Icons.AutoMirrored.Outlined.ArrowBack else Icons.Outlined.ArrowUpward,
                    contentDescription = if (path == ".") "返回" else "上一级",
                    tint = colors.textPrimary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = workspace.name,
                    fontSize = 15.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = if (path == ".") "/" else "/$path",
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { newFileName = "" }) {
                Icon(Icons.Outlined.Add, contentDescription = "新建文件", tint = colors.textSecondary)
            }
            IconButton(onClick = { refresh++ }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = colors.textSecondary)
            }
        }
        if (error != null) {
            Text(
                text = error.orEmpty(),
                fontSize = 12.sp,
                color = colors.danger,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.brand)
            }
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("空目录", fontSize = 13.sp, color = colors.textTertiary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                items(entries, key = { it.relPath }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.layer)
                            .clickable {
                                if (entry.isDirectory) path = entry.relPath else onOpenFile(entry.relPath)
                            }
                            .padding(start = 10.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                            contentDescription = null,
                            tint = if (entry.isDirectory) colors.warning else colors.textSecondary,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = entry.name,
                                fontSize = 13.sp,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!entry.isDirectory) {
                                Text(
                                    formatBytes(entry.size),
                                    fontSize = 9.5.sp,
                                    color = colors.textTertiary,
                                )
                            }
                        }
                        IconButton(onClick = { deleteTarget = entry }, modifier = Modifier.size(30.dp)) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "删除",
                                tint = colors.textTertiary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 ${target.name}？") },
            text = { Text(if (target.isDirectory) "目录及其中的所有内容将被递归删除。" else "该文件将被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { vfs?.delete(target.relPath, recursive = true) }
                        deleteTarget = null
                        refresh++
                    }
                }) { Text("删除", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    newFileName?.let { current ->
        var name by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { newFileName = null },
            title = { Text("新建文件") },
            text = {
                MiuixTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "文件名",
                    placeholder = "例如 notes.md",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val fileName = name.trim()
                    if (fileName.isNotEmpty()) {
                        val rel = if (path == ".") fileName else "$path/$fileName"
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { vfs?.writeText(rel, "") }
                            }
                            newFileName = null
                            refresh++
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { newFileName = null }) { Text("取消") }
            },
        )
    }
}

@Composable
fun FileEditorScreen(
    workspace: Workspace,
    path: String,
    onBack: () -> Unit,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vfs = remember(workspace.id, workspace.uri, workspace.path) {
        runCatching { WorkspaceFactory.create(context, workspace) }.getOrNull()
    }
    var content by remember(path) { mutableStateOf("") }
    var original by remember(path) { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedAt by remember { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }
    val dirty = content != original

    LaunchedEffect(vfs, path) {
        loading = true
        try {
            val text = vfs?.readText(path) ?: ""
            content = text
            original = text
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                vfs?.writeText(path, content)
                original = content
                savedAt = System.currentTimeMillis()
                error = null
            } catch (e: Exception) {
                error = e.message
            } finally {
                saving = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = colors.textPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = path.substringAfterLast('/'),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                Text(
                    text = path.substringBeforeLast('/', "").let {
                        if (it.isEmpty()) "工作区根目录" else "/$it"
                    } + if (dirty) " · 未保存" else if (savedAt != null) " · 已保存" else "",
                    fontSize = 10.sp,
                    color = if (dirty) colors.warning else colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { save() }, enabled = dirty && !saving) {
                Text(if (saving) "保存中…" else "保存", color = if (dirty) colors.brand else colors.textTertiary)
            }
        }
        if (error != null) {
            Text(
                text = error.orEmpty(),
                color = colors.danger,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.brand)
            }
        } else {
            androidx.compose.foundation.text.BasicTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    lineHeight = 13.sp,
                    color = colors.textPrimary,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.brand),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${content.lines().size} 行 · ${formatBytes(content.toByteArray().size.toLong())}",
                    fontSize = 10.sp,
                    color = colors.textTertiary,
                )
            }
        }
    }
}
