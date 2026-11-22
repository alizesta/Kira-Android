package ai.kira.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ai.kira.mobile.application.KiraContainer
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun KiraSkillsScreen(container: KiraContainer) {
    val assistant=remember(container) { container.assistant }
    var character by remember { mutableStateOf("global") }
    var refresh by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("https://api.example.com") }
    var endpoint by remember { mutableStateOf("/v1/search") }
    var parameters by remember { mutableStateOf("q") }
    var apiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("سه Connector آماده در کتابخانه نصب شده‌اند.") }
    var busy by remember { mutableStateOf(false) }
    var skills by remember { mutableStateOf(emptyList<ai.kira.mobile.domain.model.SkillEntry>()) }
    var loadSequence by remember { mutableIntStateOf(0) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val handler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(executor) {
        onDispose { executor.shutdownNow() }
    }

    LaunchedEffect(character, refresh) {
        val requestedCharacter = character
        val sequence = ++loadSequence
        busy = true
        executor.execute {
            try {
                val loaded = assistant.skillsFor(requestedCharacter)
                handler.post {
                    if (sequence == loadSequence) {
                        if (character == requestedCharacter) {
                            skills = loaded
                            status = "مهارت‌های دامنه انتخاب‌شده بازیابی شدند."
                        }
                        busy = false
                    }
                }
            } catch (error: Exception) {
                handler.post {
                    if (sequence == loadSequence) {
                        status = error.message ?: "بازیابی مهارت‌ها ناموفق بود."
                        busy = false
                    }
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0E15)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        Text("کتابخانه مهارت‌ها",color=Color(0xFFF4F2FA),fontSize=23.sp,fontWeight=FontWeight.Bold)
        Text("یک کتابخانه محلی و مشترک. مهارت را یک‌بار نصب کن و برای Kira، Ren، Mika یا هر سه فعالش کن.",color=Color(0xFF9BA5B8),fontSize=12.sp,lineHeight=18.sp)
        Text("دامنه فعال‌سازی",color=Color(0xFFB9A5FF),fontWeight=FontWeight.SemiBold)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("global" to "همه","kira" to "Kira","ren" to "Ren","mika" to "Mika").forEach { (id,label) ->
                FilterChip(selected=character==id,onClick={character=id},enabled=!busy,label={Text(label)})
            }
        }
        Text("مهارت‌های نصب‌شده",color=Color(0xFFF4F2FA),fontWeight=FontWeight.SemiBold)
        if(skills.isEmpty()) Text("هنوز مهارتی نصب نشده است.",color=Color(0xFF9BA5B8),fontSize=12.sp)
        skills.forEach { skill ->
            Surface(color=Color(0xFF151C2B),shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Text(skill.name,color=Color(0xFFF4F2FA),fontWeight=FontWeight.Bold)
                            Text(skill.description,color=Color(0xFF9BA5B8),fontSize=11.sp,lineHeight=16.sp)
                            Text(if(skill.source=="builtin") "Connector آماده · فقط خواندنی" else "Connector سفارشی · HTTPS GET",color=Color(0xFF8F84D8),fontSize=10.sp)
                        }
                        Switch(checked=skill.enabled,enabled=!busy,onCheckedChange={ enabled ->
                            if (!busy) {
                                busy = true
                                val requestedCharacter = character
                                executor.execute {
                                    try {
                                        assistant.setSkillEnabled(skill.name,requestedCharacter,enabled)
                                        handler.post {
                                            status = "وضعیت مهارت ذخیره شد."
                                            busy = false
                                            refresh++
                                        }
                                    } catch (error: Exception) {
                                        handler.post {
                                            status = error.message ?: "تغییر وضعیت مهارت ناموفق بود."
                                            busy = false
                                        }
                                    }
                                }
                            }
                        })
                    }
                    if(skill.source!="builtin") {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                            TextButton(onClick={
                                if (!busy) {
                                    busy = true
                                    executor.execute {
                                        try {
                                            assistant.removeSkill(skill.name)
                                            handler.post {
                                                status = "مهارت حذف شد."
                                                busy = false
                                                refresh++
                                            }
                                        } catch (error: Exception) {
                                            handler.post {
                                                status = error.message ?: "حذف مهارت ناموفق بود."
                                                busy = false
                                            }
                                        }
                                    }
                                }
                            },enabled=!busy) { Text("حذف",color=Color(0xFFFF8A9A)) }
                        }
                    }
                }
            }
        }
        Divider(color=Color(0xFF273047))
        Text("ساخت Connector جدید",color=Color(0xFFF4F2FA),fontSize=18.sp,fontWeight=FontWeight.Bold)
        Text("فقط درخواست HTTPS GET. هیچ اسکریپت یا کد دانلودشده‌ای اجرا نمی‌شود. کلید اختیاری با Android Keystore رمزنگاری می‌شود.",color=Color(0xFF9BA5B8),fontSize=11.sp,lineHeight=17.sp)
        OutlinedTextField(value=name,onValueChange={name=it.lowercase().replace(Regex("[^a-z0-9-]"),"-").replace(Regex("-+"),"-").trim('-')},label={Text("شناسه مهارت (انگلیسی)")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=description,onValueChange={description=it},label={Text("توضیح کوتاه")},enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(
            value=instructions,
            onValueChange={instructions=it},
            label={Text("دستور استفاده از این API")},
            placeholder={Text("مثلاً: برای تبدیل ارز، کد ارز مبدأ و مقصد را بگیر، نرخ مرجع را دریافت کن و تاریخ داده را اعلام کن.")},
            minLines=3,
            enabled=!busy,modifier=Modifier.fillMaxWidth()
        )
        OutlinedTextField(value=baseUrl,onValueChange={baseUrl=it},label={Text("Base URL")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=endpoint,onValueChange={endpoint=it},label={Text("مسیر Endpoint")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=parameters,onValueChange={parameters=it},label={Text("پارامترهای مجاز با کاما")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=apiKey,onValueChange={apiKey=it},label={Text("API Key اختیاری (Bearer)")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth())
        Button(
            onClick={
                if (!busy) {
                    busy = true
                    val installName = name
                    val installDescription = description
                    val installInstructions = instructions.ifBlank { description }
                    val installBaseUrl = baseUrl
                    val installEndpoint = endpoint
                    val installParameters = parameters.split(",").map { it.trim() }
                    val installApiKey = apiKey
                    val installCharacter = character
                    executor.execute {
                        try {
                            assistant.installApiSkill(
                                installName,installDescription,installInstructions,installBaseUrl,
                                installEndpoint,installParameters,installApiKey,installCharacter
                            )
                            handler.post {
                                name="";description="";instructions="";apiKey=""
                                status="Connector نصب شد و برای دامنه انتخاب‌شده فعال است."
                                busy=false
                                refresh++
                            }
                        } catch (error: Exception) {
                            handler.post {
                                status=error.message ?: "نصب Connector ناموفق بود."
                                busy=false
                            }
                        }
                    }
                }
            },
            enabled=!busy && name.isNotBlank() && description.isNotBlank() && endpoint.startsWith("/"),
            modifier=Modifier.fillMaxWidth()
        ) { Text(if (busy) "در حال ذخیره…" else "نصب Connector") }
        if (busy) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        Text(status,color=Color(0xFFB9A5FF),fontSize=11.sp,lineHeight=17.sp)
    }
}
