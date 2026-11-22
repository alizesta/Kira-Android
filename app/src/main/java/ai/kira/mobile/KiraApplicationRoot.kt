package ai.kira.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.kira.mobile.assistant.AiAssistantOverlayService
import ai.kira.mobile.application.KiraContainer
import ai.kira.mobile.application.assistant.OverlayDestination
import ai.kira.mobile.application.navigation.BackTarget
import ai.kira.mobile.application.navigation.KiraNavigationPolicy
import ai.kira.mobile.application.navigation.KiraTab
import ai.kira.mobile.application.navigation.SettingsPage
import ai.kira.mobile.application.assistant.OverlayRouteParser
import ai.kira.mobile.ui.theme.KiraTheme
import ai.kira.mobile.ui.theme.KiraPalette
import java.util.concurrent.Executors

internal data class OverlayLaunchRequest(val route: String, val text: String, val sequence: Long)

@Composable
internal fun KiraApp(
    container: KiraContainer,
    routeRequest: OverlayLaunchRequest? = null,
    overlayRunning: Boolean = false
) {
    val context=LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(KiraTab.CHAT) }
    var previousTab by rememberSaveable { mutableStateOf(KiraTab.CHAT) }
    var selectedCharacter by rememberSaveable { mutableStateOf("kira") }
    var pendingChatContext by rememberSaveable { mutableStateOf("") }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationPermissionDenied by remember { mutableStateOf(false) }
    var overlayStarted by remember { mutableStateOf(overlayRunning) }

    fun startOverlayService() {
        val intent = Intent(context, AiAssistantOverlayService::class.java)
            .setAction(AiAssistantOverlayService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
        overlayStarted = true
    }

    LaunchedEffect(overlayRunning) {
        overlayStarted = overlayRunning
    }

    LaunchedEffect(routeRequest?.sequence) {
        routeRequest?.let { request ->
            when (OverlayRouteParser.parse(request.route)) {
                OverlayDestination.CHAT -> { selectedTab = KiraTab.CHAT; settingsPage = SettingsPage.HOME }
                OverlayDestination.SEARCH -> { selectedTab = KiraTab.SEARCH; settingsPage = SettingsPage.HOME }
                OverlayDestination.IMAGE -> { selectedTab = KiraTab.IMAGE; settingsPage = SettingsPage.HOME }
                OverlayDestination.VOICE -> {
                    if (selectedTab != KiraTab.SETTINGS) previousTab = selectedTab
                    selectedTab = KiraTab.SETTINGS
                    settingsPage = SettingsPage.VOICE
                }
            }
            if (request.text.isNotBlank()) {
                pendingChatContext = request.text
                selectedTab = KiraTab.CHAT
            }
        }
    }

    val backTarget = KiraNavigationPolicy.backTarget(selectedTab, settingsPage)
    BackHandler(enabled=backTarget != null) {
        when (backTarget) {
            BackTarget.SETTINGS_HOME -> settingsPage = SettingsPage.HOME
            BackTarget.CHAT -> selectedTab = KiraTab.CHAT
            BackTarget.PREVIOUS_TAB -> selectedTab = KiraNavigationPolicy.previousTabOrChat(previousTab)
            null -> Unit
        }
    }
    val overlaySettingsLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasOverlayPermission=Settings.canDrawOverlays(context)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        notificationPermissionDenied = !granted
    }
    val notificationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        notificationPermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notificationPermissionDenied = !notificationPermissionGranted
    }

    KiraTheme {
        Scaffold(
            containerColor=KiraPalette.background,
            bottomBar={
                if(selectedTab!=KiraTab.SETTINGS || settingsPage==SettingsPage.HOME) {
                    NavigationBar(containerColor=KiraPalette.surface) {
                    KiraTab.entries.forEach { tab ->
                        NavigationBarItem(selected=selectedTab==tab,onClick={
                            if(tab==KiraTab.SETTINGS && selectedTab!=KiraTab.SETTINGS) previousTab=selectedTab
                            selectedTab=tab
                        },
                            icon={Text(tab.glyph,fontSize=18.sp,color=if(selectedTab==tab) KiraPalette.primary else KiraPalette.muted)},label={Text(tab.label,fontSize=11.sp)})
                    }
                    }
                }
            }
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding),color=Color(0xFF0B0E15)) {
                when(selectedTab) {
                    KiraTab.CHAT -> KiraChatScreen(container=container,character=selectedCharacter,prefillText=pendingChatContext,onPrefillConsumed={pendingChatContext=""})
                    KiraTab.SEARCH -> KiraSearchScreen(container=container,onUseInChat={ text -> pendingChatContext=text; selectedTab=KiraTab.CHAT })
                    KiraTab.IMAGE -> KiraImageScreen(container)
                    KiraTab.CHARACTERS -> KiraCharactersScreen(selectedCharacter) { selectedCharacter=it }
                    KiraTab.SETTINGS -> when(settingsPage) {
                        SettingsPage.ROUTE -> SettingsSubpage("اتصال API و مدل‌ها",{settingsPage=SettingsPage.HOME}) { KiraRouteScreen(container.providers) }
                        SettingsPage.MEMORY -> SettingsSubpage("حافظه بلندمدت",{settingsPage=SettingsPage.HOME}) { KiraMemoryScreen(container) }
                        SettingsPage.SKILLS -> SettingsSubpage("مهارت‌ها و اتصال API",{settingsPage=SettingsPage.HOME}) { KiraSkillsScreen(container) }
                        SettingsPage.VOICE -> SettingsSubpage("صدا و تشخیص گفتار",{settingsPage=SettingsPage.HOME}) {
                            KiraVoiceScreen(container) { recognizedText ->
                                pendingChatContext = recognizedText
                                selectedTab = KiraTab.CHAT
                            }
                        }
                        SettingsPage.ASSISTANT -> SettingsSubpage("دستیار و ویس",{settingsPage=SettingsPage.HOME}) {
                            AssistantScreen(
                                hasOverlayPermission=hasOverlayPermission,
                                notificationPermissionGranted=notificationPermissionGranted,
                                notificationPermissionDenied=notificationPermissionDenied,
                                overlayStarted=overlayStarted,
                                onRequestNotificationPermission={
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onOpenNotificationSettings={
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                                    notificationSettingsLauncher.launch(intent)
                                },
                                onRequestOverlayPermission={
                                    val intent=Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+context.packageName))
                                    overlaySettingsLauncher.launch(intent)
                                },
                                onStartOverlay={
                                    // Notification permission controls drawer visibility, not the
                                    // user's ability to start the explicit foreground overlay.
                                    startOverlayService()
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionGranted) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onStopOverlay={
                                    context.startService(Intent(context,AiAssistantOverlayService::class.java).setAction(AiAssistantOverlayService.ACTION_STOP))
                                    overlayStarted=false
                                }
                            )
                        }
                        SettingsPage.HOME -> KiraSettingsHome(
                            container=container,
                            onRoute={settingsPage=SettingsPage.ROUTE},
                            onMemory={settingsPage=SettingsPage.MEMORY},
                            onAssistant={settingsPage=SettingsPage.ASSISTANT},
                            onSkills={settingsPage=SettingsPage.SKILLS},
                            onVoice={settingsPage=SettingsPage.VOICE}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSubpage(title:String,onBack:()->Unit,content:@Composable ()->Unit) {
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0E15))) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onBack) { Text("‹ بازگشت",color=Color(0xFFB9A5FF)) }
            Text(title,color=Color(0xFFF4F2FA),fontWeight=FontWeight.SemiBold,fontSize=16.sp)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun KiraSettingsHome(container:KiraContainer,onRoute:()->Unit,onMemory:()->Unit,onAssistant:()->Unit,onSkills:()->Unit,onVoice:()->Unit) {
    var skillCount by remember(container) { mutableIntStateOf(0) }
    var skillCountError by remember(container) { mutableStateOf("") }
    val executor = remember(container) { Executors.newSingleThreadExecutor() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(executor) { onDispose { executor.shutdownNow() } }
    LaunchedEffect(container) {
        executor.execute {
            try {
                val count = container.installedSkillCount()
                handler.post { skillCount = count; skillCountError = "" }
            } catch (error: Exception) {
                handler.post { skillCountError = error.message ?: "بازیابی تعداد مهارت‌ها ناموفق بود." }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("تنظیمات Kira",color=Color(0xFFF4F2FA),fontSize=25.sp,fontWeight=FontWeight.Bold)
        Text("هسته و داده‌ها روی همین دستگاه نگهداری می‌شوند.",color=Color(0xFF9BA5B8),fontSize=12.sp)
        SettingsCard("اتصال API و مدل‌ها","افزودن کلید، دریافت خودکار مدل‌ها و مدیریت اولویت و جایگزینی",onRoute)
        SettingsCard("حافظه بلندمدت","مدیریت یادداشت‌هایی که Kira، Ren و Mika به خاطر می‌سپارند",onMemory)
        SettingsCard("تماس صوتی زنده","مکالمه دوطرفه و کم‌تأخیر با Gemini Live",onVoice)
        SettingsCard("دستیار شناور","دسترسی سریع Kira روی برنامه‌های دیگر",onAssistant)
        SettingsCard("مهارت‌ها و APIها","نصب، فعال‌سازی جداگانه برای شخصیت‌ها و اتصال APIهای واقعی",onSkills)
        SettingsCard("هسته محلی","SQLite محلی و رمزنگاری کلیدها",{})
        Text("کتابخانه: "+container.libraryDirectory(),color=Color(0xFF7F8A9E),fontSize=10.sp)
        Text("مهارت‌های نصب‌شده: "+skillCount,color=Color(0xFF7F8A9E),fontSize=11.sp)
        if (skillCountError.isNotBlank()) Text(skillCountError,color=Color(0xFFFF8A9A),fontSize=10.sp)
        Text("API Keyها با Android Keystore رمزنگاری می‌شوند و مستقیماً از گوشی به Provider ارسال می‌شوند.",color=Color(0xFF7F8A9E),fontSize=11.sp,lineHeight=17.sp)
    }
}

@Composable
private fun SettingsCard(title:String,description:String,onClick:()->Unit) {
    Surface(color=Color(0xFF151C2B),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth(),onClick=onClick) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(title,color=Color(0xFFF1EFF8),fontWeight=FontWeight.SemiBold,fontSize=15.sp)
            Text(description,color=Color(0xFF9BA5B8),fontSize=11.sp,lineHeight=17.sp)
        }
    }
}

@Composable
private fun KiraCharactersScreen(selected:String,onSelect:(String)->Unit) {
    val characters=listOf(
        Triple("kira","Kira","باهوش، مستقل، طعنه‌زن و وفادار"),
        Triple("ren","Ren","آرام، تحلیل‌گر، دقیق و محافظ"),
        Triple("mika","Mika","سرزنده، بازیگوش، کنجکاو و اجتماعی")
    )
    Column(Modifier.fillMaxSize().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("شخصیت‌ها",color=Color(0xFFF4F2FA),fontSize=25.sp,fontWeight=FontWeight.Bold)
        Text("شخصیتی را که در تب چت با آن صحبت می‌کنی انتخاب کن.",color=Color(0xFF9BA5B8),fontSize=12.sp)
        characters.forEach { (id,name,description) ->
            Surface(color=if(selected==id) Color(0xFF292344) else Color(0xFF151C2B),shape=RoundedCornerShape(18.dp),
                modifier=Modifier.fillMaxWidth(),onClick={onSelect(id)}) {
                Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(name,color=Color(0xFFF4F2FA),fontSize=18.sp,fontWeight=FontWeight.Bold)
                        Text(description,color=Color(0xFF9BA5B8),fontSize=12.sp)
                    }
                    if(selected==id) Text("فعال ✓",color=Color(0xFFB9A5FF),fontSize=12.sp)
                    else OutlinedButton(onClick={onSelect(id)}) { Text("انتخاب") }
                }
            }
        }
    }
}

