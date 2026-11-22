package ai.kira.mobile

import android.os.Handler
import android.os.Looper
import ai.kira.mobile.application.KiraContainer
import ai.kira.mobile.domain.model.ChatMessage
import ai.kira.mobile.ui.theme.KiraPalette
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.Executors

@Composable
internal fun KiraChatScreen(container: KiraContainer, character:String,prefillText:String="",onPrefillConsumed:()->Unit={}) {
    val chat=remember(container) { container.chat }
    val executor=remember { Executors.newSingleThreadExecutor() }
    val handler=remember { Handler(Looper.getMainLooper()) }
    val messages=remember { mutableStateListOf<ChatMessage>() }
    val listState=rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var historyLoading by remember { mutableStateOf(false) }
    var loadSequence by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("هسته محلی آماده است. کلید API را در Route وارد کن.") }
    LaunchedEffect(prefillText) {
        if(prefillText.isNotBlank()) {
            input=prefillText
            onPrefillConsumed()
        }
    }

    DisposableEffect(executor) { onDispose { executor.shutdownNow() } }
    fun load() {
        val requestedCharacter = character
        val sequence = ++loadSequence
        historyLoading = true
        executor.execute {
            try {
                val loaded = chat.history(requestedCharacter)
                handler.post {
                    if (sequence == loadSequence && character == requestedCharacter) {
                        messages.clear()
                        messages.addAll(loaded)
                        status = "تاریخچه از حافظه همین دستگاه بارگذاری شد."
                    }
                    if (sequence == loadSequence) historyLoading = false
                }
            } catch (error: Exception) {
                handler.post {
                    if (sequence == loadSequence) {
                        status = error.message ?: "بازیابی تاریخچه ناموفق بود."
                        historyLoading = false
                    }
                }
            }
        }
    }
    LaunchedEffect(character) { load() }
    LaunchedEffect(messages.size) { if(messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

    fun send() {
        val text=input.trim()
        if(text.isBlank() || busy || historyLoading) return
        val who=character
        input=""
        busy=true
        status="در حال دریافت پاسخ…"
        executor.execute {
            try {
                val reply=chat.sendMessage(who,text)
                val history=chat.history(who)
                handler.post {
                    if (character == who) {
                        messages.clear()
                        messages.addAll(history)
                        status="پاسخ از "+reply.provider+" · "+reply.model
                    }
                    busy=false
                }
            } catch(error:Exception) {
                val history = runCatching { chat.history(who) }.getOrDefault(emptyList())
                handler.post {
                    if (character == who) {
                        messages.clear()
                        messages.addAll(history)
                        status=error.message ?: "ارسال پیام ناموفق بود."
                    }
                    busy=false
                }
            }
        }
    }

    val characterBackground = when (character.trim().lowercase()) {
        "ren" -> R.drawable.ren_character
        "mika" -> R.drawable.mika_character
        else -> R.drawable.kira_character
    }

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = characterBackground),
            contentDescription = "تصویر کیرا در پس‌زمینهٔ گفتگو",
            modifier = Modifier.fillMaxSize().alpha(0.22f),
            contentScale = ContentScale.Crop
        )
        Box(Modifier.fillMaxSize().background(KiraPalette.background.copy(alpha = 0.82f)))
        Column(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Column {
                Text("KIRA LOCAL CORE",color=KiraPalette.primary,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)
                Text("گفت‌وگو با "+character.replaceFirstChar { it.uppercase() },color=KiraPalette.onBackground,fontSize=21.sp,fontWeight=FontWeight.Bold)
            }
            OutlinedButton(onClick={load()},enabled=!busy && !historyLoading) { Text("بازیابی") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=listState,verticalArrangement=Arrangement.spacedBy(9.dp)) {
            if(messages.isEmpty()) item {
                Text("این چت فقط روی همین گوشی ذخیره می‌شود. API Key را از تنظیمات اضافه کن تا Kira پاسخ بدهد.",color=Color(0xFF8995AA),fontSize=13.sp,lineHeight=20.sp,modifier=Modifier.padding(vertical=20.dp))
            }
            items(messages,key={it.id}) { message ->
                val user=message.role=="user"
                Row(Modifier.fillMaxWidth(),horizontalArrangement=if(user) Arrangement.End else Arrangement.Start) {
                    Surface(color=if(user) Color(0xFF493D79) else KiraPalette.surface,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth(0.88f)) {
                        Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(if(user) "تو" else character.uppercase(),color=if(user) Color(0xFFE5DCFF) else KiraPalette.primary,fontSize=10.sp,fontWeight=FontWeight.Bold)
                            Text(message.content,color=Color(0xFFF0EFF6),fontSize=14.sp,lineHeight=21.sp)
                            if(!user && message.route.isNotBlank()) Text(message.route,color=Color(0xFF8290A7),fontSize=9.sp)
                        }
                    }
                }
            }
            if(busy || historyLoading) item { Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp),color=KiraPalette.primary,strokeWidth=2.dp)
                Text(if (historyLoading) "در حال بازیابی تاریخچه…" else "Kira در حال فکرکردن است…",color=KiraPalette.muted,fontSize=11.sp)
            } }
        }
        Text(status,color=KiraPalette.muted,fontSize=10.sp,lineHeight=15.sp)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.weight(1f),placeholder={Text("پیامت را بنویس…",color=Color(0xFFD7D1E8))},maxLines=4,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}),shape=RoundedCornerShape(18.dp),
                colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,disabledTextColor=Color.White,cursorColor=Color.White,focusedPlaceholderColor=Color(0xFFD7D1E8),unfocusedPlaceholderColor=Color(0xFFD7D1E8)))
            Button(onClick={send()},enabled=!busy && !historyLoading && input.isNotBlank(),modifier=Modifier.height(54.dp),colors=ButtonDefaults.buttonColors(containerColor=KiraPalette.primaryContainer)) { Text("ارسال") }
        }
        }
    }
}
