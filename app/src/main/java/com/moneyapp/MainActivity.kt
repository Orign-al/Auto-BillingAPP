package com.moneyapp

import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneyapp.data.SettingsRepository
import com.moneyapp.data.SettingsState
import com.moneyapp.db.OcrRecord
import com.moneyapp.monitor.ScreenshotMonitorService
import com.moneyapp.repository.OcrRepository
import com.moneyapp.ui.theme.MoneyAppTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SettingsRepository(this)
        val ocrRepository = OcrRepository(this)

        setContent {
            MoneyAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(repository = repository, ocrRepository = ocrRepository)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(repository: SettingsRepository, ocrRepository: OcrRepository) {
    val settingsState by repository.settingsFlow.collectAsStateWithLifecycle(
        initialValue = SettingsState("", "", "", "")
    )
    val records by ocrRepository.observeRecords().collectAsStateWithLifecycle(initialValue = emptyList())
    var editable by remember { mutableStateOf(settingsState) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isMonitoring by remember { mutableStateOf(false) }
    var permissionWarning by remember { mutableStateOf("") }
    var selectedRecord by remember { mutableStateOf<OcrRecord?>(null) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startMonitoring(context)
            isMonitoring = true
            permissionWarning = ""
        } else {
            permissionWarning = "Permissions required to monitor screenshots"
        }
    }

    LaunchedEffect(settingsState) {
        editable = settingsState
    }

    val statusColor by animateColorAsState(
        targetValue = if (settingsState.isConfigured) Color(0xFF0F766E) else Color(0xFFB42318),
        label = "statusColor"
    )

    selectedRecord?.let { record ->
        EditRecordSheet(
            record = record,
            onDismiss = { selectedRecord = null },
            onSave = { updated ->
                scope.launch {
                    ocrRepository.updateRecord(updated)
                    snackbarHostState.showSnackbar("Record updated")
                    selectedRecord = null
                }
            },
            onUpload = { updated ->
                scope.launch {
                    val success = ocrRepository.uploadRecord(updated)
                    val status = if (success) "uploaded" else "failed"
                    ocrRepository.updateRecord(updated.copy(uploadStatus = status))
                    snackbarHostState.showSnackbar(
                        if (success) "Uploaded to ezBookkeeping" else "Upload failed"
                    )
                    selectedRecord = null
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "MoneyApp", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (settingsState.isConfigured) "Ready to sync" else "Setup required",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                )
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusCard(
                    isReady = settingsState.isConfigured,
                    statusColor = statusColor
                )

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SectionTitle(title = "Screenshot OCR")
                        Text(
                            text = "Monitor screenshots and extract text automatically.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        if (permissionWarning.isNotBlank()) {
                            Text(
                                text = permissionWarning,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                        Button(
                            onClick = {
                                if (hasAllPermissions(context)) {
                                    if (isMonitoring) {
                                        stopMonitoring(context)
                                        isMonitoring = false
                                    } else {
                                        startMonitoring(context)
                                        isMonitoring = true
                                    }
                                } else {
                                    permissionLauncher.launch(requiredPermissions())
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            val label = if (isMonitoring) "Stop monitoring" else "Start monitoring"
                            Text(text = label)
                        }
                    }
                }

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SectionTitle(title = "Server settings")
                        OutlinedTextField(
                            value = editable.host,
                            onValueChange = { editable = editable.copy(host = it) },
                            label = { Text("Base URL") },
                            placeholder = { Text("https://your-host") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editable.token,
                            onValueChange = { editable = editable.copy(token = it) },
                            label = { Text("API token") },
                            placeholder = { Text("Bearer token") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SectionTitle(title = "Defaults")
                        OutlinedTextField(
                            value = editable.defaultAccountId,
                            onValueChange = { editable = editable.copy(defaultAccountId = it) },
                            label = { Text("Default account ID") },
                            placeholder = { Text("e.g. acc_123") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editable.defaultCategoryId,
                            onValueChange = { editable = editable.copy(defaultCategoryId = it) },
                            label = { Text("Default category ID") },
                            placeholder = { Text("e.g. cat_food") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SectionTitle(title = "Recent OCR")
                        if (records.isEmpty()) {
                            Text(
                                text = "No OCR records yet. Take a screenshot to start.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.height(260.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(records) { record ->
                                    RecordCard(record = record, onEdit = { selectedRecord = record })
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            repository.save(editable)
                            snackbarHostState.showSnackbar("Settings saved")
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Outlined.Save,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Save settings")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(isReady: Boolean, statusColor: Color) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = if (isReady) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = statusColor
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isReady) "Connected" else "Not configured",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isReady) "Sync is ready for OCR uploads" else "Add server URL and token to start",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun RecordCard(record: OcrRecord, onEdit: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.merchant ?: record.displayName,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = buildString {
                        val amount = record.amountMinor?.let { formatAmount(it) } ?: "--"
                        append(amount)
                        record.categoryGuess?.let { append(" · ").append(it) }
                        record.uploadStatus?.let { append(" · ").append(it) }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Button(onClick = onEdit, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Edit")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditRecordSheet(
    record: OcrRecord,
    onDismiss: () -> Unit,
    onSave: (OcrRecord) -> Unit,
    onUpload: (OcrRecord) -> Unit
) {
    var merchant by remember(record) { mutableStateOf(record.merchant.orEmpty()) }
    var amount by remember(record) { mutableStateOf(record.amountMinor?.let { formatAmount(it) }.orEmpty()) }
    var currency by remember(record) { mutableStateOf(record.currency ?: "CNY") }
    var payTime by remember(record) { mutableStateOf(record.payTime?.let { formatTime(it) }.orEmpty()) }
    var accountId by remember(record) { mutableStateOf(record.accountId.orEmpty()) }
    var categoryId by remember(record) { mutableStateOf(record.categoryId.orEmpty()) }
    var comment by remember(record) { mutableStateOf(record.comment.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Edit OCR result", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("Merchant") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = currency,
                onValueChange = { currency = it },
                label = { Text("Currency") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = payTime,
                onValueChange = { payTime = it },
                label = { Text("Pay time (yyyy-MM-dd HH:mm)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = accountId,
                onValueChange = { accountId = it },
                label = { Text("Account ID") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = categoryId,
                onValueChange = { categoryId = it },
                label = { Text("Category ID") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                label = { Text("Comment") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        onSave(
                            record.copy(
                                merchant = merchant.ifBlank { null },
                                amountMinor = parseAmount(amount),
                                currency = currency.ifBlank { null },
                                payTime = parseTime(payTime),
                                accountId = accountId.ifBlank { null },
                                categoryId = categoryId.ifBlank { null },
                                comment = comment.ifBlank { null }
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
                Button(
                    onClick = {
                        onUpload(
                            record.copy(
                                merchant = merchant.ifBlank { null },
                                amountMinor = parseAmount(amount),
                                currency = currency.ifBlank { null },
                                payTime = parseTime(payTime),
                                accountId = accountId.ifBlank { null },
                                categoryId = categoryId.ifBlank { null },
                                comment = comment.ifBlank { null }
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Upload")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun requiredPermissions(): Array<String> {
    val permissions = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions += android.Manifest.permission.READ_MEDIA_IMAGES
        permissions += android.Manifest.permission.POST_NOTIFICATIONS
    } else {
        permissions += android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return permissions.toTypedArray()
}

private fun hasAllPermissions(context: android.content.Context): Boolean {
    return requiredPermissions().all { permission ->
        PermissionChecker.checkSelfPermission(context, permission) == PermissionChecker.PERMISSION_GRANTED
    }
}

private fun startMonitoring(context: android.content.Context) {
    val intent = android.content.Intent(context, ScreenshotMonitorService::class.java)
    ContextCompat.startForegroundService(context, intent)
}

private fun stopMonitoring(context: android.content.Context) {
    val intent = android.content.Intent(context, ScreenshotMonitorService::class.java)
    context.stopService(intent)
}

private fun formatAmount(minor: Long): String {
    return String.format(Locale.getDefault(), "%.2f", minor / 100.0)
}

private fun parseAmount(value: String): Long? {
    val clean = value.trim().replace(",", "")
    val number = clean.toDoubleOrNull() ?: return null
    return (number * 100).toLong()
}

private fun formatTime(epochSeconds: Long): String {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return format.format(Date(epochSeconds * 1000))
}

private fun parseTime(value: String): Long? {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val date = format.parse(value.trim()) ?: return null
        date.time / 1000
    } catch (ex: Exception) {
        null
    }
}
