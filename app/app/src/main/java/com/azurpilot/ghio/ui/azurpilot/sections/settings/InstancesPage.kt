package com.azurpilot.ghio.ui.azurpilot.sections.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azurpilot.ghio.R
import com.azurpilot.ghio.proot.AzurPilotRepository
import com.azurpilot.ghio.theme.AppTokens
import com.azurpilot.ghio.ui.azurpilot.ApEmptyState
import com.azurpilot.ghio.ui.azurpilot.ApSectionColumn
import com.azurpilot.ghio.ui.azurpilot.ApStatusPill
import com.azurpilot.ghio.ui.azurpilot.instanceStatusColor
import com.azurpilot.ghio.ui.azurpilot.instanceStatusText
import com.azurpilot.ghio.ui.azurpilot.apEnter
import com.azurpilot.ghio.ui.components.AppCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 实例管理：建、删、导出、导入
 *
 * 删除走「先读 revision 再删」——网关拿它挡住「读到旧快照后删掉别人刚改的配置」，
 * 所以这里必须先取一次再带上去，不能只传名字。
 *
 * The instance-management page: create, delete, export, import.
 *
 * Deletion goes "read the revision first, then delete" — the gateway uses it
 * to block "read a stale snapshot, then clobber a config someone else just
 * changed", so the revision must be fetched and attached rather than passing
 * only the name.
 *
 * @param repository 网关仓库 / the gateway repository
 */
@Composable
fun InstancesPage(repository: AzurPilotRepository) {
    val instances by repository.instances.collectAsStateWithLifecycle()
    val selected by repository.selectedInstance.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 声明必须排在使用它的 launcher 之前：SAF 回调里要拿这个值
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val name = pendingExport
        pendingExport = null
        if (uri != null && name != null) {
            scope.launch {
                val payload = repository.exportConfig(name)?.values?.toJsonString().orEmpty()
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
                    }
                }
            }
        }
    }

    // 导入选完文件先停一停：待确认的候选（实例名 + 文件内容），确认后才真正上传建档
    var pendingImport by remember { mutableStateOf<ImportCandidate?>(null) }
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val candidate = readImportCandidate(context, uri)
                if (candidate == null) {
                    pendingImport = null
                    repository.reportTransient(
                        context.getString(R.string.ap_instances_import_read_failed)
                    )
                } else {
                    pendingImport = candidate
                }
            }
        }
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.ap_instances_delete_title, name)) },
            text = { Text(stringResource(R.string.ap_instances_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    repository.deleteInstance(name)
                    pendingDelete = null
                }) { Text(stringResource(R.string.ap_instances_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.ap_cancel))
                }
            },
        )
    }

    // 导入警告：安卓端配置与 PC 端存在差异（截图/控制方式等必须用安卓专用值），
    // 直接整份导入 PC 配置后这些参数不会指向本机运行时，实例可能无法直接跑起来，
    // 所以在上传前必须让用户显式确认。
    pendingImport?.let { candidate ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.ap_instances_import)) },
            text = { Text(stringResource(R.string.ap_instances_import_warning, candidate.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    // 两步落库：先上传进导入目录，再用该文件建档；成功后仓库自动切到新实例
                    repository.importConfig(candidate.name, candidate.content) { created ->
                        repository.createInstance(created, importFile = created)
                    }
                }) { Text(stringResource(R.string.ap_instances_import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.ap_cancel))
                }
            },
        )
    }

    ApSectionColumn {
        if (creating) {
            CreateInstanceCard(
                modifier = Modifier.apEnter(0),
                sources = instances.map { it.name },
                onCreate = { name, source ->
                    repository.createInstance(name, source)
                    creating = false
                },
                onCancel = { creating = false },
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .apEnter(0),
                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
            ) {
                Button(
                    onClick = { creating = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(AppTokens.IconSize.md))
                    Text(
                        text = stringResource(R.string.ap_instances_create),
                        modifier = Modifier.padding(start = AppTokens.Spacing.sm),
                    )
                }
                OutlinedButton(
                    onClick = {
                        // json 之外放宽 text/plain 与 octet-stream：文件管理器对 json 的
                        // MIME 标注五花八门，只认 application/json 会在选择器里直接灰掉
                        importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(AppTokens.IconSize.md))
                    Text(
                        text = stringResource(R.string.ap_instances_import),
                        modifier = Modifier.padding(start = AppTokens.Spacing.sm),
                    )
                }
            }
        }

        AppCard(
            title = stringResource(R.string.ap_settings_instances_count, instances.size),
            modifier = Modifier.apEnter(1),
        ) {
            if (instances.isEmpty()) {
                ApEmptyState(
                    icon = Icons.Filled.FolderOpen,
                    title = stringResource(R.string.ap_instance_none),
                    hint = stringResource(R.string.ap_instances_empty_hint),
                )
            } else {
                instances.forEachIndexed { index, instance ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppTokens.Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm),
                            ) {
                                Text(instance.name, style = MaterialTheme.typography.bodyLarge)
                                ApStatusPill(
                                    text = instanceStatusText(instance.status),
                                    container = instanceStatusColor(instance.status).copy(alpha = 0.16f),
                                    content = instanceStatusColor(instance.status),
                                )
                            }
                            Text(
                                text = buildString {
                                    append(instance.server)
                                    if (instance.serial.isNotEmpty()) append(" · ${instance.serial}")
                                    instance.currentTask?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            pendingExport = instance.name
                            exporter.launch("${instance.name}.json")
                        }) {
                            Icon(
                                imageVector = Icons.Filled.FileUpload,
                                contentDescription = stringResource(R.string.ap_instances_export),
                            )
                        }
                        IconButton(
                            enabled = instance.name != selected || instances.size > 1,
                            onClick = { pendingDelete = instance.name },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = stringResource(R.string.ap_instances_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 建档：可以复制一个已有实例，也可以从模板起
 *
 * The create-instance card: copy an existing instance or start from the
 * template.
 *
 * @param modifier 应用于卡片的修饰符 / the modifier applied to the card
 * @param sources 可复制的已有实例名 / the existing instance names to copy from
 * @param onCreate 确认创建：实例名 + 复制源（null 表示模板） / confirms creation:
 *   the instance name plus the copy source (null for the template)
 * @param onCancel 取消建档 / cancels creation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateInstanceCard(
    modifier: Modifier = Modifier,
    sources: List<String>,
    onCreate: (String, String?) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    AppCard(title = stringResource(R.string.ap_instances_create), modifier = modifier) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(stringResource(R.string.ap_instances_name)) },
            supportingText = { Text(stringResource(R.string.ap_instances_name_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = source ?: stringResource(R.string.ap_instances_source_template),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ap_instances_source)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ap_instances_source_template)) },
                    onClick = {
                        source = null
                        expanded = false
                    },
                )
                sources.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ap_instances_source_copy, option)) },
                        onClick = {
                            source = option
                            expanded = false
                        },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AppTokens.Spacing.sm)) {
            Button(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim(), source) },
            ) { Text(stringResource(R.string.ap_instances_create_confirm)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.ap_cancel)) }
        }
    }
}

