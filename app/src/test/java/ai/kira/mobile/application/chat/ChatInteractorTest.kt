package ai.kira.mobile.application.chat

import ai.kira.mobile.application.assistant.AssistantInteractor
import ai.kira.mobile.domain.model.ChatMessage
import ai.kira.mobile.domain.model.ConnectorDefinition
import ai.kira.mobile.domain.model.MemoryNote
import ai.kira.mobile.domain.model.ProviderRoute
import ai.kira.mobile.domain.model.SkillEntry
import ai.kira.mobile.domain.repository.AssistantRepository
import ai.kira.mobile.domain.repository.ChatCompletionGateway
import ai.kira.mobile.domain.repository.ConnectorExecutionGateway
import ai.kira.mobile.domain.repository.ChatRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInteractorTest {
    @Test
    fun sendMessageTrimsTextPersistsTheTurnAndReturnsProviderMetadata() {
        val repository=FakeChatRepository(listOf(route("openrouter")))
        val gateway=FakeGateway("hello Kira")
        val result=interactor(repository,gateway).sendMessage(" kira ","  hello Kira  ")
        assertEquals("hello Kira",repository.appended.first().content)
        assertEquals("user",repository.appended.first().role)
        assertEquals("hello Kira",result.content)
        assertEquals("openrouter",result.provider)
        assertEquals("model:free",result.model)
        assertEquals(1,gateway.calls)
        assertTrue(gateway.lastSystemPrompt.contains("remembered fact"))
    }

    @Test
    fun failedProviderFallsBackToNextEligibleRoute() {
        val repository=FakeChatRepository(listOf(route("openrouter",0),route("gemini",1)))
        val gateway=FakeGateway("unused").apply {
            failures["openrouter"]=IllegalStateException("provider offline")
            responses["gemini"]="recovered"
        }
        val result=interactor(repository,gateway).sendMessage("kira","hello")
        assertEquals("gemini",result.provider)
        assertEquals("recovered",result.content)
        assertEquals(2,gateway.calls)
    }

    @Test
    fun connectorExecutionIsBoundedAndReturnedAsUntrustedContext() {
        val repository=FakeChatRepository(listOf(route("gemini")))
        val connector=FakeConnectorGateway().apply {
            responses["<kira_connector>{...}</kira_connector>"]="real connector data"
        }
        val gateway=FakeGateway("unused").apply {
            queuedResponses.add("<kira_connector>{...}</kira_connector>")
            queuedResponses.add("final answer")
        }
        val result=interactor(repository,gateway,connector).sendMessage("kira","what is current weather?")
        assertEquals("final answer",result.content)
        assertEquals(2,gateway.calls)
        assertEquals("assistant",repository.appended.last().role)
        assertEquals("final answer",repository.appended.last().content)
        assertTrue(gateway.observedHistory.any { it.content.contains("real connector data") })
    }

    @Test(expected=IllegalArgumentException::class)
    fun sendMessageRejectsBlankInput() {
        interactor(FakeChatRepository(),FakeGateway("unused")).sendMessage("kira","  \n  ")
    }

    @Test(expected=IllegalArgumentException::class)
    fun historyRejectsNonPositiveLimit() {
        interactor(FakeChatRepository(),FakeGateway("unused")).history("kira",0)
    }

    @Test(expected=IllegalStateException::class)
    fun sendMessageRequiresAnEligibleConfiguredRoute() {
        interactor(FakeChatRepository(),FakeGateway("unused")).sendMessage("kira","hello")
    }

    private fun interactor(repository:FakeChatRepository,gateway:FakeGateway,connector:FakeConnectorGateway=FakeConnectorGateway())=
        ChatInteractor(repository,gateway,connector,ChatContextBuilder(AssistantInteractor(FakeAssistantRepository()),repository))

    private fun route(provider:String,priority:Int=0)=ProviderRoute(
        providerId=provider,modelId=if(provider=="openrouter") "model:free" else "gemini-3.8-flash",
        baseUrl="https://example.test/v1",enabled=true,priority=priority,hasKey=true
    )

    private class FakeGateway(private val fallback:String):ChatCompletionGateway {
        val failures=mutableMapOf<String,Exception>()
        val responses=mutableMapOf<String,String>()
        val queuedResponses=ArrayDeque<String>()
        var calls=0
        var lastSystemPrompt=""
        var observedHistory:List<ChatMessage> = emptyList()
        override fun complete(route:ProviderRoute,apiKey:String,systemPrompt:String,history:List<ChatMessage>):String {
            calls++; lastSystemPrompt=systemPrompt; observedHistory=history
            failures[route.providerId]?.let { throw it }
            return if(queuedResponses.isNotEmpty()) queuedResponses.removeFirst() else responses[route.providerId] ?: fallback
        }
    }

    private class FakeChatRepository(private val routes:List<ProviderRoute> = emptyList()):ChatRepository {
        val appended=mutableListOf<ChatMessage>()
        override fun history(characterId:String,limit:Int)=appended.takeLast(limit)
        override fun providerRoutes()=routes
        override fun providerApiKey(providerId:String)="secret-test-key"
        override fun appendMessage(characterId:String,role:String,content:String,route:String) {
            appended += ChatMessage(appended.size.toLong()+1L,role,content,route,0L)
        }
        override fun connectorsFor(characterId:String)=emptyList<ConnectorDefinition>()
        override fun clearHistory(characterId:String)=Unit
    }

    private class FakeConnectorGateway:ConnectorExecutionGateway {
        val responses=mutableMapOf<String,String>()
        override fun executeIfRequested(characterId:String,assistantContent:String):String? = responses[assistantContent]
    }

    private class FakeAssistantRepository:AssistantRepository {
        override fun memories(characterId:String)=listOf(MemoryNote(1L,"remembered fact"))
        override fun addMemory(characterId:String,text:String)=Unit
        override fun deleteMemory(id:Long)=Unit
        override fun skillsFor(characterId:String)=listOf(SkillEntry("sample","sample skill","safe instructions","test",true))
        override fun setSkillEnabled(name:String,characterId:String,enabled:Boolean)=Unit
        override fun removeSkill(name:String)=Unit
        override fun installApiSkill(name:String,description:String,instructions:String,baseUrl:String,
            pathTemplate:String,allowedParameters:List<String>,apiKey:String,characterId:String,source:String)=Unit
    }
}
