package com.muzhou.droidagent.ui.workspace

import com.muzhou.droidagent.agent.workspace.Workspace
import com.muzhou.droidagent.agent.workspace.WorkspaceFactory
import com.muzhou.droidagent.agent.workspace.WorkspaceKind
import com.muzhou.droidagent.ui.chat.ChatUiState
import com.muzhou.droidagent.ui.theme.DsTheme
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class CreateKind { LOCAL, EXTERNAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onCreateLocal: (String) -> Unit,
    onCreateExternal: (String) -> Unit,
    onAddSaf: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val colors = DsTheme.colors
    val context = LocalContext.current
    var createKind by remember { mutableStateOf<CreateKind?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val safPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onAddSaf(uri.toString())
            onDismiss()
        }
    }
    val legacyWritePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) createKind = CreateKind.EXTERNAL
        else notice = "需要存储写入权限才能创建共享工作区，可改用 SAF 目录。"
    }

    fun requestExternal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                createKind = CreateKind.EXTERNAL
            } else {
                notice = "请先授予“所有文件访问权限”，返回后再次点击创建。"
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            }
        } else {
            legacyWritePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.background,
        contentColor = colors.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.borderStrong) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 20.dp),
        ) {
            Text("工作区", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(4.dp))
            Text(
                text = "应用私有无需权限；共享存储支持 Termux Shell。",
                fontSize = 11.sp,
                color = colors.textTertiary,
            )
            Spacer(Modifier.size(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 330.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.workspaces.forEach { workspace ->
                    WorkspaceRow(
                        workspace = workspace,
                        selected = workspace.id == state.workspace?.id,
                        onDelete = if (workspace.id == "default") null else ({ onDelete(workspace.id) }),
                    )
                }
            }
            Spacer(Modifier.size(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AddWorkspaceButton(
                    icon = Icons.Outlined.PhoneAndroid,
                    label = "应用私有",
                    modifier = Modifier.weight(1f),
                ) { createKind = CreateKind.LOCAL }
                AddWorkspaceButton(
                    icon = Icons.Outlined.SdCard,
                    label = "共享存储",
                    modifier = Modifier.weight(1f),
                    onClick = { requestExternal() },
                )
                AddWorkspaceButton(
                    icon = Icons.Outlined.FolderOpen,
                    label = "SAF 目录",
                    modifier = Modifier.weight(1f),
                ) { safPicker.launch(null) }
            }
            notice?.let {
                Spacer(Modifier.size(10.dp))
                Text(it, fontSize = 11.sp, color = colors.warning)
            }
        }
    }

    createKind?.let { kind ->
        var name by remember(kind) { mutableStateOf(if (kind == CreateKind.LOCAL) "本地工作区" else "共享工作区") }
        AlertDialog(
            onDismissRequest = { createKind = null },
            title = { Text(if (kind == CreateKind.LOCAL) "新建应用私有工作区" else "新建共享存储工作区") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 15.sp),
                        label = { Text("工作区名称") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (kind == CreateKind.LOCAL) onCreateLocal(name) else onCreateExternal(name)
                        createKind = null
                        onDismiss()
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { createKind = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun WorkspaceRow(
    workspace: Workspace,
    selected: Boolean,
    onDelete: (() -> Unit)?,
) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) colors.brandSoft else colors.layer)
            .border(1.dp, if (selected) colors.brand.copy(alpha = 0.4f) else colors.border, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (workspace.kind) {
                WorkspaceKind.LOCAL -> Icons.Outlined.PhoneAndroid
                WorkspaceKind.EXTERNAL -> Icons.Outlined.SdCard
                WorkspaceKind.SAF -> Icons.Outlined.Folder
            },
            contentDescription = null,
            tint = if (selected) colors.brand else colors.textSecondary,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = workspace.name,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (workspace.kind) {
                    WorkspaceKind.LOCAL -> "应用私有 · ${workspace.path.orEmpty()}"
                    WorkspaceKind.EXTERNAL -> "共享存储 · ${workspace.path.orEmpty()}"
                    WorkspaceKind.SAF -> "SAF · ${workspace.uri.orEmpty()}"
                },
                fontSize = 10.sp,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "当前工作区",
                tint = colors.brand,
                modifier = Modifier.size(17.dp),
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除工作区",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AddWorkspaceButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = DsTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, colors.border, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = colors.brand, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, fontSize = 11.sp, color = colors.textSecondary)
    }
}
