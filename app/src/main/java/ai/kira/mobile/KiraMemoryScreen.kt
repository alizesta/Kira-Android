package ai.kira.mobile

import android.os.Handler
import android.os.Looper
import ai.kira.mobile.application.KiraContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.Executors

private data class LocalMemoryRow(val id:Long,val text:String)

@Composable
internal fun KiraMemoryScreen(container: KiraContainer) {
    val assistant=remember(container) { container.assistant }
    val executor=remember { Executors.newSingleThreadExecutor() }
    val handler=remember { Handler(Looper.getMainLooper()) }
    val rows=remember { mutableStateListOf<LocalMemoryRow>() }
    var character by remember { mutableStateOf("kira") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("حافظه بلندمدت روی همین دستگاه ذخیره می‌شود.") }
    DisposableEffect(executor) { onDispose { executor.shutdownNow() } }

    var loadSequence by remember { mutableLongStateOf(0L) }
    fun refresh() {
        val requestedCharacter = character
        val sequence = ++loadSequence
        busy = true
        executor.execute {
            try {
                val loaded = assistant.memories(requestedCharacter).map { LocalMemoryRow(it.id,it.text) }
                handler.post {
                    if (sequence == loadSequence && character == requestedCharacter) {
                        rows.clear()
                        rows.addAll(loaded)
                        status = "حافظه محلی بازیابی شد."
                    }
                    if (sequence == loadSequence) busy = false
                }
            } catch (error: Exception) {
                handler.post {
                    if (sequence == loadSequence) {
                        status = error.message ?: "بازیابی حافظه ناموفق بود."
                        busy = false
                    }
                }
            }
        }
    }
    LaunchedEffect(character) { refresh() }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0E15)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("حافظه محلی KIRA",color=Color(0xFFF4F2FA),fontSize=22.sp,fontWeight=FontWeight.Bold)
        Text("یادداشت‌ها در پایگاه داده خصوصی برنامه ذخیره می‌شوند و برای پاسخ‌های بعدی همان شخصیت استفاده می‌شوند.",color=Color(0xFF9BA5B8),fontSize=11.sp,lineHeight=17.sp)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("kira" to "Kira","ren" to "Ren","mika" to "Mika","global" to "مشترک").forEach { (id,label) ->
                Button(onClick={character=id},enabled=!busy,colors=ButtonDefaults.buttonColors(containerColor=if(character==id) Color(0xFF7661D8) else Color(0xFF20293A))) { Text(label) }
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=note,onValueChange={note=it},modifier=Modifier.weight(1f),label={Text("چیزی برای به‌خاطر سپردن…")},maxLines=3)
            Button(onClick={
                val value=note.trim()
                if(value.isNotBlank() && !busy) {
                    busy=true
                    val requestedCharacter = character
                    executor.execute {
                        try {
                            assistant.addMemory(requestedCharacter,value)
                            val loaded=assistant.memories(requestedCharacter).map { LocalMemoryRow(it.id,it.text) }
                            handler.post {
                                if (character == requestedCharacter) {
                                    rows.clear()
                                    rows.addAll(loaded)
                                    note=""
                                    status="در حافظه محلی ذخیره شد."
                                }
                                busy=false
                            }
                        } catch(error:Exception) {
                            handler.post {
                                status=error.message ?: "ذخیره ناموفق بود."
                                busy=false
                            }
                        }
                    }
                }
            },enabled=!busy && note.isNotBlank(),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF7661D8))) { Text("ثبت") }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("یادداشت‌ها",color=Color(0xFFF1EFF8),fontWeight=FontWeight.SemiBold)
            OutlinedButton(onClick={refresh()},enabled=!busy) { Text("بازیابی") }
        }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            items(rows,key={it.id}) { row ->
                Surface(color=Color(0xFF151C2B),shape=RoundedCornerShape(13.dp),modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(row.text,Modifier.weight(1f),color=Color(0xFFEDEBF4),fontSize=13.sp,lineHeight=19.sp)
                        TextButton(onClick={
                            if(!busy) {
                                busy=true
                                val requestedCharacter = character
                        executor.execute {
                            try {
                                assistant.deleteMemory(row.id)
                                val loaded = assistant.memories(requestedCharacter).map { LocalMemoryRow(it.id,it.text) }
                                handler.post {
                                    if (character == requestedCharacter) {
                                        rows.clear()
                                        rows.addAll(loaded)
                                        status = "یادداشت حذف شد."
                                    }
                                    busy = false
                                }
                            } catch (error: Exception) {
                                handler.post {
                                    status = error.message ?: "حذف یادداشت ناموفق بود."
                                    busy = false
                                }
                            }
                        }
                            }
                        },enabled=!busy) { Text("حذف") }
                    }
                }
            }
            if(rows.isEmpty()) item { Text("هنوز یادداشتی ثبت نشده.",color=Color(0xFF8995AA),fontSize=12.sp) }
        }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Color(0xFFB9A5FF))
        Text(status,color=Color(0xFF9BA5B8),fontSize=10.sp)
    }
}
