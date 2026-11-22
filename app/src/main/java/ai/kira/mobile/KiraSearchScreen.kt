package ai.kira.mobile

import ai.kira.mobile.application.KiraContainer
import ai.kira.mobile.domain.model.InternetSearchResponse
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors

@Composable
internal fun KiraSearchScreen(container: KiraContainer, onUseInChat: (String) -> Unit) {
    val executor = remember { Executors.newSingleThreadExecutor() }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val uriHandler = LocalUriHandler.current
    var query by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(container.search.hasApiKey()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("برای جستجو، کلید Tavily را یک‌بار ذخیره کن.") }
    var response by remember { mutableStateOf<InternetSearchResponse?>(null) }

    DisposableEffect(executor) { onDispose { executor.shutdownNow() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("جستجوی اینترنتی", style = MaterialTheme.typography.headlineSmall)
        Text("جستجو، استخراج محتوا، منابع و ارجاعات؛ انتقال شواهد به چت با برچسب داده بیرونی.")
        if (!keySaved) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tavily API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Button(onClick = {
                loading = true
                val key = apiKey
                executor.execute {
                    try {
                        container.search.saveApiKey(key)
                        handler.post { apiKey = ""; keySaved = true; status = "کلید با Android Keystore ذخیره شد."; loading = false }
                    } catch (error: Exception) {
                        handler.post { status = error.message ?: "ذخیره کلید ناموفق بود."; loading = false }
                    }
                }
            }, enabled = !loading && apiKey.isNotBlank()) { Text("ذخیره امن کلید") }
        } else {
            Text("کلید جستجو در Android Keystore رمزنگاری شده است.")
            TextButton(onClick = {
                loading = true
                executor.execute {
                    try {
                        container.search.clearApiKey()
                        handler.post { keySaved = false; response = null; status = "کلید حذف شد."; loading = false }
                    } catch (error: Exception) {
                        handler.post { status = error.message ?: "حذف کلید ناموفق بود."; loading = false }
                    }
                }
            }, enabled = !loading) { Text("حذف کلید") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("عبارت جستجو") },
            minLines = 2,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (keySaved && !loading && query.trim().length >= 2) {
                    loading = true
                    val requested = query
                    executor.execute {
                        try {
                            val found = container.search.search(requested)
                            handler.post { response = found; status = found.results.size.toString() + " منبع دریافت شد."; loading = false }
                        } catch (error: Exception) {
                            handler.post { status = error.message ?: "جستجو ناموفق بود."; loading = false }
                        }
                    }
                }
            })
        )
        Button(onClick = {
            loading = true
            val requested = query
            executor.execute {
                try {
                    val found = container.search.search(requested)
                    handler.post { response = found; status = found.results.size.toString() + " منبع دریافت شد."; loading = false }
                } catch (error: Exception) {
                    handler.post { status = error.message ?: "جستجو ناموفق بود."; loading = false }
                }
            }
        }, enabled = keySaved && !loading && query.trim().length >= 2, modifier = Modifier.fillMaxWidth()) {
            Text(if (loading) "در حال جستجو…" else "جستجو در اینترنت")
        }
        Text(status, style = MaterialTheme.typography.bodySmall)

        response?.let { found ->
            if (found.answer.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("خلاصه اولیه سرویس جستجو", style = MaterialTheme.typography.titleMedium)
                        Text(found.answer)
                        Text("خلاصه جایگزین بررسی منابع نیست.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            found.results.forEach { source ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("[" + source.number + "] " + source.title, style = MaterialTheme.typography.titleMedium)
                        Text(source.excerpt, style = MaterialTheme.typography.bodySmall)
                        if (source.extractedContent.isNotBlank()) {
                            Text(source.extractedContent.take(900), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { runCatching { uriHandler.openUri(source.url) } }) {
                            Text("باز کردن منبع [" + source.number + "]")
                        }
                    }
                }
            }
            if (found.results.isNotEmpty()) {
                Button(
                    onClick = { onUseInChat(container.search.formatForChat(query, found)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("ارسال شواهد و ارجاعات به چت") }
            }
        }
    }
}
