package ai.kira.mobile

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import ai.kira.mobile.domain.model.GeneratedImage
import ai.kira.mobile.application.KiraContainer
import ai.kira.mobile.ui.theme.KiraPalette
import java.io.File
import java.util.concurrent.Executors

@Composable
internal fun KiraImageScreen(container: KiraContainer) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val images = remember { mutableStateListOf<GeneratedImage>() }
    var selectedImage by remember { mutableStateOf<GeneratedImage?>(null) }
    var endpoint by remember { mutableStateOf("https://api.openai.com/v1") }
    var model by remember { mutableStateOf("gpt-image-2.6-sunburst") }
    var apiKey by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("1024x1024") }
    var configured by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("برای شروع، Provider سازگار با OpenAI Images را تنظیم کن.") }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val current = selectedImage
        if (uri != null && current != null) {
            executor.execute {
                try {
                    val bitmap = BitmapFactory.decodeFile(current.localPath)
                        ?: throw IllegalStateException("تصویر ذخیره‌شده خوانده نشد.")
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            throw IllegalStateException("ذخیره تصویر ناموفق بود.")
                        }
                    } ?: throw IllegalStateException("مقصد ذخیره تصویر باز نشد.")
                    bitmap.recycle()
                    handler.post { status = "تصویر در محل انتخاب‌شده ذخیره شد." }
                } catch (error: Exception) {
                    handler.post { status = error.message ?: "ذخیره تصویر ناموفق بود." }
                }
            }
        }
    }

    DisposableEffect(executor) { onDispose { executor.shutdownNow() } }
    LaunchedEffect(Unit) {
        images.addAll(container.images.history())
        selectedImage = images.firstOrNull()
        if (container.images.hasSettings()) {
            val saved = container.images.settings()
            endpoint = saved.baseUrl
            model = saved.model
            configured = true
            status = "Provider تصویر آماده است."
        }
    }

    fun saveSettings() {
        val key = apiKey.trim()
        if (key.isBlank()) {
            status = "برای ذخیره یا تغییر تنظیمات، API Key را وارد کن."
            return
        }
        busy = true
        executor.execute {
            try {
                container.images.saveSettings(endpoint, model, key)
                handler.post {
                    apiKey = ""
                    configured = true
                    status = "تنظیمات Provider و کلید رمزنگاری شدند."
                    busy = false
                }
            } catch (error: Exception) {
                handler.post { status = error.message ?: "ذخیره تنظیمات ناموفق بود."; busy = false }
            }
        }
    }

    fun generate() {
        if (busy) return
        val text = prompt.trim()
        busy = true
        status = "در حال تولید تصویر…"
        executor.execute {
            try {
                val generated = container.images.generate(text, size)
                val history = container.images.history()
                handler.post {
                    images.clear()
                    images.addAll(history)
                    selectedImage = generated
                    status = "تصویر تولید و در حافظه خصوصی برنامه ذخیره شد."
                    busy = false
                }
            } catch (error: Exception) {
                handler.post {
                    status = error.message ?: "تولید تصویر ناموفق بود."
                    busy = false
                }
            }
        }
    }

    val preview = remember(selectedImage?.localPath) {
        selectedImage?.let { BitmapFactory.decodeFile(it.localPath)?.asImageBitmap() }
    }

    Column(
        Modifier.fillMaxSize().background(KiraPalette.background)
            .verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ساخت تصویر", color = KiraPalette.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text(
            "از هر سرویس‌دهنده سازگار با OpenAI Images API استفاده کن. هزینه و محدودیت مدل را پیش از ارسال بررسی کن.",
            color = KiraPalette.muted, fontSize = 12.sp, lineHeight = 18.sp
        )

        Surface(color = KiraPalette.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اتصال Provider", color = KiraPalette.onBackground, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(endpoint, { endpoint = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(model, { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Image Model ID") }, singleLine = true)
                OutlinedTextField(
                    apiKey, { apiKey = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (configured) "API Key جدید (اختیاری برای تولید)" else "API Key") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true
                )
                Button(onClick = { saveSettings() }, enabled = !busy && apiKey.isNotBlank()) {
                    Text(if (configured) "به‌روزرسانی اتصال" else "ذخیره اتصال امن")
                }
                if (configured) {
                    Text("کلید روی دستگاه و با Android Keystore رمزنگاری شده است.", color = KiraPalette.muted, fontSize = 11.sp)
                    Text("برای حذف اتصال و کلید ذخیره‌شده:", color = KiraPalette.muted, fontSize = 11.sp)
                    OutlinedButton(onClick = {
                        if (busy) return@OutlinedButton
                        busy = true
                        executor.execute {
                            try {
                                container.images.clearSettings()
                                handler.post {
                                    configured = false
                                    apiKey = ""
                                    endpoint = "https://api.openai.com/v1"
                                    model = "gpt-image-2.6-sunburst"
                                    status = "اتصال تصویر حذف شد."
                                    busy = false
                                }
                            } catch (error: Exception) {
                                handler.post {
                                    status = error.message ?: "حذف اتصال تصویر ناموفق بود."
                                    busy = false
                                }
                            }
                        }
                    }, enabled = !busy) { Text("حذف اتصال") }
                }
            }
        }

        OutlinedTextField(
            prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("توصیف تصویر") }, minLines = 3, maxLines = 6
        )
        Text("ابعاد تصویر", color = KiraPalette.onBackground, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1024x1024", "1024x1536", "1536x1024").forEach { option ->
                FilterChip(selected = size == option, onClick = { size = option }, label = { Text(option) })
            }
        }
        Button(
            onClick = { generate() },
            enabled = configured && !busy && prompt.trim().length >= 3,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = KiraPalette.primaryContainer)
        ) { Text(if (busy) "در حال تولید…" else "تولید تصویر") }
        Text(status, color = KiraPalette.muted, fontSize = 11.sp)

        preview?.let { bitmap ->
            Surface(color = KiraPalette.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(bitmap, contentDescription = selectedImage?.prompt, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit)
                    Text(selectedImage?.prompt.orEmpty(), color = KiraPalette.onBackground, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { saveLauncher.launch("kira-image.png") }) { Text("ذخیره تصویر") }
                        OutlinedButton(onClick = {
                            val image = selectedImage ?: return@OutlinedButton
                            runCatching {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "ai.kira.mobile.fileprovider",
                                    File(image.localPath)
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newUri(context.contentResolver, "Kira image", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "اشتراک تصویر"))
                            }.onFailure { status = it.message ?: "اشتراک تصویر ناموفق بود." }
                        }) { Text("اشتراک‌گذاری") }
                    }
                }
            }
        }

        if (images.isNotEmpty()) {
            Text("تاریخچه تصاویر", color = KiraPalette.onBackground, fontWeight = FontWeight.SemiBold)
            images.forEach { image ->
                OutlinedButton(
                    onClick = { selectedImage = image },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(image.prompt.take(100), maxLines = 2)
                }
            }
        }
    }
}
