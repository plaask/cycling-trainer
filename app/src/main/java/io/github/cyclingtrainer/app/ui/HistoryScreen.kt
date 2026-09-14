package io.github.cyclingtrainer.app.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.cyclingtrainer.app.AppViewModel
import io.github.cyclingtrainer.app.session.FitExporter
import io.github.cyclingtrainer.app.session.PublicExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * Lists recorded ride CSV files (external files dir/rides).
 * - "导出 FIT" converts the ride to a .FIT in Download/CyclingTrainer —
 *   always clickable; re-exporting overwrites the previous .FIT.
 * - "删除" removes the ride's own CSV from the app's rides dir only.
 *   Already-exported .FIT files in the public Downloads folder are never
 *   touched by deletion.
 */
@Composable
fun HistoryScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current.applicationContext as Application
    val scope = rememberCoroutineScope()
    var rides by remember { mutableStateOf<List<File>>(emptyList()) }
    var refreshed by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var exportedName by remember { mutableStateOf<String?>(null) }
    var deletedName by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(refreshed) {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "rides")
        rides = dir.listFiles { f ->
            f.isFile && f.extension.equals("csv", true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("训练历史", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("导出 FIT 会保存到 下载/CyclingTrainer/；删除只移除本机训练记录（CSV），不影响已导出的文件。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (rides.isEmpty()) {
            Text("暂无记录。完成一次训练后，1Hz CSV 会出现在这里。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rides, key = { it.name }) { f ->
                RideRow(
                    f = f,
                    onExportFit = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val bytes = FitExporter.toFitBytes(f)
                                    val fileName = "${f.nameWithoutExtension}.fit"
                                    PublicExport.writeFitToDownloads(ctx, fileName, bytes)
                                        ?: error("写入下载目录失败")
                                    fileName
                                }
                            }
                            result.onSuccess { exportedName = it }
                                .onFailure { error = it.message ?: "导出失败" }
                        }
                    },
                    onDelete = { pendingDelete = f },
                )
            }
        }
    }

    // ---- confirm delete dialog ----
    pendingDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除记录") },
            text = { Text("确定删除这条训练记录吗？\n\n${f.name}\n\n仅删除本机 CSV 记录，已导出的 FIT 文件不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = f.delete()
                    pendingDelete = null
                    if (ok) {
                        deletedName = f.nameWithoutExtension
                        refreshed++
                    } else {
                        error = "删除失败（文件可能已被移除）"
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // ---- feedback toasts ----
    exportedName?.let { name ->
        Toast.makeText(ctx, "已导出到 下载/CyclingTrainer/$name", Toast.LENGTH_LONG).show()
        exportedName = null
    }
    deletedName?.let { name ->
        Toast.makeText(ctx, "已删除记录 $name", Toast.LENGTH_SHORT).show()
        deletedName = null
    }

    // ---- failure dialog ----
    if (error != null) {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("操作失败") },
            text = { Text(error ?: "") },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun RideRow(
    f: File,
    onExportFit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.nameWithoutExtension, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onExportFit) { Text("导出 FIT") }
                Spacer(Modifier.padding(start = 4.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除记录",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            val kb = f.length() / 1024.0
            Text(
                "%.1f KB  %s".format(
                    Locale.US, kb,
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        .format(Date(f.lastModified()))
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
