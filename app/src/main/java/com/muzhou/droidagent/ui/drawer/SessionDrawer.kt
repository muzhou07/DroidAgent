package com.muzhou.droidagent.ui.drawer

import com.muzhou.droidagent.agent.workspace.Workspace
import com.muzhou.droidagent.data.Session
import com.muzhou.droidagent.ui.chat.ChatUiState
import com.muzhou.droidagent.ui.components.MiuixDropdownMenu
import com.muzhou.droidagent.ui.components.MiuixMenuItem
import com.muzhou.droidagent.ui.components.MiuixMenuPlacement
import com.muzhou.droidagent.ui.theme.DsTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class WorkspaceGroup(
    val workspace: Workspace,
    val sessions: List<Session>,
)

@Composable
fun SessionDrawer(
    state: ChatUiState,
    onClose: () -> Unit,
    onNewSession: () -> Unit,
    onNewSessionInWorkspace: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onManageWorkspaces: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTheme: () -> Unit,
) {
    val colors = DsTheme.colors
    var renameTarget by remember { mutableStateOf<Session?>(null) }
    var deleteTarget by remember { mutableStateOf<Session?>(null) }
    var query by remember { mutableStateOf("") }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(state.workspace?.id) {
        state.workspace?.id?.let { expanded.putIfAbsent(it, true) }
    }

    val groups = remember(state.sessions, state.workspaces, state.workspace?.id, query, state.searchResults) {
        val visible = state.sessions.filter {
            query.isBlank() ||
                it.title.contains(query, ignoreCase = true) ||
                it.id in state.searchResults
        }
        state.workspaces
            .sortedWith(
                compareByDescending<Workspace> { it.id == state.workspace?.id }
                    .thenBy { it.createdAt },
            )
            .mapNotNull { workspace ->
                val sessions = visible
                    .filter { it.workspaceId == workspace.id }
                    .sortedByDescending { it.updatedAt }
                if (query.isNotBlank() && sessions.isEmpty()) null
                else WorkspaceGroup(workspace, sessions)
            }
    }

    ModalDrawerSheet(
        modifier = Modifier
            .width(304.dp)
            .fillMaxHeight(),
        drawerContainerColor = colors.sidebar,
        drawerContentColor = colors.textPrimary,
        drawerShape = RoundedCornerShape(0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding(),
        ) {
            SearchField(
                query = query,
                onQueryChange = {
                    query = it
                    onSearchQuery(it)
                },
            )
            OutlinedButton(
                onClick = {
                    onNewSession()
                    onClose()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .padding(horizontal = 12.dp),
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.background,
                    contentColor = colors.textPrimary,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderStrong),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("新会话", fontSize = 13.sp)
            }
            Spacer(Modifier.size(10.dp))
            WorkspaceSectionHeader(
                onManageWorkspaces = {
                    onManageWorkspaces()
                    onClose()
                },
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 2.dp,
                    bottom = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                if (groups.isEmpty()) {
                    item("empty") {
                        Text(
                            text = if (query.isBlank()) "暂无会话" else "无匹配会话",
                            fontSize = 11.sp,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
                groups.forEach { group ->
                    item(key = "ws_${group.workspace.id}") {
                        Column {
                            WorkspaceRow(
                                workspace = group.workspace,
                                active = group.workspace.id == state.workspace?.id,
                                expanded = expanded[group.workspace.id] ?: true,
                                onNewSession = {
                                    onNewSessionInWorkspace(group.workspace.id)
                                    onClose()
                                },
                                onToggle = {
                                    expanded[group.workspace.id] = !(expanded[group.workspace.id] ?: true)
                                },
                            )
                            Spacer(Modifier.height(7.dp))
                        }
                    }
                    if (expanded[group.workspace.id] ?: true) {
                        if (group.sessions.isEmpty()) {
                            item(key = "empty_${group.workspace.id}") {
                                Text(
                                    text = "暂无会话",
                                    fontSize = 11.sp,
                                    color = colors.textTertiary,
                                    modifier = Modifier.padding(start = 44.dp, top = 4.dp, bottom = 6.dp),
                                )
                            }
                        } else {
                            items(group.sessions, key = { "s_${it.id}" }) { session ->
                                Box(Modifier.animateItem()) {
                                    SessionRow(
                                        session = session,
                                        selected = session.id == state.session?.id,
                                        onClick = {
                                            onSelectSession(session.id)
                                            onClose()
                                        },
                                        onRename = { renameTarget = session },
                                        onDelete = { deleteTarget = session },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.divider),
            )
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                DrawerAction(
                    icon = if (colors.dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    label = if (colors.dark) "浅色模式" else "深色模式",
                    onClick = onToggleTheme,
                )
                DrawerAction(
                    icon = Icons.Outlined.Settings,
                    label = "设置",
                    onClick = {
                        onOpenSettings()
                        onClose()
                    },
                )
            }
        }
    }

    renameTarget?.let { target ->
        var value by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 15.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameSession(target.id, value)
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话？") },
            text = { Text("「${target.title}」及其全部消息将被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(target.id)
                    deleteTarget = null
                }) { Text("删除", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun WorkspaceSectionHeader(
    onManageWorkspaces: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "工作区",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textTertiary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onManageWorkspaces, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Outlined.CreateNewFolder,
                contentDescription = "管理工作区",
                tint = colors.textSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)
            .clip(CircleShape)
            .background(colors.search)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(7.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.5.sp, lineHeight = 13.sp, color = colors.textPrimary),
            cursorBrush = SolidColor(colors.brand),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text("搜索会话或内容", fontSize = 12.5.sp, lineHeight = 13.sp, color = colors.textTertiary)
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun WorkspaceRow(
    workspace: Workspace,
    active: Boolean,
    expanded: Boolean,
    onNewSession: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors = DsTheme.colors
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (active) Modifier.shadow(5.dp, shape, clip = false) else Modifier)
            .clip(shape)
            .background(if (active) colors.layer else Color.Transparent)
            .clickable(onClick = onToggle)
            .padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = if (active) colors.brand else colors.textTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = workspace.name,
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = if (active) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNewSession, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = "在该工作区新建会话",
                tint = colors.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "收起" else "展开",
            tint = colors.textTertiary,
            modifier = Modifier
                .padding(start = 2.dp)
                .size(15.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: Session,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DsTheme.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) colors.card else Color.Transparent)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menu = true },
                    onLongClickLabel = "会话操作",
                )
                .padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.title,
                fontSize = 12.5.sp,
                color = if (selected) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = relativeTime(session.updatedAt),
                fontSize = 9.5.sp,
                color = colors.textTertiary,
            )
            if (selected) {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        contentDescription = "会话操作",
                        tint = colors.textTertiary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        MiuixDropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            placement = MiuixMenuPlacement.BELOW_START,
            gap = 4.dp,
        ) {
            MiuixMenuItem(
                text = "重命名",
                icon = Icons.Outlined.Edit,
                onClick = {
                    menu = false
                    onRename()
                },
            )
            MiuixMenuItem(
                text = "删除",
                icon = Icons.Outlined.Delete,
                danger = true,
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun DrawerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(11.dp))
        Text(label, fontSize = 13.5.sp, color = colors.textPrimary)
    }
}

private fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    if (diff in 0..60_000) return "刚刚"
    if (diff in 0..3_600_000) return "${diff / 60_000} 分钟前"
    val current = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    if (current.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        current.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    ) {
        return time
    }
    val yesterday = current.clone() as Calendar
    yesterday.add(Calendar.DAY_OF_YEAR, -1)
    if (yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    ) {
        return "昨天"
    }
    return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
}
