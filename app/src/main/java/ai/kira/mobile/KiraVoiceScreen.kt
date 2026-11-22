package ai.kira.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.kira.mobile.application.voice.VoiceCallState
import ai.kira.mobile.application.KiraContainer
import ai.kira.mobile.domain.model.ShenavaModelVariant
import ai.kira.mobile.ui.theme.KiraPalette
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun KiraVoiceScreen(container: KiraContainer, onUseInChat: (String) -> Unit = {}) {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val screenDisposed = remember { AtomicBoolean(false) }
    val voice = container.voice
    val shenavaAsr = container.shenavaAsr
    val speech = container.speech
    var kittenDownloaded by remember { mutableStateOf(speech.isKittenModelDownloaded()) }
    var kittenBusy by remember { mutableStateOf(false) }
    var kittenProgress by remember { mutableIntStateOf(0) }
    var kittenStatus by remember { mutableStateOf(if (kittenDownloaded) "مدل Kitten آماده است؛ صدای انگلیسی آفلاین است." else "برای صدای انگلیسی، مدل Kitten را یک‌بار دانلود کن.") }
    val preferences = remember(context) {
        context.getSharedPreferences("kira_voice_settings", android.content.Context.MODE_PRIVATE)
    }
    var selectedShenavaVariant by remember(context) {
        mutableStateOf(ShenavaModelVariant.fromStorageKey(preferences.getString("shenava_variant", "int8")))
    }
    var shenavaDownloaded by remember(selectedShenavaVariant) {
        mutableStateOf(shenavaAsr.isModelDownloaded(selectedShenavaVariant))
    }
    var shenavaDownloadProgress by remember { mutableIntStateOf(0) }
    var shenavaBusy by remember { mutableStateOf(false) }
    var shenavaRecording by remember { mutableStateOf(false) }
    var shenavaStatus by remember {
        mutableStateOf(
            if (shenavaAsr.isModelDownloaded(selectedShenavaVariant))
                "مدل انتخاب‌شده آماده است؛ پردازش گفتار کاملاً آفلاین انجام می‌شود."
            else "برای استفاده آفلاین، ابتدا مدل شنوا را دانلود کن."
        )
    }
    var shenavaTranscript by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(voice.hasApiKey()) }
    var status by remember { mutableStateOf("Gemini Live از مکالمه صوتی دوطرفه پشتیبانی می‌کند.") }
    var callActive by remember { mutableStateOf(voice.isActive()) }
    var muted by remember { mutableStateOf(voice.isMuted()) }
    var busy by remember { mutableStateOf(false) }
    val transcript = remember { mutableStateListOf<String>() }

    DisposableEffect(executor, voice, shenavaAsr) {
        onDispose {
            screenDisposed.set(true)
            runCatching { voice.stop() }
            runCatching { shenavaAsr.cancelRecording() }
            executor.shutdownNow()
        }
    }

    fun downloadKitten() {
        if (kittenBusy || busy || callActive || shenavaRecording || shenavaBusy) return
        kittenBusy = true
        kittenProgress = 0
        kittenStatus = "در حال دانلود مدل Kitten TTS…"
        executor.execute {
            try {
                speech.downloadKittenModel { progress ->
                    handler.post {
                        kittenProgress = progress.coerceIn(0, 100)
                        kittenStatus = "دانلود Kitten TTS: $progress%"
                    }
                }
                handler.post {
                    kittenDownloaded = speech.isKittenModelDownloaded()
                    kittenProgress = 100
                    kittenStatus = "✓ مدل Kitten دانلود و اعتبارسنجی شد؛ برای متن انگلیسی آماده است."
                    kittenBusy = false
                }
            } catch (error: Exception) {
                handler.post {
                    kittenDownloaded = speech.isKittenModelDownloaded()
                    kittenStatus = error.message ?: "دانلود Kitten TTS ناموفق بود."
                    kittenBusy = false
                }
            }
        }
    }

    fun selectShenavaVariant(variant: ShenavaModelVariant) {
        if (shenavaBusy || shenavaRecording || callActive) return
        selectedShenavaVariant = variant
        preferences.edit().putString("shenava_variant", variant.storageKey).apply()
        shenavaDownloaded = shenavaAsr.isModelDownloaded(variant)
        shenavaDownloadProgress = if (shenavaDownloaded) 100 else 0
        shenavaTranscript = ""
        shenavaStatus = if (shenavaDownloaded) {
            "${variant.displayName} آماده است؛ برای تشخیص گفتار به اینترنت نیاز نیست."
        } else {
            "${variant.displayName} هنوز دانلود نشده است."
        }
    }

    fun downloadShenavaModel() {
        if (shenavaBusy || shenavaRecording || callActive || busy) return
        val variant = selectedShenavaVariant
        shenavaBusy = true
        shenavaDownloadProgress = 0
        shenavaStatus = "در حال دانلود ${variant.displayName} از Hugging Face…"
        executor.execute {
            try {
                shenavaAsr.downloadModel(variant) { progress ->
                    handler.post {
                        shenavaDownloadProgress = progress.coerceIn(0, 100)
                        shenavaStatus = "دانلود ${variant.displayName}: $progress%"
                    }
                }
                val ready = shenavaAsr.isModelDownloaded(variant)
                handler.post {
                    shenavaDownloaded = ready
                    shenavaDownloadProgress = if (ready) 100 else shenavaDownloadProgress
                    shenavaStatus = if (ready) {
                        "✓ ${variant.displayName} دانلود و اعتبارسنجی شد. اکنون می‌توانی بدون اینترنت از آن استفاده کنی."
                    } else {
                        "دانلود تمام شد اما فایل‌های مدل کامل نیستند؛ دوباره تلاش کن."
                    }
                    shenavaBusy = false
                }
            } catch (error: Exception) {
                handler.post {
                    shenavaDownloaded = shenavaAsr.isModelDownloaded(variant)
                    shenavaStatus = error.message ?: "دانلود مدل شنوا ناموفق بود."
                    shenavaBusy = false
                }
            }
        }
    }

    fun startShenavaRecording() {
        if (shenavaBusy || shenavaRecording || callActive || busy) return
        val variant = selectedShenavaVariant
        if (!shenavaAsr.isModelDownloaded(variant)) {
            shenavaStatus = "ابتدا نسخه انتخاب‌شده شنوا را دانلود کن."
            return
        }
        shenavaBusy = true
        shenavaStatus = "در حال آماده‌سازی میکروفون محلی…"
        executor.execute {
            try {
                if (screenDisposed.get()) return@execute
                shenavaAsr.startRecording(variant)
                if (screenDisposed.get()) {
                    shenavaAsr.cancelRecording()
                    return@execute
                }
                handler.post {
                    if (screenDisposed.get()) return@post
                    shenavaRecording = true
                    shenavaStatus = "در حال ضبط روی گوشی؛ پس از صحبت، «توقف و تبدیل آفلاین» را بزن."
                    shenavaBusy = false
                }
            } catch (error: Exception) {
                handler.post {
                    shenavaStatus = error.message ?: "شروع ضبط محلی ناموفق بود."
                    shenavaBusy = false
                    shenavaRecording = false
                }
            }
        }
    }

    fun stopShenavaRecordingAndTranscribe() {
        if (!shenavaRecording || shenavaBusy) return
        val variant = selectedShenavaVariant
        shenavaBusy = true
        shenavaStatus = "ضبط متوقف شد؛ شنوا در حال تبدیل گفتار روی CPU گوشی است…"
        executor.execute {
            try {
                val result = shenavaAsr.stopRecordingAndTranscribe(variant)
                handler.post {
                    shenavaTranscript = result.ifBlank { "گفتاری تشخیص داده نشد." }
                    shenavaStatus = "تبدیل آفلاین تمام شد · ${variant.displayName}"
                    shenavaRecording = false
                    shenavaBusy = false
                }
            } catch (error: Exception) {
                handler.post {
                    shenavaStatus = error.message ?: "تبدیل آفلاین شنوا ناموفق بود."
                    shenavaRecording = false
                    shenavaBusy = false
                }
            }
        }
    }

    fun applyVoiceState() {
        val current = voice.state
        callActive = current is VoiceCallState.Connecting ||
            current is VoiceCallState.Connected ||
            current is VoiceCallState.Muted
        muted = current is VoiceCallState.Muted
    }

    fun saveKey() {
        val key = apiKey.trim()
        if (busy || key.isBlank()) return
        busy = true
        executor.execute {
            try {
                voice.saveApiKey(key)
                handler.post {
                    apiKey = ""
                    keySaved = true
                    status = "کلید Gemini Live با Android Keystore رمزنگاری شد."
                    busy = false
                }
            } catch (error: Exception) {
                handler.post {
                    status = error.message ?: "ذخیره کلید ناموفق بود."
                    busy = false
                }
            }
        }
    }

    fun clearKey() {
        if (busy || callActive) return
        busy = true
        executor.execute {
            try {
                voice.clearApiKey()
                handler.post {
                    keySaved = false
                    apiKey = ""
                    status = "کلید Gemini Live حذف شد."
                    busy = false
                }
            } catch (error: Exception) {
                handler.post {
                    status = error.message ?: "حذف کلید ناموفق بود."
                    busy = false
                }
            }
        }
    }

    fun stopCall() {
        if (busy) return
        busy = true
        executor.execute {
            try {
                voice.stop()
                handler.post {
                    applyVoiceState()
                    status = "تماس صوتی پایان یافت."
                    busy = false
                }
            } catch (error: Exception) {
                handler.post {
                    status = error.message ?: "پایان تماس ناموفق بود."
                    busy = false
                    applyVoiceState()
                }
            }
        }
    }

    fun startCall() {
        if (busy || callActive || shenavaRecording || shenavaBusy) return
        transcript.clear()
        busy = true
        status = "در حال اتصال به Gemini Live…"
        executor.execute {
            try {
                voice.start(
                    onStatus = { message ->
                        handler.post {
                            status = message
                            applyVoiceState()
                        }
                    },
                    onTranscript = { speaker, text ->
                        handler.post {
                            transcript.add(speaker + ": " + text)
                            if (transcript.size > 100) transcript.removeAt(0)
                        }
                    }
                )
                handler.post {
                    applyVoiceState()
                    busy = false
                }
            } catch (error: Exception) {
                handler.post {
                    applyVoiceState()
                    status = error.message ?: "شروع تماس ناموفق بود."
                    busy = false
                }
            }
        }
    }

    fun toggleMute() {
        if (busy || !callActive) return
        val next = !muted
        busy = true
        executor.execute {
            try {
                voice.setMuted(next)
                handler.post {
                    applyVoiceState()
                    status = if (next) "میکروفون بی‌صداست." else "تماس زنده است؛ می‌توانی صحبت کنی."
                    busy = false
                }
            } catch (error: Exception) {
                handler.post {
                    status = error.message ?: "تغییر وضعیت میکروفون ناموفق بود."
                    applyVoiceState()
                    busy = false
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCall()
        else status = "برای تماس زنده باید اجازه دسترسی به میکروفون بدهی."
    }
    val shenavaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startShenavaRecording()
        else shenavaStatus = "برای ضبط آفلاین شنوا باید اجازه دسترسی به میکروفون بدهی."
    }

    Column(
        Modifier.fillMaxSize().background(KiraPalette.background)
            .verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("صدا و تشخیص گفتار", color = KiraPalette.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text(
            "Gemini Live برای مکالمه دوطرفه و شنوا برای تشخیص فارسی کاملاً آفلاین در دسترس است. مسیر شنوا پس از دانلود مدل، صدا را فقط روی خود گوشی پردازش می‌کند.",
            color = KiraPalette.muted, fontSize = 12.sp, lineHeight = 18.sp
        )

        Surface(color = KiraPalette.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تولید گفتار · Kitten TTS", color = KiraPalette.onBackground, fontWeight = FontWeight.SemiBold)
                Text(
                    "Kitten پاسخ‌های انگلیسی را آفلاین می‌خواند. برای پاسخ‌های فارسی از موتور گفتار نصب‌شده روی اندروید استفاده می‌شود؛ پشتیبانی فارسی به وجود صدای فارسی روی گوشی بستگی دارد.",
                    color = KiraPalette.muted, fontSize = 11.sp, lineHeight = 17.sp
                )
                Text(kittenStatus, color = KiraPalette.muted, fontSize = 11.sp)
                if (kittenBusy) LinearProgressIndicator(progress = { kittenProgress / 100f }, modifier = Modifier.fillMaxWidth())
                if (!kittenDownloaded) {
                    Button(
                        onClick = { downloadKitten() },
                        enabled = !kittenBusy && !busy && !callActive && !shenavaBusy && !shenavaRecording,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (kittenBusy) "در حال دانلود…" else "دانلود Kitten TTS · حدود ۲۷ مگابایت") }
                } else {
                    Text("مدل روی همین گوشی ذخیره شده و برای تولید صدای انگلیسی به اینترنت نیاز ندارد.", color = KiraPalette.primary, fontSize = 11.sp)
                }
            }
        }

        Surface(color = KiraPalette.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("تشخیص گفتار آفلاین · Shenava", color = KiraPalette.onBackground, fontWeight = FontWeight.SemiBold)
                Text(
                    "مدل روی گوشی دانلود می‌شود؛ صدای میکروفون به سرور یا سرویس ابری ارسال نمی‌شود. هر بار تا ۲ دقیقه ضبط می‌کنی و بعد از توقف، فایل در همان گوشی تبدیل می‌شود.",
                    color = KiraPalette.muted, fontSize = 11.sp, lineHeight = 17.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { selectShenavaVariant(ShenavaModelVariant.INT8) },
                        enabled = !shenavaBusy && !shenavaRecording && !callActive && !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (selectedShenavaVariant == ShenavaModelVariant.INT8) "✓ INT8 · ۱۲۶ MB" else "INT8 · ۱۲۶ MB")
                    }
                    OutlinedButton(
                        onClick = { selectShenavaVariant(ShenavaModelVariant.FP32) },
                        enabled = !shenavaBusy && !shenavaRecording && !callActive && !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (selectedShenavaVariant == ShenavaModelVariant.FP32) "✓ FP32 · ۴۵۹ MB" else "FP32 · ۴۵۹ MB")
                    }
                }
                Text(shenavaStatus, color = KiraPalette.muted, fontSize = 11.sp, lineHeight = 17.sp)
                if (shenavaBusy && shenavaDownloadProgress > 0) {
                    Text("پیشرفت دانلود: $shenavaDownloadProgress%", color = KiraPalette.primary, fontSize = 11.sp)
                }
                if (!shenavaDownloaded) {
                    Button(
                        onClick = { downloadShenavaModel() },
                        enabled = !shenavaBusy && !shenavaRecording && !callActive && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (shenavaBusy) "در حال آماده‌سازی…"
                            else "دانلود ${selectedShenavaVariant.displayName} · حدود ${selectedShenavaVariant.approximateSizeMb} مگابایت"
                        )
                    }
                }
                if (!shenavaRecording) {
                    Button(
                        onClick = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startShenavaRecording()
                            } else {
                                shenavaPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = shenavaDownloaded && !shenavaBusy && !callActive && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (shenavaBusy) "در حال آماده‌سازی…" else "شروع ضبط آفلاین") }
                } else {
                    Button(
                        onClick = { stopShenavaRecordingAndTranscribe() },
                        enabled = !shenavaBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (shenavaBusy) "در حال تبدیل گفتار…" else "توقف و تبدیل آفلاین") }
                }
                if (shenavaTranscript.isNotBlank()) {
                    Text("متن تشخیص‌داده‌شده", color = KiraPalette.primary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Surface(color = KiraPalette.background, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            shenavaTranscript,
                            modifier = Modifier.padding(12.dp),
                            color = KiraPalette.onBackground,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    }
                    Button(
                        onClick = { onUseInChat(shenavaTranscript) },
                        enabled = !shenavaBusy && !shenavaRecording,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("انتقال متن به کادر چت")
                    }
                }
            }
        }

        Surface(color = KiraPalette.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اتصال Gemini Live", color = KiraPalette.onBackground, fontWeight = FontWeight.SemiBold)
                if (!keySaved || apiKey.isNotBlank()) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Gemini API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !callActive && !busy
                    )
                    Button(onClick = { saveKey() }, enabled = apiKey.isNotBlank() && !callActive && !busy) {
                        Text(if (busy) "در حال ذخیره…" else "ذخیره امن کلید")
                    }
                } else {
                    Text("کلید ذخیره شده است؛ مقدار آن در صفحه نمایش داده نمی‌شود.", color = KiraPalette.muted, fontSize = 12.sp)
                    OutlinedButton(onClick = { clearKey() }, enabled = !callActive && !busy) { Text("حذف کلید") }
                }
            }
        }

        Surface(color = KiraPalette.surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Gemini 3.8 Live", color = KiraPalette.primary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(status, color = KiraPalette.muted, fontSize = 12.sp, lineHeight = 18.sp)
                if (!callActive) {
                    Button(
                        onClick = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startCall()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = keySaved && !busy && !shenavaRecording && !shenavaBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "در حال اتصال…" else "شروع تماس زنده") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { toggleMute() },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text(if (muted) "فعال‌کردن میکروفون" else "بی‌صداکردن") }
                        Button(
                            onClick = { stopCall() },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("پایان تماس") }
                    }
                }
            }
        }

        if (transcript.isNotEmpty()) {
            Text("رونوشت زنده", color = KiraPalette.onBackground, fontWeight = FontWeight.SemiBold)
            transcript.forEach { line ->
                Surface(color = KiraPalette.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(line, modifier = Modifier.padding(12.dp), color = KiraPalette.onBackground, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}
