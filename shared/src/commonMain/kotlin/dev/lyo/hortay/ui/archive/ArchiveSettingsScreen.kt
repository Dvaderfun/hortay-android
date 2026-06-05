@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package dev.lyo.hortay.ui.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.archive.ArchiveSettings
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.settings.SectionLabel
import dev.lyo.hortay.ui.settings.SettingsRow
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.action_back
import hortay.shared.generated.resources.archive_capture_deletes
import hortay.shared.generated.resources.archive_capture_edits
import hortay.shared.generated.resources.archive_clear_all
import hortay.shared.generated.resources.archive_clear_confirm
import hortay.shared.generated.resources.archive_clear_yes
import hortay.shared.generated.resources.archive_disable_body
import hortay.shared.generated.resources.archive_disable_keep
import hortay.shared.generated.resources.archive_disable_purge
import hortay.shared.generated.resources.archive_disable_title
import hortay.shared.generated.resources.archive_export_continue
import hortay.shared.generated.resources.archive_export_json
import hortay.shared.generated.resources.archive_export_size_warning
import hortay.shared.generated.resources.archive_master_subtitle
import hortay.shared.generated.resources.archive_master_toggle
import hortay.shared.generated.resources.archive_max_records_label
import hortay.shared.generated.resources.archive_onboarding_cancel
import hortay.shared.generated.resources.archive_open_browser
import hortay.shared.generated.resources.archive_records_unlimited
import hortay.shared.generated.resources.archive_retention_days
import hortay.shared.generated.resources.archive_retention_label
import hortay.shared.generated.resources.archive_retention_unlimited
import hortay.shared.generated.resources.archive_section_archive
import hortay.shared.generated.resources.archive_section_capture
import hortay.shared.generated.resources.archive_section_events
import hortay.shared.generated.resources.archive_section_retention
import hortay.shared.generated.resources.archive_snapshot_count
import hortay.shared.generated.resources.archive_storage_label
import hortay.shared.generated.resources.settings_archive_title
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.round
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Post archive management screen. Layout follows the project's settings idiom
 * ([SectionLabel] + [SegmentedListItem] sub-rows). The JSON-export file picker is platform-specific,
 * so the screen exposes an [onExportJson] callback that the Android host wires to a SAF launcher +
 * [ArchiveSettingsViewModel.exportTo] (null/no-op on iOS until file export lands there).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveSettingsScreen(
    viewModel: ArchiveSettingsViewModel,
    onBack: () -> Unit,
    onOpenArchive: () -> Unit = {},
) {
    val s by viewModel.settings.collectAsState()
    val count by viewModel.snapshotCount.collectAsState()
    val bytes by viewModel.storageBytes.collectAsState()
    var showOnboarding by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showExportConfirm by remember { mutableStateOf(false) }
    val triggerExport = rememberArchiveJsonExporter { viewModel.exportTo(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_archive_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(
                            name = "arrow_back",
                            contentDescription = stringResource(Res.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            size = 24.dp,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionLabel(stringResource(Res.string.archive_section_capture))
            MasterToggleRow(
                enabled = s.enabled,
                onChange = { wantOn ->
                    if (wantOn && !s.onboardingSeen) {
                        showOnboarding = true
                    } else if (wantOn) {
                        viewModel.confirmEnableFromOnboarding()
                    } else {
                        showDisableDialog = true
                    }
                },
            )

            if (s.enabled) {
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(Res.string.archive_section_retention))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DropdownRow(
                        symbol = "timer",
                        title = stringResource(Res.string.archive_retention_label),
                        value = retentionLabel(s.retentionDays),
                        options = ArchiveSettings.RETENTION_OPTIONS,
                        labelOf = { retentionLabel(it) },
                        onPick = viewModel::setRetentionDays,
                        index = 0,
                        count = 2,
                    )
                    DropdownRow(
                        symbol = "storage",
                        title = stringResource(Res.string.archive_max_records_label),
                        value = recordsLabel(s.maxRecords),
                        options = ArchiveSettings.MAX_RECORDS_OPTIONS,
                        labelOf = { recordsLabel(it) },
                        onPick = viewModel::setMaxRecords,
                        index = 1,
                        count = 2,
                    )
                }

                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(Res.string.archive_section_events))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ToggleRow(
                        symbol = "edit",
                        title = stringResource(Res.string.archive_capture_edits),
                        checked = s.captureEdits,
                        onCheckedChange = viewModel::setCaptureEdits,
                        index = 0,
                        count = 2,
                    )
                    ToggleRow(
                        symbol = "delete",
                        title = stringResource(Res.string.archive_capture_deletes),
                        checked = s.captureDeletes,
                        onCheckedChange = viewModel::setCaptureDeletes,
                        index = 1,
                        count = 2,
                    )
                }

                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(Res.string.archive_section_archive))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsRow(
                        symbol = "delete_sweep",
                        title = stringResource(Res.string.archive_open_browser),
                        subtitle = pluralStringResource(Res.plurals.archive_snapshot_count, count, count),
                        chevron = true,
                        index = 0,
                        count = 4,
                        onClick = onOpenArchive,
                    )
                    SettingsRow(
                        symbol = "storage",
                        title = stringResource(Res.string.archive_storage_label),
                        subtitle = formatBytes(bytes),
                        index = 1,
                        count = 4,
                    )
                    SettingsRow(
                        symbol = "ios_share",
                        title = stringResource(Res.string.archive_export_json),
                        chevron = true,
                        index = 2,
                        count = 4,
                        onClick = { showExportConfirm = true },
                    )
                    SettingsRow(
                        symbol = "delete",
                        title = stringResource(Res.string.archive_clear_all),
                        tint = MaterialTheme.colorScheme.error,
                        index = 3,
                        count = 4,
                        onClick = { showClearConfirm = true },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showOnboarding) {
        ArchiveOnboardingSheet(
            onDismiss = { showOnboarding = false },
            onEnable = {
                viewModel.confirmEnableFromOnboarding()
                showOnboarding = false
            },
        )
    }
    if (showDisableDialog) {
        DisableDialog(
            onKeep = { viewModel.disable(deleteArchive = false); showDisableDialog = false },
            onDelete = { viewModel.disable(deleteArchive = true); showDisableDialog = false },
            onCancel = { showDisableDialog = false },
        )
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(Res.string.archive_clear_all)) },
            text = { Text(stringResource(Res.string.archive_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAll(); showClearConfirm = false }) {
                    Text(stringResource(Res.string.archive_clear_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(Res.string.archive_onboarding_cancel))
                }
            },
        )
    }
    if (showExportConfirm) {
        AlertDialog(
            onDismissRequest = { showExportConfirm = false },
            title = { Text(stringResource(Res.string.archive_export_json)) },
            text = {
                Text(
                    stringResource(
                        Res.string.archive_export_size_warning,
                        (bytes / 1024 / 1024).coerceAtLeast(1L),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportConfirm = false
                    triggerExport()
                }) { Text(stringResource(Res.string.archive_export_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportConfirm = false }) {
                    Text(stringResource(Res.string.archive_onboarding_cancel))
                }
            },
        )
    }
}

