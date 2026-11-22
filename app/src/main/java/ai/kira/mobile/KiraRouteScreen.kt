package ai.kira.mobile

import ai.kira.mobile.application.provider.ProviderApplicationService
import ai.kira.mobile.domain.model.ProviderConnection
import ai.kira.mobile.domain.policy.FreeModelPolicy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors

@Composable
internal fun KiraRouteScreen(service: ProviderApplicationService) {
    val executor = remember { Executors.newSingleThreadExecutor() }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val routes = remember { mutableStateListOf<ProviderConnection>() }
    val models = remember { mutableStateListOf<String>() }
    var providerId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("API Key را وارد کن؛ Provider و مدل رایگان خودکار تشخیص داده می‌شوند.") }

    DisposableEffect(executor) { onDispose { executor.shutdownNow() } }

    fun refreshRoutesAsync() {
        executor.execute {
            try {
                val loaded = service.providers()
                handler.post {
                    routes.clear()
                    routes.addAll(loaded)
                }
            } catch (error: Exception) {
                handler.post { status = error.message ?: "بازیابی Routeها ناموفق بود." }
            }
        }
    }

    fun applyModelResult(result: ai.kira.mobile.domain.model.ProviderOperationResult, message: String) {
        providerId = result.provider.providerId
        selectedModel = result.provider.modelId
        models.clear()
        models.addAll(result.availableModels)
        refreshRoutesAsync()
        status = message
    }

    fun refreshModels() {
        if (busy || providerId.isBlank()) return
        busy = true
        status = "در حال دریافت دوباره مدل‌های تأییدشده…"
        executor.execute {
            try {
                val result = service.refresh(providerId)
                handler.post { applyModelResult(result, "فهرست مدل‌های رایگان تازه شد."); busy = false }
            } catch (error: Exception) {
                handler.post { status = error.message ?: "تازه‌سازی مدل‌ها ناموفق بود."; busy = false }
            }
        }
    }

    fun updateRoute(route: ProviderConnection, enabled: Boolean? = null, delta: Int = 0) {
        if (busy) return
        busy = true
        executor.execute {
            try {
                val updated = when {
                    enabled != null -> service.setEnabled(route.providerId, enabled)
                    delta != 0 -> service.move(route.providerId, delta)
                    else -> service.providers()
                }
                handler.post {
                    routes.clear()
                    routes.addAll(updated)
                    status = "تنظیمات Route ذخیره شد."
                    busy = false
                }
            } catch (error: Exception) {
                handler.post { status = error.message ?: "تغییر Route ناموفق بود."; busy = false }
            }
        }
    }

    LaunchedEffect(service) {
        executor.execute {
            try {
                val loadedRoutes = service.providers()
                val selected = loadedRoutes.firstOrNull { it.enabled } ?: loadedRoutes.firstOrNull()
                val available = selected?.let { service.availableModels(it.providerId) }.orEmpty()
                handler.post {
                    routes.clear()
                    routes.addAll(loadedRoutes)
                    if (selected != null) {
                        providerId = selected.providerId
                        selectedModel = selected.modelId
                        models.clear()
                        models.addAll(available)
                    }
                }
            } catch (error: Exception) {
                handler.post { status = error.message ?: "بازیابی اتصال‌ها ناموفق بود." }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("اتصال API و مدل‌ها", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val key = apiKey.trim()
                if (busy || key.isBlank()) return@Button
                busy = true
                status = "در حال تشخیص Provider و دریافت مدل‌ها…"
                executor.execute {
                    try {
                        val result = service.connect(key)
                        handler.post {
                            apiKey = ""
                            applyModelResult(result, "اتصال برقرار شد؛ مدل رایگان انتخاب شد.")
                            busy = false
                        }
                    } catch (error: Exception) {
                        handler.post { status = error.message ?: "اتصال ناموفق بود."; busy = false }
                    }
                }
            },
            enabled = !busy && apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("اتصال و انتخاب مدل رایگان") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("مدل فعال", style = MaterialTheme.typography.labelMedium)
                Text(selectedModel.ifBlank { "هنوز مدلی انتخاب نشده" }, style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(
                onClick = { refreshModels() },
                enabled = !busy && providerId.isNotBlank()
            ) {
                Text("به‌روزرسانی فهرست مدل‌ها")
            }
        }
        if (providerId.isNotBlank() && models.isEmpty()) {
            Text("فهرست مدل‌ها خالی است؛ برای دریافت دوباره دکمهٔ بالا را بزن.", style = MaterialTheme.typography.bodySmall)
        }
        if (models.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { expanded = true }, enabled = !busy) {
                        Text("مدل‌های رایگان (" + models.size + ")")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        models.forEach { model ->
                            DropdownMenuItem(text = { Text(model) }, onClick = {
                                expanded = false
                                if (!busy) {
                                    busy = true
                                    executor.execute {
                                        try {
                                            val result = service.selectModel(providerId, model)
                                            handler.post { applyModelResult(result, "مدل ذخیره شد."); busy = false }
                                        } catch (error: Exception) {
                                            handler.post { status = error.message ?: "انتخاب مدل ناموفق بود."; busy = false }
                                        }
                                    }
                                }
                            })
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (!busy) {
                            val recommended = FreeModelPolicy.recommendedModel(providerId, models.toList())
                            busy = true
                            executor.execute {
                                try {
                                    val result = service.selectModel(providerId, recommended)
                                    handler.post { applyModelResult(result, "مدل پیشنهادی انتخاب شد."); busy = false }
                                } catch (error: Exception) {
                                    handler.post { status = error.message ?: "انتخاب مدل ناموفق بود."; busy = false }
                                }
                            }
                        }
                    },
                    enabled = !busy
                ) { Text("انتخاب هوشمند") }
            }
        }
        Text("ترتیب Routeها و Failover", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(routes, key = { _, route -> route.providerId }) { index, route ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(route.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(route.modelId, style = MaterialTheme.typography.bodySmall)
                            Text("اولویت " + (index + 1), style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Switch(
                                checked = route.enabled,
                                enabled = !busy,
                                onCheckedChange = { enabled -> updateRoute(route, enabled = enabled) }
                            )
                            Row {
                                TextButton(onClick = { updateRoute(route, delta = -1) }, enabled = !busy && index > 0) { Text("↑") }
                                TextButton(onClick = { updateRoute(route, delta = 1) }, enabled = !busy && index < routes.lastIndex) { Text("↓") }
                            }
                        }
                    }
                }
            }
            if (routes.isEmpty()) item {
                Text("هنوز Provider متصل نشده است.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