/**
 * 整份配置 → 带缩进的 JSON 文本，导出用；包装失败时回退成 `"{}"`
 *
 * A whole config → indented JSON text for export; falls back to `"{}"` when
 * wrapping fails.
 */
private fun com.azurpilot.ghio.proot.ApConfigValues.toJsonString(): String =
    runCatching { (JSONObject.wrap(this) as? JSONObject)?.toString(2) }.getOrNull() ?: "{}"

/** 一次待确认的导入候选：实例名来自文件名，内容是原始 JSON 文本 / One import candidate awaiting confirmation: the instance name derives from the file name, the content is the raw JSON text. */
private data class ImportCandidate(val name: String, val content: String)

/**
 * 读取 SAF 选中的配置文件并装成待确认候选；读不出内容时返回 null
 *
 * Reads the SAF-picked config file into a candidate; returns null when the
 * content cannot be read.
 */
private suspend fun readImportCandidate(context: Context, uri: Uri): ImportCandidate? =
    withContext(Dispatchers.IO) {
        runCatching {
            val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: return@runCatching null
            val fileName = queryDisplayName(context, uri).orEmpty()
            ImportCandidate(deriveInstanceName(fileName), content)
        }.getOrNull()
    }

/**
 * 查 SAF 文件的显示名；查询失败或无名时返回 null，调用方回退到固定名
 *
 * Queries the SAF file's display name; returns null when the query fails or
 * the name is missing, and the caller falls back to a fixed name.
 */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

/**
 * 文件名 → 实例名：去扩展名后只保留网关白名单字符（字母数字、空格、点、下划线、
 * 连字符及汉字等），并保证首字符合法；清不出合法名时回退固定名，避免整次导入白跑。
 *
 * File name → instance name: strips the extension and keeps only characters the
 * gateway whitelist allows (letters, digits, space, dot, underscore, hyphen and
 * CJK), with a valid first character; falls back to a fixed name so one odd
 * file name cannot sink the whole import.
 */
private fun deriveInstanceName(fileName: String): String {
    val base = fileName.trim().substringBeforeLast('.').trim().trimEnd('.', ' ')
    val cleaned = base.filter { it.isLetterOrDigit() || it in "_. -" }.take(64).trim()
    return if (cleaned.isNotEmpty() && cleaned.first().isLetterOrDigit()) cleaned else "导入配置"
}
