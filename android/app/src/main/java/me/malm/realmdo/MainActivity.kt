package me.malm.realmdo

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var vm: TasksViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[TasksViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) {
                val model: TasksViewModel = viewModel()
                App(model)
            }
        }
        pairFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.resumed()
    }

    /** A `realmdo://pair` link opened from the camera app or any scanner. */
    private fun pairFrom(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val pairing = Pairing.parse(intent.dataString) ?: return
        intent.data = null // once, not again on rotation
        vm.connect(pairing.baseUrl, pairing.token) { err ->
            Toast.makeText(this, err ?: "Paired as ${vm.state.value.username}", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun App(vm: TasksViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    when {
        !state.configured -> SetupScreen(vm, state)
        showSettings -> SettingsScreen(vm, state, onBack = { showSettings = false })
        else -> TasksScreen(vm, state, onSettings = { showSettings = true })
    }
}

/** The mark next to the app name, for the title bars. */
@Composable
private fun AppTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.mark), contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text("realmdo")
    }
}

/** A full turn per second while [active], else at rest. */
@Composable
private fun spin(active: Boolean): Float {
    if (!active) return 0f
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "angle",
    )
    return angle
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksScreen(vm: TasksViewModel, state: UiState, onSettings: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var sheetId by rememberSaveable { mutableStateOf<Long?>(null) }
    val submit = {
        val t = title.trim()
        if (t.isNotEmpty()) {
            vm.add(t)
            title = ""
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { AppTitle() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, "Refresh", Modifier.rotate(spin(state.loading)))
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Add a task") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
            state.background?.let { if (state.liveUpdate && it.held) HeldBanner(it, onSettings) }
            val visible = state.tasks.filter { it.urgency >= 0 }
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { task ->
                    ListItem(
                        modifier = if (task.notes == null) Modifier else Modifier.clickable { sheetId = task.id },
                        headlineContent = {
                            Text(task.title, fontWeight = if (task.important) FontWeight.Bold else null)
                        },
                        supportingContent = { detail(task)?.let { Text(it) } },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (task.notes != null) Icon(Icons.AutoMirrored.Filled.List, "Has notes")
                                IconButton(onClick = { vm.done(task) }) { Icon(Icons.Default.Check, "Done") }
                            }
                        },
                    )
                    HorizontalDivider()
                }
                if (visible.isEmpty() && !state.loading) {
                    item { Text("Nothing to do.", Modifier.padding(16.dp)) }
                }
            }
        }
    }
    // Looked up by id so the sheet follows refreshes; it closes if the task goes.
    val sheetTask = sheetId?.let { id -> state.tasks.find { it.id == id && it.notes != null } }
    if (sheetTask != null) {
        NotesSheet(sheetTask, onDone = { vm.done(sheetTask) }, onDismiss = { sheetId = null })
    } else if (sheetId != null) {
        LaunchedEffect(Unit) { sheetId = null }
    }
}

private val URL = Regex("""https?://\S+""")

/** Notes as text, with bare http(s) URLs made tappable. */
@Composable
private fun notesText(notes: String): AnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0
        for (m in URL.findAll(notes)) {
            append(notes.substring(at, m.range.first))
            withLink(LinkAnnotation.Url(m.value, style)) { append(m.value) }
            at = m.range.last + 1
        }
        append(notes.substring(at))
    }
}

/** A task's notes over the list, with Done at hand. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesSheet(task: Task, onDone: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(task.title, style = MaterialTheme.typography.titleLarge)
            detail(task)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Text(notesText(task.notes.orEmpty()), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onDone()
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                },
                Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("Done")
            }
        }
    }
}

/** The pinned task went stale in the background; the fix is under Settings. */
@Composable
private fun HeldBanner(status: BackgroundRefresh.Status, onSettings: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
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
        Text(text, style = MaterialTheme.typography.bodySmall)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: TasksViewModel, state: UiState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setLiveUpdate(true)
    }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Signed in as ${state.username}", style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pin the top task", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (TaskNotification.canPromote(ctx)) "Shown as a Live Update on the lock screen and status bar."
                        else "Shown as an ongoing notification. Live Updates need Android 16.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.liveUpdate,
                    onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else vm.setLiveUpdate(on)
                    },
                )
            }
            if (state.liveUpdate) state.background?.let { BackgroundCard(it) }
            OutlinedButton(onClick = { vm.signOut() }, Modifier.fillMaxWidth()) { Text("Sign out") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(vm: TasksViewModel, state: UiState) {
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
    Scaffold(topBar = { TopAppBar(title = { AppTitle() }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Create a token under Settings in the web UI, then scan its code or paste it here.")
            Button(onClick = { scan() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Scan QR code")
            }
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
            Button(onClick = connect, enabled = !busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Connecting…" else "Connect")
            }
        }
    }
}
