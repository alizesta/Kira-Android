package ai.kira.mobile.data.local

import ai.kira.mobile.domain.model.ChatAttachmentInput
import ai.kira.mobile.domain.policy.FreeModelPolicy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LocalProvider(val id:String,val model:String,val baseUrl:String,val enabled:Boolean,val priority:Int,val hasKey:Boolean)
data class LocalChatAttachment(val id:String,val name:String,val mimeType:String,val sizeBytes:Long)
data class LocalChatMessage(val id:Long,val role:String,val content:String,val route:String,val createdAt:Long,val attachments:List<LocalChatAttachment> = emptyList())
data class LocalSkill(val name:String,val description:String,val instructions:String,val source:String,val enabled:Boolean)
data class LocalConnectorDefinition(val id:String,val description:String,val parameters:List<String>)
data class LocalConnectorExecutionConfig(val id:String,val baseUrl:String,val pathTemplate:String,val allowedParameters:List<String>,val authType:String,val apiKey:String)
private data class ConnectorCall(val id:String,val params:JSONObject)

private class LocalKeyVault {
    private val alias="kira.local.provider.keys.v1"
    private val key:SecretKey by lazy {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey) ?: run {
            val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
            gen.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            gen.generateKey()
        }
    }
    fun encrypt(value:String):String {
        if(value.isBlank()) return ""
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key)
        return Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),Base64.NO_WRAP)
    }
    fun decrypt(value:String):String {
        if(value.isBlank()) return ""
        return try {
            val raw=Base64.decode(value,Base64.NO_WRAP); require(raw.size>12)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,raw.copyOfRange(0,12)))
            String(cipher.doFinal(raw.copyOfRange(12,raw.size)),StandardCharsets.UTF_8)
        } catch(_:Exception) { "" }
    }
}

