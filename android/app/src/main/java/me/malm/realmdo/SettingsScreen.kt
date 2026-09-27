package me.malm.realmdo

import android.Manifest
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.net.URI
import java.time.ZoneId

/** A group of settings on a card, under a small caps label. */
@Composable
private fun Group(label: String, content: @Composable ColumnScope.() -> Unit) {
    SectionLabel(label, Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = LocalExtra.current.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: TasksViewModel, state: UiState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setLiveUpdate(true)
    }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Glyph.Back, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Group("Notification") {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pin the top task", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (TaskNotification.canPromote(ctx)) "Shown as a Live Update on the lock screen and status bar."
                            else "Shown as an ongoing notification. Live Updates need Android 16.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.liveUpdate,
                        onCheckedChange = { on ->
                            if (on && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else vm.setLiveUpdate(on)
                        },
                    )
                }
                if (state.liveUpdate) {
                    state.background?.let {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.padding(vertical = 12.dp)) { BackgroundCard(it) }
                    }
                }
            }
            state.timezone?.let { zone -> Group("Your day") { DaySection(vm, DaySettings(zone, state.slotTimes)) } }
            Group("Account") {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("Signed in as ${state.username}", style = MaterialTheme.typography.bodyLarge)
                    runCatching { URI(state.baseUrl).host }.getOrNull()?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(
                    onClick = { vm.signOut() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) { Text("Sign out", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/**
 * The time zone and the four slot start times. Each change is saved at once;
 * slot times out of order are refused here before the server sees them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySection(vm: TasksViewModel, day: DaySettings) {
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickZone by rememberSaveable { mutableStateOf(false) }
    var pickSlot by rememberSaveable { mutableStateOf<String?>(null) }
    val zones = remember { pickableZones() }
    val offer = phoneZoneOffer(day.timezone, ZoneId.systemDefault().id, zones)
    val save = { next: DaySettings ->
        val bad = slotOrderError(next.slotTimes)
        if (bad != null) error = bad
        else {
            busy = true
            error = null
            vm.saveDay(next) { err ->
                busy = false
                error = err
            }
        }
    }
    SettingRow("Time zone", zoneLabel(day.timezone), enabled = !busy) { pickZone = true }
    if (offer != null) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "This phone is on ${zoneLabel(offer)}.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            TextButton(onClick = { save(day.copy(timezone = offer)) }, enabled = !busy) { Text("Use it") }
        }
    }
    SLOTS.forEach { slot ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingRow(slotTitle(slot), day.slotTimes[slot] ?: "", enabled = !busy) { pickSlot = slot }
    }
    error?.let { Text(it, Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.error) }

    if (pickZone) {
        ZoneDialog(zones, day.timezone, onDismiss = { pickZone = false }) { zone ->
            pickZone = false
            if (zone != day.timezone) save(day.copy(timezone = zone))
        }
    }
    pickSlot?.let { slot ->
        val start = day.slotTimes[slot]?.let(::minutesOf) ?: 0
        val picker = rememberTimePickerState(start / 60, start % 60, is24Hour = DateFormat.is24HourFormat(ctx))
        AlertDialog(
            onDismissRequest = { pickSlot = null },
            title = { Text("${slotTitle(slot)} starts") },
            text = { TimePicker(picker) },
            confirmButton = {
                TextButton(onClick = {
                    pickSlot = null
                    val at = hhmm(picker.hour * 60 + picker.minute)
                    if (at != day.slotTimes[slot]) save(day.copy(slotTimes = day.slotTimes + (slot to at)))
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pickSlot = null }) { Text("Cancel") } },
        )
    }
}

/** A label with its value under it; tap to change. */
@Composable
private fun SettingRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every region zone, filtered as you type. */
@Composable
private fun ZoneDialog(zones: List<String>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val words = query.lowercase().split(' ', '/').filter { it.isNotBlank() }
    val shown = zones.filter { z -> zoneLabel(z).lowercase().let { l -> words.all { it in l } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time zone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, placeholder = { Text("Search, like Stockholm") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.height(360.dp)) {
                    items(shown, key = { it }) { zone ->
                        Text(
                            zoneLabel(zone),
                            Modifier.fillMaxWidth().clickable { onPick(zone) }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (zone == current) FontWeight.SemiBold else null,
                            color = if (zone == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The pinned task went stale in the background; the fix is under Settings. */
@Composable
fun HeldBanner(status: BackgroundRefresh.Status, onSettings: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(
                "The pinned task was not refreshed for ${BackgroundRefresh.label(status.heldMs)} while the app was in the background.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSettings) { Text("Show how to fix it") }
            }
        }
    }
}

/**
 * The guide: what the phone's battery settings do to the refresh alarm and
 * the buttons that open the right pages. Wording follows what was measured on
 * a Galaxy S24: "Unrestricted" alone is enough; Sleeping apps is the next
 * thing to check when it is not.
 */
@Composable
private fun BackgroundCard(status: BackgroundRefresh.Status) {
    val ctx = LocalContext.current
    val samsung = remember { BackgroundRefresh.samsungBatteryPage(ctx) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Background refresh", style = MaterialTheme.typography.bodyLarge)
        val text = when {
            status.restricted ->
                "Background use is restricted for this app, so the pinned task only changes while the app is open. " +
                    "Allow background use under App info, then choose Unrestricted."
            !status.exempt ->
                "Battery use is optimised. Samsung and some other phones then freeze the app in the background " +
                    "and hold its refreshes until it is opened, so the pinned task goes stale overnight. " +
                    "Allow unrestricted battery use to keep it current."
            status.held ->
                "Battery use is unrestricted, but a refresh was still held for ${BackgroundRefresh.label(status.heldMs)}. " +
                    "On Samsung, open Battery settings, then Background usage limits, and make sure " +
                    "the app is not under Sleeping apps or Deep sleeping apps."
            else ->
                "Battery use is unrestricted, so the pinned task keeps refreshing every 15 minutes in the background."
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            status.restricted -> OutlinedButton(
                onClick = { BackgroundRefresh.open(ctx, BackgroundRefresh.appInfo(ctx)) }, Modifier.fillMaxWidth(),
            ) { Text("Open app info") }
            !status.exempt -> Button(
                onClick = { BackgroundRefresh.open(ctx, BackgroundRefresh.exemptionDialog(ctx)) }, Modifier.fillMaxWidth(),
            ) { Text("Allow unrestricted battery use") }
        }
        if (samsung != null && (status.held || !status.exempt)) {
            OutlinedButton(onClick = { BackgroundRefresh.open(ctx, samsung) }, Modifier.fillMaxWidth()) {
                Text("Battery settings")
            }
        }
    }
}

@Composable
fun SetupScreen(vm: TasksViewModel, state: UiState) {
    val ctx = LocalContext.current
    var url by rememberSaveable { mutableStateOf("https://") }
    var token by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val connectWith = { u: String, t: String ->
        busy = true
        vm.connect(u, t) { err ->
            busy = false
            error = err
        }
    }
    val connect = { connectWith(url, token) }
    val scan = {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { code ->
                val pairing = Pairing.parse(code.rawValue)
                if (pairing == null) error = "Not a realmdo pairing code."
                else connectWith(pairing.baseUrl, pairing.token)
            }
            .addOnFailureListener { error = it.message ?: "scan failed" }
    }
    Scaffold { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppTitle()
            Spacer(Modifier.height(24.dp))
            ScreenTitle("Pair this phone", null)
            Text(
                "Create a token on the realmdo page in your Realm's Home, then scan its code or paste it here.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { scan() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Scan QR code")
            }
            SectionLabel("Or by hand", Modifier.padding(top = 12.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text("Server URL") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it }, label = { Text("Token") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { connect() }),
            )
            (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = connect, enabled = !busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (busy) "Connecting…" else "Connect")
            }
        }
    }
}