@Composable
private fun AssistantScreen(
    hasOverlayPermission:Boolean,
    notificationPermissionGranted:Boolean,
    notificationPermissionDenied:Boolean,
    overlayStarted:Boolean,
    onRequestNotificationPermission:()->Unit,
    onOpenNotificationSettings:()->Unit,
    onRequestOverlayPermission:()->Unit,
    onStartOverlay:()->Unit,
    onStopOverlay:()->Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("دستیار و ویس",color=Color(0xFFF4F2FA),fontSize=23.sp,fontWeight=FontWeight.Bold)
        InfoPanel("دسترسی سریع روی برنامه‌ها","حباب شناور Kira برای دسترسی از داخل برنامه‌های دیگر.",when {
            overlayStarted -> "حباب شناور فعال است"
            hasOverlayPermission -> "مجوز آماده است"
            else -> "نیازمند مجوز نمایش روی برنامه‌ها"
        }) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionGranted) {
                Text(
                    "برای نمایش اعلان دائمی سرویس و کنترل خاموش‌کردن آن، مجوز اعلان‌ها لازم است.",
                    color=Color(0xFFFFC477),fontSize=11.sp,lineHeight=17.sp
                )
                Button(onClick=onRequestNotificationPermission,modifier=Modifier.fillMaxWidth()) {
                    Text("درخواست مجوز اعلان‌ها")
                }
                if (notificationPermissionDenied) {
                    OutlinedButton(onClick=onOpenNotificationSettings,modifier=Modifier.fillMaxWidth()) {
                        Text("بازکردن تنظیمات اعلان‌ها")
                    }
                }
            }
            if (!hasOverlayPermission) {
                Button(onClick=onRequestOverlayPermission,modifier=Modifier.fillMaxWidth()) { Text("دادن مجوز نمایش روی برنامه‌ها") }
            } else if (!overlayStarted) {
                Button(onClick=onStartOverlay,modifier=Modifier.fillMaxWidth()) { Text("فعال‌کردن حباب") }
            } else {
                OutlinedButton(onClick=onStopOverlay,modifier=Modifier.fillMaxWidth()) { Text("خاموش‌کردن حباب") }
            }
        }
        InfoPanel("تعامل صوتی","تماس صوتی Real-time با Gemini Live از بخش مستقل «تماس صوتی زنده» در تنظیمات در دسترس است.","فعال") {}
        InfoPanel("درک محتوای صفحه","تحلیل صفحه فقط با اجازه صریح کاربر و پس از پیاده‌سازی نشست امن فعال می‌شود.","فعلاً غیرفعال") {}
    }
}

@Composable
private fun InfoPanel(title:String,description:String,status:String,actions:@Composable ()->Unit={}) {
    Surface(color=Color(0xFF151C2B),shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text(title,color=Color(0xFFF1EFF8),fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                Text(status,color=Color(0xFFB9A5FF),fontSize=10.sp)
            }
            Text(description,color=Color(0xFFA2ADC0),fontSize=12.sp,lineHeight=18.sp)
            actions()
        }
    }
}