private class LocalAttachmentVault {
 private val alias="kira.local.chat.attachments.v1"
 private val key:SecretKey by lazy {
  val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  (store.getKey(alias,null) as? SecretKey) ?: run {
   val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
   generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
   generator.generateKey()
  }
 }
 fun encryptBytes(value:ByteArray):ByteArray {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key)
  return cipher.iv+cipher.doFinal(value)
 }
}
private class LocalDb(context:Context):SQLiteOpenHelper(context.applicationContext,"kira_local_core.db",null,4) {
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE providers(id TEXT PRIMARY KEY,model TEXT NOT NULL,base_url TEXT NOT NULL,encrypted_key TEXT NOT NULL DEFAULT '',enabled INTEGER NOT NULL DEFAULT 0,priority INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,character_id TEXT NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,route TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_messages_character ON messages(character_id,id)")
        db.execSQL("CREATE TABLE message_attachments(id TEXT PRIMARY KEY,message_id INTEGER NOT NULL,name TEXT NOT NULL,mime_type TEXT NOT NULL,size_bytes INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_message_attachments_message ON message_attachments(message_id)")
        db.execSQL("CREATE TABLE memories(id INTEGER PRIMARY KEY AUTOINCREMENT,character_id TEXT NOT NULL,text TEXT NOT NULL,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE skills(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,description TEXT NOT NULL DEFAULT '',character_id TEXT NOT NULL DEFAULT 'global',enabled INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL,instructions TEXT NOT NULL DEFAULT '',source TEXT NOT NULL DEFAULT 'local')")
        db.execSQL("CREATE TABLE skill_assignments(skill_name TEXT NOT NULL,character_id TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(skill_name,character_id))")
        db.execSQL("CREATE TABLE skill_connectors(id TEXT PRIMARY KEY,skill_name TEXT NOT NULL,description TEXT NOT NULL,base_url TEXT NOT NULL,path_template TEXT NOT NULL,allowed_params TEXT NOT NULL,auth_type TEXT NOT NULL DEFAULT 'none',encrypted_key TEXT NOT NULL DEFAULT '',enabled INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_skill_connectors_skill ON skill_connectors(skill_name)")
        db.execSQL("CREATE TABLE provider_models(provider_id TEXT NOT NULL,model_id TEXT NOT NULL,capabilities TEXT NOT NULL DEFAULT 'chat',PRIMARY KEY(provider_id,model_id))")
        val defaults=listOf(
            arrayOf("openrouter","openai/gpt-4o-mini","https://openrouter.ai/api/v1"),
            arrayOf("groq","openai/gpt-oss-120b","https://api.groq.com/openai/v1"),
            arrayOf("mistral","mistral-small-latest","https://api.mistral.ai/v1"),
            arrayOf("deepseek","deepseek-chat","https://api.deepseek.com"),
            arrayOf("gemini","gemini-2.5-flash","https://generativelanguage.googleapis.com/v1beta")
        )
        defaults.forEachIndexed { i,row ->
            db.execSQL("INSERT INTO providers(id,model,base_url,priority) VALUES(?,?,?,?)",arrayOf<Any>(row[0],row[1],row[2],i))
        }
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        if(oldVersion<2) db.execSQL("CREATE TABLE IF NOT EXISTS provider_models(provider_id TEXT NOT NULL,model_id TEXT NOT NULL,capabilities TEXT NOT NULL DEFAULT 'chat',PRIMARY KEY(provider_id,model_id))")
        if(oldVersion<3) {
            db.execSQL("ALTER TABLE skills ADD COLUMN instructions TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE skills ADD COLUMN source TEXT NOT NULL DEFAULT 'local'")
            db.execSQL("CREATE TABLE IF NOT EXISTS skill_assignments(skill_name TEXT NOT NULL,character_id TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,PRIMARY KEY(skill_name,character_id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS skill_connectors(id TEXT PRIMARY KEY,skill_name TEXT NOT NULL,description TEXT NOT NULL,base_url TEXT NOT NULL,path_template TEXT NOT NULL,allowed_params TEXT NOT NULL,auth_type TEXT NOT NULL DEFAULT 'none',encrypted_key TEXT NOT NULL DEFAULT '',enabled INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_skill_connectors_skill ON skill_connectors(skill_name)")
        }
        if(oldVersion<4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS message_attachments(id TEXT PRIMARY KEY,message_id INTEGER NOT NULL,name TEXT NOT NULL,mime_type TEXT NOT NULL,size_bytes INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_attachments_message ON message_attachments(message_id)")
        }
    }
}

/** Independent on-device Core. API keys are encrypted with Android Keystore. */
class SqliteKiraStore(context:Context) {
    private val app=context.applicationContext
    private val db=LocalDb(app).writableDatabase
    private val vault=LocalKeyVault()
    private val attachmentVault=LocalAttachmentVault()
    private val attachmentDirectory=java.io.File(app.filesDir,"kira/attachments")

    init {
        val root=java.io.File(app.filesDir,"kira")
        listOf(root,java.io.File(root,"library"),java.io.File(root,"skills"),attachmentDirectory).forEach {
            if(!it.exists() && !it.mkdirs()) throw IllegalStateException("ساخت پوشه محلی Kira ناموفق بود: "+it.name)
        }
        ensureBuiltinSkills()
    }


    @Synchronized fun discoveredModels(providerId:String):List<String> {
        val out=mutableListOf<String>()
        db.rawQuery("SELECT model_id FROM provider_models WHERE provider_id=? ORDER BY model_id",arrayOf(providerId)).use { c -> while(c.moveToNext()) out+=c.getString(0) }
        return out.filter { FreeModelPolicy.isVerifiedFreeChatModel(providerId,it) }
    }

    fun recommendedFreeModel(providerId:String,models:List<String>):String =
        FreeModelPolicy.recommendedModel(providerId,models)