@Composable
private fun MasterToggleRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val shapes = ListItemDefaults.segmentedShapes(0, 1, ListItemDefaults.shapes())
    SegmentedListItem(
        onClick = { onChange(!enabled) },
        shapes = shapes,
        leadingContent = {
            Symbol(
                name = "delete_sweep",
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 22.dp,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(Res.string.archive_master_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { Switch(checked = enabled, onCheckedChange = onChange) },
        content = {
            Text(
                text = stringResource(Res.string.archive_master_toggle),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

@Composable
private fun ToggleRow(
    symbol: String,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
) {
    val shapes = ListItemDefaults.segmentedShapes(index, count, ListItemDefaults.shapes())
    SegmentedListItem(
        onClick = { onCheckedChange(!checked) },
        shapes = shapes,
        leadingContent = { Symbol(name = symbol, size = 22.dp) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

@Composable
private fun DropdownRow(
    symbol: String,
    title: String,
    value: String,
    options: ImmutableList<Int>,
    labelOf: @Composable (Int) -> String,
    onPick: (Int) -> Unit,
    index: Int,
    count: Int,
) {
    var expanded by remember { mutableStateOf(false) }
    val shapes = ListItemDefaults.segmentedShapes(index, count, ListItemDefaults.shapes())
    SegmentedListItem(
        onClick = { expanded = true },
        shapes = shapes,
        leadingContent = { Symbol(name = symbol, size = 22.dp) },
        trailingContent = {
            Box {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(labelOf(o)) },
                            onClick = { onPick(o); expanded = false },
                        )
                    }
                }
            }
        },
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

@Composable
private fun DisableDialog(onKeep: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.archive_disable_title)) },
        text = { Text(stringResource(Res.string.archive_disable_body)) },
        confirmButton = {
            TextButton(onClick = onKeep) {
                Text(stringResource(Res.string.archive_disable_keep))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(Res.string.archive_disable_purge),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(Res.string.archive_onboarding_cancel))
                }
            }
        },
    )
}

@Composable
private fun retentionLabel(days: Int): String =
    if (days == Int.MAX_VALUE) {
        stringResource(Res.string.archive_retention_unlimited)
    } else {
        stringResource(Res.string.archive_retention_days, days)
    }

@Composable
private fun recordsLabel(n: Int): String =
    if (n == Int.MAX_VALUE) stringResource(Res.string.archive_records_unlimited) else groupThousands(n)

/** Space-grouped integer (KMP — no `java.text.NumberFormat`). e.g. 10000 → "10 000". */
private fun groupThousands(n: Int): String {
    val s = n.toString()
    val sb = StringBuilder()
    val rem = s.length % 3
    for (i in s.indices) {
        if (i > 0 && (i - rem) % 3 == 0) sb.append(' ')
        sb.append(s[i])
    }
    return sb.toString()
}

/** KMP byte formatter (no `String.format`). */
private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        bytes < 1024 -> "$bytes B"
        kb < 1024 -> "${oneDecimal(kb)} KB"
        else -> "${oneDecimal(kb / 1024.0)} MB"
    }
}

private fun oneDecimal(v: Double): String {
    val scaled = round(v * 10).toLong()
    return "${scaled / 10}.${scaled % 10}"
}
