package io.github.cyclingtrainer.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.cyclingtrainer.app.AppViewModel
import io.github.cyclingtrainer.app.BuildConfig
import io.github.cyclingtrainer.app.session.HrZones
import io.github.cyclingtrainer.app.ui.theme.ThemeMode

/**
 * Settings page: personal profile (FTP, heart-rate reference LTHR/max HR),
 * appearance (theme mode). Ride history lives on its own bottom tab.
 */
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val themeMode by vm.themeMode.collectAsState()
    val ftp by vm.ftpWatts.collectAsState()
    val lthr by vm.lthrBpm.collectAsState()
    val maxHr by vm.maxHrBpm.collectAsState()
    val hrRef by vm.hrReference.collectAsState()

    var ftpInput by remember { mutableStateOf(ftp.toString()) }
    var lthrInput by remember { mutableStateOf(lthr.toString()) }
    var maxHrInput by remember { mutableStateOf(maxHr.toString()) }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("个人配置", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("FTP (W)", style = MaterialTheme.typography.labelMedium)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = ftpInput,
                                onValueChange = { ftpInput = it.filter { c -> c.isDigit() } },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                ftpInput.toIntOrNull()?.let { vm.setFtp(it) }
                                ftpInput = vm.ftpWatts.value.toString()
                            }) { Text("保存") }
                        }
                        Text("用于把课程功率换算成 ERG 目标（默认 200 W）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("心率参考", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("乳酸阈值心率 (bpm)", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = lthrInput,
                                onValueChange = { lthrInput = it.filter { c -> c.isDigit() } },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                lthrInput.toIntOrNull()?.let { vm.setLthr(it) }
                                lthrInput = vm.lthrBpm.value.toString()
                            }) { Text("保存") }
                        }
                        Text("阈值心率（LTHR），默认 170 bpm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("最大心率 (bpm)", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f))
                            OutlinedTextField(
                                value = maxHrInput,
                                onValueChange = { maxHrInput = it.filter { ch -> ch.isDigit() } },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                maxHrInput.toIntOrNull()?.let { vm.setMaxHr(it) }
                                maxHrInput = vm.maxHrBpm.value.toString()
                            }) { Text("保存") }
                        }
                        Text("默认 190 bpm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))

                        Text("心率区间依据", style = MaterialTheme.typography.labelMedium)
                        HrZones.Reference.entries.forEach { ref ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.setHrReference(ref) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (hrRef == ref) "● " else "○ ",
                                    color = if (hrRef == ref)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    when (ref) {
                                        HrZones.Reference.LTHR ->
                                            "乳酸阈值心率 (LTHR) — 7 区 (Z1–Z7)"
                                        HrZones.Reference.MAX_HR ->
                                            "最大心率 — 5 区 (Z1–Z5)"
                                    },
                                    fontWeight = if (hrRef == ref) FontWeight.SemiBold
                                    else FontWeight.Normal,
                                )
                            }
                        }
                        Text("功率区间按 FTP 7 区 (Z1–Z7) 显示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("主题模式", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.setThemeMode(mode) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (themeMode == mode) "● " else "○ ",
                                    color = if (themeMode == mode)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    mode.label,
                                    fontWeight = if (themeMode == mode) FontWeight.SemiBold
                                    else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("关于", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        // Read from the build so the About box can never drift
                        // from app/build.gradle.kts again.
                        Text("Cycling Trainer v${BuildConfig.VERSION_NAME} · GPL-3.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
