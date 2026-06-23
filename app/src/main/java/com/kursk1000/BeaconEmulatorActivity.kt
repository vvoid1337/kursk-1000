package com.kursk1000

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.kursk1000.ui.theme.Kursk1000Theme

/**
 * Приложение-эмулятор метки (TZ: отдельный артефакт для демо «атака vs защита»).
 *
 * Отдельная launcher-Activity в том же модуле (своя иконка «Эмулятор метки»): так напрямую
 * переиспользуются [BeaconCode] и Keystore-секреты гида без отдельного Gradle-модуля. Список
 * меток и секреты берутся с бекенда (как у гида). Вещает либо **защищённую** метку (ротирующийся
 * HMAC-код — гид показывает «Подлинная метка»), либо **уязвимую** (только UUID без кода, как
 * клон — гид показывает предупреждение).
 */
class BeaconEmulatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Kursk1000Theme {
                EmulatorScreen()
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun EmulatorScreen(
    viewModel: BeaconEmulatorViewModel = viewModel(factory = BeaconEmulatorViewModel.Factory),
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        // BLE-вещание на Android 12+ требует runtime-разрешение BLUETOOTH_ADVERTISE; на 11 и
        // ниже хватает обычного BLUETOOTH_ADMIN из манифеста — там гейт не нужен.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permission = rememberPermissionState(Manifest.permission.BLUETOOTH_ADVERTISE)
            if (!permission.status.isGranted) {
                PermissionGate(
                    modifier = Modifier.padding(padding),
                    onRequest = { permission.launchPermissionRequest() },
                )
                return@Scaffold
            }
        }
        EmulatorContent(viewModel = viewModel, modifier = Modifier.padding(padding))
    }
}

@Composable
private fun PermissionGate(modifier: Modifier = Modifier, onRequest: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.emu_permission_required),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.grant_permissions)) }
    }
}

@Composable
private fun EmulatorContent(viewModel: BeaconEmulatorViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uuids by viewModel.availableUuids.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.emu_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.emu_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.emu_uuid_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        if (uuids.isEmpty()) {
            // Меток ещё нет: бекенд недоступен / первая синхронизация не прошла — вещать нечем.
            Text(
                text = stringResource(R.string.emu_no_beacons),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            UuidDropdown(
                uuids = uuids,
                selected = state.uuid,
                enabled = !state.advertising,
                onSelect = viewModel::selectUuid,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.emu_mode_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.mode == BeaconMode.PROTECTED,
                onClick = { viewModel.selectMode(BeaconMode.PROTECTED) },
                label = { Text(stringResource(R.string.emu_mode_protected)) },
            )
            FilterChip(
                selected = state.mode == BeaconMode.VULNERABLE,
                onClick = { viewModel.selectMode(BeaconMode.VULNERABLE) },
                label = { Text(stringResource(R.string.emu_mode_vulnerable)) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (state.mode == BeaconMode.PROTECTED) R.string.emu_mode_protected_desc
                else R.string.emu_mode_vulnerable_desc
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.emu_txpower_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TxPowerChip(state.txPower, BeaconTxPower.LOW, R.string.emu_tx_low, viewModel::selectTxPower)
            TxPowerChip(state.txPower, BeaconTxPower.MEDIUM, R.string.emu_tx_medium, viewModel::selectTxPower)
            TxPowerChip(state.txPower, BeaconTxPower.HIGH, R.string.emu_tx_high, viewModel::selectTxPower)
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = viewModel::toggleAdvertising,
            enabled = state.supported && state.uuid.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = if (state.advertising) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(
                stringResource(
                    if (state.advertising) R.string.emu_stop else R.string.emu_start
                )
            )
        }

        Spacer(Modifier.height(20.dp))
        StatusCard(state)
    }
}

@Composable
private fun TxPowerChip(
    current: BeaconTxPower,
    value: BeaconTxPower,
    @androidx.annotation.StringRes labelRes: Int,
    onSelect: (BeaconTxPower) -> Unit,
) {
    FilterChip(
        selected = current == value,
        onClick = { onSelect(value) },
        label = { Text(stringResource(labelRes)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UuidDropdown(
    uuids: List<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.emu_uuid_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            uuids.forEach { uuid ->
                DropdownMenuItem(
                    text = { Text(uuid, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onSelect(uuid)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: EmulatorUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Декоративная иконка статуса: текст рядом несёт смысл, прячем от screen reader.
                Text(
                    text = if (state.advertising) "📡" else "⏸️",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(state.statusRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Счётчик показываем только когда реально ротируем код (защищённая метка в эфире).
            if (state.advertising && state.mode == BeaconMode.PROTECTED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.emu_counter, state.counter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