    /** Persist a verified provider model catalog; network access is owned by data/remote. */
    @Synchronized fun cacheDiscoveredModels(providerId:String,modelIds:List<String>):List<String> {
        val id=providerId.trim().lowercase()
        val models=FreeModelPolicy.verifiedModels(id,modelIds)
        require(models.isNotEmpty()) { "فهرست مدل‌های رایگان معتبر خالی است." }
        db.beginTransaction()
        try {
            db.delete("provider_models","provider_id=?",arrayOf(id))
            models.forEach { modelId ->
                val capabilities=when {
                    modelId.contains("embedding",true) -> "embedding"
                    modelId.contains("tts",true) || modelId.contains("audio",true) || modelId.contains("speech",true) -> "audio"
                    modelId.contains("vision",true) || modelId.contains("image",true) || modelId.contains("4o",true) || modelId.contains("gemini",true) -> "chat,vision"
                    else -> "chat"
                }
                val values=ContentValues().apply {
                    put("provider_id",id); put("model_id",modelId); put("capabilities",capabilities)
                }
                db.insertWithOnConflict("provider_models",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return models
    }

    @Synchronized fun providers():List<LocalProvider> {
        val out=mutableListOf<LocalProvider>()
        db.rawQuery("SELECT id,model,base_url,enabled,priority,encrypted_key FROM providers ORDER BY priority,id",null).use { c ->
            while(c.moveToNext()) out+=LocalProvider(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)!=0,c.getInt(4),c.getString(5).isNotBlank())
        }
        return out
    }

    @Synchronized fun freeProviders():List<LocalProvider> =
        providers().filter { it.hasKey && FreeModelPolicy.isVerifiedFreeChatModel(it.id,it.model) }

    @Synchronized fun saveProvider(id:String,model:String,baseUrl:String,apiKey:String,enabled:Boolean=true) {
        val pid=id.trim().lowercase()
        require(pid.matches(Regex("[a-z0-9][a-z0-9_-]{1,39}"))) { "شناسه Provider معتبر نیست." }
        require(model.isNotBlank()) { "Model ID را وارد کن." }
        require(FreeModelPolicy.isVerifiedFreeChatModel(pid,model.trim())) { "این مدل در فهرست رایگان تأییدشده Kira نیست؛ برای جلوگیری از هزینه ذخیره نشد." }
        require(baseUrl.startsWith("https://")) { "آدرس Provider باید HTTPS باشد." }
        val old=db.rawQuery("SELECT encrypted_key FROM providers WHERE id=?",arrayOf(pid)).use { if(it.moveToFirst()) vault.decrypt(it.getString(0)) else "" }
        val priority=providers().indexOfFirst { it.id==pid }.let { if(it<0) providers().size else it }
        val values=ContentValues().apply {
            put("id",pid); put("model",model.trim()); put("base_url",baseUrl.trim().trimEnd('/'))
            put("encrypted_key",vault.encrypt(apiKey.trim().ifBlank { old }))
            put("enabled",if(enabled) 1 else 0); put("priority",priority)
        }
        db.insertWithOnConflict("providers",null,values,SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun setProviderEnabled(id:String,enabled:Boolean) {
        db.execSQL("UPDATE providers SET enabled=? WHERE id=?",arrayOf<Any>(if(enabled) 1 else 0,id))
    }
    @Synchronized fun moveProvider(id:String,delta:Int) {
        val rows=providers().toMutableList(); val i=rows.indexOfFirst { it.id==id }; if(i<0) return
        val target=(i+delta).coerceIn(0,rows.lastIndex); if(target==i) return
        val item=rows.removeAt(i); rows.add(target,item); db.beginTransaction()
        try { rows.forEachIndexed { index,p -> db.execSQL("UPDATE providers SET priority=? WHERE id=?",arrayOf<Any>(index,p.id)) }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    @Synchronized fun history(characterId:String,limit:Int=100):List<LocalChatMessage> {
        val out=mutableListOf<LocalChatMessage>()
        db.rawQuery("SELECT id,role,content,route,created_at FROM (SELECT * FROM messages WHERE character_id=? ORDER BY id DESC LIMIT ?) ORDER BY id ASC",arrayOf(characterId,limit.coerceIn(1,500).toString())).use { c ->
            while(c.moveToNext()) { val id=c.getLong(0); out+=LocalChatMessage(id,c.getString(1),c.getString(2),c.getString(3),c.getLong(4),attachmentsForMessage(id)) }
        }
        return out
    }
    private fun attachmentsForMessage(id:Long):List<LocalChatAttachment> {
        val out=mutableListOf<LocalChatAttachment>()
        db.rawQuery("SELECT id,name,mime_type,size_bytes FROM message_attachments WHERE message_id=? ORDER BY rowid",arrayOf(id.toString())).use { c -> while(c.moveToNext()) out+=LocalChatAttachment(c.getString(0),c.getString(1),c.getString(2),c.getLong(3)) }
        return out
    }
    @Synchronized fun clearHistory(characterId:String) {
        val ids=mutableListOf<String>()
        db.rawQuery("SELECT a.id FROM message_attachments a JOIN messages m ON m.id=a.message_id WHERE m.character_id=?",arrayOf(characterId)).use { c -> while(c.moveToNext()) ids+=c.getString(0) }
        db.beginTransaction()
        try {
            db.delete("message_attachments","message_id IN (SELECT id FROM messages WHERE character_id=?)",arrayOf(characterId))
            db.delete("messages","character_id=?",arrayOf(characterId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        ids.forEach { runCatching { attachmentFile(it).delete() } }
    }
    @Synchronized fun addMemory(characterId:String,text:String) {
        require(text.isNotBlank())
        db.execSQL("INSERT INTO memories(character_id,text,created_at) VALUES(?,?,?)",arrayOf<Any>(characterId,text.trim(),System.currentTimeMillis()))
    }
    @Synchronized fun memories(characterId:String):List<Pair<Long,String>> {
        val out=mutableListOf<Pair<Long,String>>()
        db.rawQuery("SELECT id,text FROM memories WHERE character_id=? OR character_id='global' ORDER BY id DESC",arrayOf(characterId)).use { c -> while(c.moveToNext()) out+=c.getLong(0) to c.getString(1) }
        return out
    }
    @Synchronized fun deleteMemory(id:Long) { db.delete("memories","id=?",arrayOf(id.toString())) }

    fun libraryDirectory()=java.io.File(app.filesDir,"kira/library").absolutePath
    fun installedSkills():List<String> = java.io.File(app.filesDir,"kira/library").listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()

    @Synchronized fun skillsFor(characterId:String):List<LocalSkill> {
        val out=mutableListOf<LocalSkill>()
        val cid=characterId.lowercase()
        db.rawQuery(
            "SELECT s.name,s.description,s.instructions,s.source,CASE WHEN EXISTS(SELECT 1 FROM skill_assignments a WHERE a.skill_name=s.name AND a.character_id=? ) THEN (SELECT a.enabled FROM skill_assignments a WHERE a.skill_name=s.name AND a.character_id=? LIMIT 1) WHEN EXISTS(SELECT 1 FROM skill_assignments a WHERE a.skill_name=s.name AND a.character_id='global') THEN (SELECT a.enabled FROM skill_assignments a WHERE a.skill_name=s.name AND a.character_id='global' LIMIT 1) ELSE 0 END FROM skills s ORDER BY s.name",
            arrayOf(cid,cid)
        ).use { c -> while(c.moveToNext()) out += LocalSkill(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4)!=0) }
        return out
    }

    @Synchronized fun setSkillEnabled(name:String,characterId:String,enabled:Boolean) {
        require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "شناسه Skill معتبر نیست." }
        val cid=characterId.lowercase()
        require(cid in setOf("global","kira","ren","mika")) { "شخصیت معتبر نیست." }
        val values=ContentValues().apply { put("skill_name",name);put("character_id",cid);put("enabled",if(enabled) 1 else 0) }
        db.insertWithOnConflict("skill_assignments",null,values,SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun removeSkill(name:String) {
        require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")))
        db.beginTransaction()
        try {
            db.delete("skill_connectors","skill_name=?",arrayOf(name))
            db.delete("skill_assignments","skill_name=?",arrayOf(name))
            db.delete("skills","name=?",arrayOf(name))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val folder=java.io.File(app.filesDir,"kira/library/"+name)
        folder.deleteRecursively()
    }

    @Synchronized fun installApiSkill(
        name:String,
        description:String,
        instructions:String,
        baseUrl:String,
        pathTemplate:String,
        allowedParameters:List<String>,
        apiKey:String="",
        characterId:String="global",
        source:String="custom-api"
    ) {
        val skillName=name.trim().lowercase()
        require(skillName.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && skillName.length<=64) { "نام Skill فقط باید شامل حروف کوچک انگلیسی، عدد و خط تیره باشد." }
        require(description.trim().isNotBlank() && description.length<=500) { "توضیحات Skill باید بین ۱ تا ۵۰۰ نویسه باشد." }
        require(instructions.length<=4000) { "دستورهای Skill بیش از حد طولانی است." }
        val base=URL(baseUrl.trim())
        require(base.protocol=="https" && base.host.isNotBlank() && base.userInfo==null && base.query==null && base.ref==null) { "آدرس پایه باید HTTPS و بدون اطلاعات ورود یا پارامتر باشد." }
        val host=base.host.lowercase()
        require(host!="localhost" && !host.endsWith(".local") && !host.endsWith(".localhost") &&
            !host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) && !host.contains(":") &&
            (base.port==-1 || base.port==443)) { "اتصال فقط به دامنه HTTPS روی پورت پیش‌فرض مجاز است." }
        val path=pathTemplate.trim()
        require(path.startsWith("/") && !path.contains("..") && !path.contains("?") && !path.contains("#") && path.length<=300) { "مسیر Endpoint معتبر نیست؛ پارامترها را در فهرست پارامترها تعریف کن." }
        val params=allowedParameters.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(params.size in 1..20 && params.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,39}")) }) { "نام پارامترها معتبر نیست." }
        val placeholders=Regex("\\{([A-Za-z][A-Za-z0-9_]*)}").findAll(path).map { it.groupValues[1] }.toSet()
        require(placeholders.all { it in params }) { "همه پارامترهای مسیر باید در فهرست پارامترها وجود داشته باشند." }
        require(path.count { it=='{' }==placeholders.size && path.count { it=='}' }==placeholders.size) { "قالب مسیر معتبر نیست." }
        val cid=characterId.lowercase()
        require(cid in setOf("global","kira","ren","mika")) { "شخصیت معتبر نیست." }
        require(!db.rawQuery("SELECT 1 FROM skills WHERE name=?",arrayOf(skillName)).use { it.moveToFirst() }) {
            "این نام از قبل در کتابخانه وجود دارد؛ برای جلوگیری از جایگزینی ناخواسته، نام دیگری انتخاب کن."
        }
        val connectorId="$skillName-query"
        val folder=java.io.File(app.filesDir,"kira/library/"+skillName)
        if(!folder.exists() && !folder.mkdirs()) throw IllegalStateException("ساخت پوشه Skill ناموفق بود.")
        val safeDescription=description.trim()
        val safeInstructions=instructions.trim().ifBlank { safeDescription }
        val markdown="---\nname: $skillName\ndescription: \"$safeDescription\"\n---\n\n$safeInstructions\n"
        java.io.File(folder,"SKILL.md").writeText(markdown,StandardCharsets.UTF_8)
        val connectorValues=ContentValues().apply {
            put("id",connectorId);put("skill_name",skillName);put("description",safeDescription)
            put("base_url",base.toString().trimEnd('/'));put("path_template",path)
            put("allowed_params",JSONArray(params).toString())
            put("auth_type",if(apiKey.isBlank()) "none" else "bearer")
            put("encrypted_key",if(apiKey.isBlank()) "" else vault.encrypt(apiKey.trim()))
            put("enabled",1);put("created_at",System.currentTimeMillis())
        }
        db.beginTransaction()
        try {
            val skillValues=ContentValues().apply {
                put("name",skillName);put("description",safeDescription);put("character_id","global")
                put("enabled",1);put("created_at",System.currentTimeMillis())
                put("instructions",safeInstructions);put("source",source)
            }
            db.insertWithOnConflict("skills",null,skillValues,SQLiteDatabase.CONFLICT_REPLACE)
            db.insertWithOnConflict("skill_assignments",null,ContentValues().apply {
                put("skill_name",skillName);put("character_id",cid);put("enabled",1)
            },SQLiteDatabase.CONFLICT_REPLACE)
            db.insertWithOnConflict("skill_connectors",null,connectorValues,SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun ensureBuiltinSkills() {
        fun exists(name:String)=db.rawQuery("SELECT 1 FROM skills WHERE name=?",arrayOf(name)).use { it.moveToFirst() }
        if(!exists("currency-rates")) installApiSkill(
            "currency-rates","نرخ مرجع ارز از Frankfurter",
            "برای نرخ مرجع ارز از Connector استفاده کن. این نرخ‌ها قیمت لحظه‌ای بازار یا نرخ صرافی نیستند؛ تاریخ داده را در پاسخ ذکر کن. برای تبدیل مبلغ، نرخ را دریافت و مقدار را محاسبه کن.",
            "https://api.frankfurter.dev","/v2/rate/{base}/{quote}",listOf("base","quote"),"","global","builtin"
        )
        if(!exists("weather-location")) installApiSkill(
            "weather-location","جست‌وجوی مختصات شهر با Open-Meteo",
            "برای پیدا کردن مختصات شهر قبل از درخواست پیش‌بینی هوا استفاده کن. نام شهر را به زبان کاربر بده و در صورت چند نتیجه، کشور و منطقه را بررسی کن.",
            "https://geocoding-api.open-meteo.com","/v1/search",listOf("name","count","language"),"","global","builtin"
        )
        if(!exists("weather-forecast")) installApiSkill(
            "weather-forecast","پیش‌بینی و وضعیت فعلی هوا با Open-Meteo",
            "برای اطلاعات هواشناسی از مختصات به‌دست‌آمده از weather-location استفاده کن. latitude و longitude الزامی‌اند. پارامتر current را با متغیرهایی مانند temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m درخواست کن و timezone را متناسب با شهر تنظیم کن. زمان و واحدهای برگشتی را در پاسخ حفظ کن.",
            "https://api.open-meteo.com","/v1/forecast",listOf("latitude","longitude","current","daily","timezone","forecast_days","temperature_unit","wind_speed_unit"),"","global","builtin"
        )
        db.execSQL("UPDATE skills SET source='builtin' WHERE name IN ('currency-rates','weather-location','weather-forecast')")
    }

    @Synchronized fun connectorDefinitions(characterId:String):List<LocalConnectorDefinition> {
        val out=mutableListOf<LocalConnectorDefinition>()
        val cid=characterId.lowercase()
        val sql="SELECT c.id,c.description,c.allowed_params FROM skill_connectors c WHERE c.enabled=1 AND (EXISTS(SELECT 1 FROM skill_assignments a WHERE a.skill_name=c.skill_name AND a.character_id=? AND a.enabled=1) OR (NOT EXISTS(SELECT 1 FROM skill_assignments a WHERE a.skill_name=c.skill_name AND a.character_id=?) AND EXISTS(SELECT 1 FROM skill_assignments a WHERE a.skill_name=c.skill_name AND a.character_id='global' AND a.enabled=1))) ORDER BY c.id"
        db.rawQuery(sql,arrayOf(cid,cid)).use { c ->
            while(c.moveToNext()) {
                val params=JSONArray(c.getString(2))
                out += LocalConnectorDefinition(c.getString(0),c.getString(1),(0 until params.length()).map { params.optString(it) })
            }
        }
        return out
    }

    /** Reads the enabled connector's trusted endpoint configuration from local storage. */
    @Synchronized fun connectorExecutionConfig(characterId:String,id:String):LocalConnectorExecutionConfig? {
        val cid=characterId.lowercase()
        val row=db.rawQuery(
            "SELECT c.skill_name,c.base_url,c.path_template,c.allowed_params,c.auth_type,c.encrypted_key FROM skill_connectors c WHERE c.id=? AND c.enabled=1 LIMIT 1",
            arrayOf(id)
        ).use { c -> if(c.moveToFirst()) arrayOf(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5)) else null }
            ?: return null
        val enabled=db.rawQuery(
            "SELECT 1 FROM skill_assignments a WHERE a.skill_name=? AND a.character_id=? AND a.enabled=1 UNION ALL SELECT 1 FROM skill_assignments g WHERE g.skill_name=? AND g.character_id='global' AND g.enabled=1 AND NOT EXISTS(SELECT 1 FROM skill_assignments x WHERE x.skill_name=? AND x.character_id=?) LIMIT 1",
            arrayOf(row[0],cid,row[0],row[0],cid)
        ).use { it.moveToFirst() }
        if(!enabled) return null
        val values=JSONArray(row[3])
        val apiKey=if(row[4]=="bearer") vault.decrypt(row[5]) else ""
        return LocalConnectorExecutionConfig(
            id,row[1],row[2],(0 until values.length()).map { values.optString(it) },row[4],apiKey
        )
    }

    /** Persistence-only write; turn orchestration belongs to Application. */
    @Synchronized fun appendChatMessage(characterId:String,role:String,content:String,route:String="",attachments:List<ChatAttachmentInput> = emptyList()) {
        require(characterId.isNotBlank()); require(role=="user" || role=="assistant"); require(content.isNotBlank())
        require(role=="user" || attachments.isEmpty()); require(attachments.size<=5)
        val stored=mutableListOf<java.io.File>()
        db.beginTransaction()
        try {
            val messageId=db.insertOrThrow("messages",null,ContentValues().apply {
                put("character_id",characterId); put("role",role); put("content",content); put("route",route); put("created_at",System.currentTimeMillis())
            })
            attachments.forEach { a ->
                require(a.name.isNotBlank() && a.name.length<=160)
                val id=UUID.randomUUID().toString(); val dest=attachmentFile(id); val temp=java.io.File(attachmentDirectory,"."+id+".tmp")
                try { temp.writeBytes(attachmentVault.encryptBytes(a.bytes)); if(!temp.renameTo(dest)) throw IllegalStateException("ذخیره امن پیوست ناموفق بود.") }
                finally { if(temp.exists()) temp.delete() }
                stored+=dest
                db.insertOrThrow("message_attachments",null,ContentValues().apply {
                    put("id",id); put("message_id",messageId); put("name",a.name.trim()); put("mime_type",a.mimeType.substringBefore(';').trim().lowercase()); put("size_bytes",a.bytes.size.toLong())
                })
            }
            db.setTransactionSuccessful()
        } catch(e:Exception) { stored.forEach { runCatching { it.delete() } }; throw e }
        finally { db.endTransaction() }
    }
    private fun attachmentFile(id:String):java.io.File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}")))
        val root=attachmentDirectory.canonicalFile; val file=java.io.File(attachmentDirectory,id+".bin").canonicalFile
        require(file.parentFile==root); return file
    }

    /** Plaintext exists only in memory for one authenticated outbound request. */
    fun providerApiKey(providerId:String):String = readKey(providerId)

    private fun readKey(id:String):String {
        val encrypted=db.rawQuery("SELECT encrypted_key FROM providers WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) it.getString(0) else "" }
        val key=vault.decrypt(encrypted)
        if(key.isBlank()) throw IllegalStateException("API Key برای "+id+" ذخیره نشده است.")
        return key
    }


}
