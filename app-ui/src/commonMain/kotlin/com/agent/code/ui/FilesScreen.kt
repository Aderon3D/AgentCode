package com.agent.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.FileNode
import com.agent.code.workspace.FileSystemProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Workspace browser: list a directory, open a file, edit + save, create a new
 * file. Root is the agent's clean workspace/ dir (not the opencode install
 * dir), so everything shown is something the agent (or the operator) wrote.
 *
 * ponytail: directory contents are re-walked on a 2s poll rather than wired to
 * a filesystem watcher — fine for a test surface on one small dir; swap in
 * FileWatcher if the lag ever matters.
 */
@Composable
fun FilesScreen(
    fileSystem: FileSystemProvider,
    root: VirtualPath,
    modifier: Modifier = Modifier
) {
    var currentDir by remember { mutableStateOf(root) }
    var nodes by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var openFile by remember { mutableStateOf<FileNode.File?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    var prevMtimes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var changedNames by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun refresh() {
        fileSystem.walkTree(currentDir, maxDepth = 1, ignorePatterns = emptyList()).fold(
            onSuccess = { tree ->
                nodes = tree.children
                val mtimes = tree.children
                    .filterIsInstance<FileNode.File>()
                    .associate { it.name to it.lastModifiedMs }
                // Only flag as "changed" once we have a previous snapshot for this dir.
                changedNames = if (prevMtimes.isEmpty()) emptySet()
                    else mtimes.filter { (name, t) -> prevMtimes[name] != null && prevMtimes[name] != t }.keys
                prevMtimes = mtimes
                loadError = null
            },
            onFailure = { loadError = it.message }
        )
    }

    LaunchedEffect(currentDir) {
        prevMtimes = emptyMap()
        while (isActive) {
            refresh()
            delay(2_000)
        }
    }

    val open = openFile
    if (open != null) {
        FileViewer(
            fileSystem = fileSystem,
            file = open,
            onBack = { openFile = null },
            modifier = modifier
        )
    } else {
        Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentDir.rawPath != root.rawPath) {
                    TextButton(onClick = { currentDir = currentDir.parent() ?: root }) { Text("Up") }
                }
                Text(
                    // Relative breadcrumb: the part under the workspace root.
                    text = currentDir.rawPath.removePrefix(root.rawPath).trimStart('/').ifEmpty { "workspace" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                TextButton(onClick = { showNewDialog = true }) { Text("New file", fontSize = 12.sp) }
            }

            loadError?.let {
                Text("Error: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }

            if (nodes.isEmpty() && loadError == null) {
                Text("Empty directory", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }

            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(nodes) { node ->
                    val isDir = node is FileNode.Directory
                    val changed = node.name in changedNames
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isDir) {
                                    currentDir = node.path
                                } else {
                                    openFile = node as FileNode.File
                                }
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isDir) "[d]" else "[f]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(node.name, fontSize = 13.sp)
                            if (!isDir) {
                                val f = node as FileNode.File
                                Text(
                                    "${formatSize(f.sizeBytes)}  ${formatTime(f.lastModifiedMs)}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (changed) {
                            Text(
                                "changed",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            }
        }

        if (showNewDialog) {
            NewFileDialog(
                onDismiss = { showNewDialog = false },
                onCreate = { name -> fileSystem.write(currentDir.resolve(name), "") },
                exists = { name ->
                    // Suspend-free probe: the dialog body is not a suspend scope.
                    fileSystem.exists(currentDir.resolve(name))
                }
            )
        }
    }
}

@Composable
private fun FileViewer(
    fileSystem: FileSystemProvider,
    file: FileNode.File,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // null = loading, sentinel = too large to load.
    var content by remember(file.path) { mutableStateOf<String?>(null) }
    var tooLarge by remember(file.path) { mutableStateOf(false) }
    var editing by remember(file.path) { mutableStateOf(false) }
    var draft by remember(file.path) { mutableStateOf("") }
    var opError by remember(file.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.path) {
        if (file.sizeBytes > 2_000_000L) {
            tooLarge = true
        } else {
            fileSystem.read(file.path).fold(
                onSuccess = { content = it },
                onFailure = { content = "read failed: ${it.message}" }
            )
        }
    }

    Column(modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Column(Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                Text(
                    "${formatSize(file.sizeBytes)}  ${formatTime(file.lastModifiedMs)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!tooLarge) {
                if (!editing) {
                    TextButton(onClick = {
                        draft = content.orEmpty()
                        editing = true
                    }) { Text("Edit", fontSize = 12.sp) }
                } else {
                    TextButton(onClick = { editing = false }) { Text("Cancel", fontSize = 12.sp) }
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    TextButton(onClick = {
                        scope.launch {
                            fileSystem.write(file.path, draft).fold(
                                onSuccess = {
                                    content = draft
                                    editing = false
                                    opError = null
                                },
                                onFailure = { opError = it.message }
                            )
                        }
                    }) { Text("Save", fontSize = 12.sp) }
                }
            }
        }

        opError?.let {
            Text("Error: $it", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }

        when {
            tooLarge -> Text(
                "File too large to view (${formatSize(file.sizeBytes)}); viewer capped at 2 MB.",
                fontSize = 12.sp
            )
            content == null -> Text("Loading...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            editing -> OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            )
            else -> Text(
                text = content.orEmpty(),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(4.dp)
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp)
            )
        }
    }
}

@Composable
private fun NewFileDialog(
    onDismiss: () -> Unit,
    onCreate: suspend (String) -> Result<Unit>,
    exists: suspend (String) -> Boolean
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New file", fontSize = 14.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    placeholder = { Text("name.txt") }
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                when {
                    trimmed.isEmpty() -> error = "Name is empty"
                    trimmed.contains('/') -> error = "Name must not contain /"
                    else -> scope.launch {
                        if (exists(trimmed)) {
                            error = "Already exists"
                        } else {
                            onCreate(trimmed)
                                .onSuccess { onDismiss() }
                                .onFailure { error = it.message ?: "Write failed" }
                        }
                    }
                }
            }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${round1(bytes / 1024.0)} KB"
    else -> "${round1(bytes / (1024.0 * 1024.0))} MB"
}

private fun round1(d: Double): Double = kotlin.math.round(d * 10) / 10.0

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return ""
    val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    val m = dt.monthNumber.toString().padStart(2, '0')
    val d = dt.dayOfMonth.toString().padStart(2, '0')
    val h = dt.hour.toString().padStart(2, '0')
    val min = dt.minute.toString().padStart(2, '0')
    return "$m-$d $h:$min"
}
