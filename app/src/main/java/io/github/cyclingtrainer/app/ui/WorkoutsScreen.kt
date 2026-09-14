package io.github.cyclingtrainer.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.cyclingtrainer.app.AppViewModel
import io.github.cyclingtrainer.app.ui.charts.WorkoutHistogram
import io.github.cyclingtrainer.app.workout.Workout

/**
 * Course library: .zwo files are read from a user-picked folder (e.g.
 * Documents/CyclingTrainer). The folder is chosen once (system picker) and
 * then auto-loaded on every app start.
 */
@Composable
fun WorkoutsScreen(
    vm: AppViewModel,
    selected: Workout?,
    onSelect: (Workout) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workouts by vm.workouts.collectAsState()
    val folderUri by vm.courseTreeUri.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) vm.setCourseFolder(uri)
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("课程库", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (folderUri != null)
                "课程目录：Documents/CyclingTrainer（启动时自动读取）"
            else "还没有选择课程目录。把 .zwo 放进 Documents/CyclingTrainer/，再点下方按钮选择该文件夹。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { folderPicker.launch(null) }) {
                Text(if (folderUri == null) "选择课程文件夹" else "更换文件夹")
            }
            OutlinedButton(onClick = { vm.refreshWorkouts() }, enabled = folderUri != null) {
                Text("刷新")
            }
        }
        Spacer(Modifier.height(12.dp))
        if (workouts.isEmpty()) {
            Text(
                if (folderUri == null)
                    "选择文件夹后，其中的 .zwo 课程会显示在这里。"
                else "该文件夹里没有可用的 .zwo 课程。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(workouts, key = { it.name + it.totalDurationSeconds }) { w ->
                val isSelected = selected?.name == w.name &&
                    selected.totalDurationSeconds == w.totalDurationSeconds
                WorkoutCard(w, selected = isSelected, onClick = { onSelect(w) })
            }
        }
    }
}

@Composable
private fun WorkoutCard(w: Workout, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.name, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (selected) {
                    Text("已选", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            val min = w.totalDurationSeconds / 60
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${min} 分钟",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text("${w.segments.size} 段",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Inline course histogram: the whole power profile at a glance.
            Spacer(Modifier.height(8.dp))
            WorkoutHistogram(w, Modifier.fillMaxWidth())
            w.description.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2)
            }
        }
    }
}
