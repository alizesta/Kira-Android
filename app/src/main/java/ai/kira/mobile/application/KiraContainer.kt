package ai.kira.mobile.application

import android.content.Context
import ai.kira.mobile.data.local.SqliteKiraStore
import ai.kira.mobile.application.assistant.AssistantInteractor
import ai.kira.mobile.application.chat.ChatContextBuilder
import ai.kira.mobile.application.chat.ChatInteractor
import ai.kira.mobile.application.image.ImageGenerationInteractor
import ai.kira.mobile.application.provider.ProviderApplicationService
import ai.kira.mobile.application.search.InternetSearchInteractor
import ai.kira.mobile.application.voice.RealtimeVoiceInteractor
import ai.kira.mobile.application.voice.ShenavaOfflineSpeechInteractor
import ai.kira.mobile.data.voice.SherpaShenavaOfflineSpeechRepository
import ai.kira.mobile.data.voice.KiraSpeechService
import ai.kira.mobile.data.image.ImageGenerationSettingsStore
import ai.kira.mobile.data.image.OpenAiCompatibleImageRepository
import ai.kira.mobile.data.local.LocalAssistantRepository
import ai.kira.mobile.data.local.LocalChatRepository
import ai.kira.mobile.data.local.LocalProviderRepository
import ai.kira.mobile.data.remote.HttpChatCompletionGateway
import ai.kira.mobile.data.remote.HttpApiConnectorGateway
import ai.kira.mobile.data.remote.ProviderDiscoveryRemoteDataSource
import ai.kira.mobile.data.remote.SearchCredentialStore
import ai.kira.mobile.data.remote.TavilySearchRepository
import ai.kira.mobile.data.voice.GeminiLiveCredentialStore
import ai.kira.mobile.data.voice.GeminiLiveVoiceSessionFactory

/** The single process-scoped composition root for Android services and adapters. */
class KiraContainer(context: Context) {
    private val app = context.applicationContext
    private val localCore = SqliteKiraStore(app)

    private val chatRepository: LocalChatRepository by lazy { LocalChatRepository(localCore) }
    private val chatCompletionGateway: HttpChatCompletionGateway by lazy { HttpChatCompletionGateway() }
    private val connectorExecutionGateway: HttpApiConnectorGateway by lazy { HttpApiConnectorGateway(chatRepository) }
    val chat: ChatInteractor by lazy {
        ChatInteractor(chatRepository, chatCompletionGateway, connectorExecutionGateway, ChatContextBuilder(assistant, chatRepository))
    }

    val assistant: AssistantInteractor by lazy {
        AssistantInteractor(LocalAssistantRepository(localCore))
    }

    private val providerDiscovery: ProviderDiscoveryRemoteDataSource by lazy { ProviderDiscoveryRemoteDataSource() }
    val providers: ProviderApplicationService by lazy {
        ProviderApplicationService(LocalProviderRepository(localCore, providerDiscovery))
    }

    private val searchCredentials: SearchCredentialStore by lazy { SearchCredentialStore(app) }
    val search: InternetSearchInteractor by lazy {
        InternetSearchInteractor(TavilySearchRepository(searchCredentials), searchCredentials)
    }

    private val imageSettings: ImageGenerationSettingsStore by lazy { ImageGenerationSettingsStore(app) }
    val images: ImageGenerationInteractor by lazy {
        ImageGenerationInteractor(OpenAiCompatibleImageRepository(app, imageSettings), imageSettings)
    }

    private val voiceCredentials: GeminiLiveCredentialStore by lazy { GeminiLiveCredentialStore(app) }
    private val voiceSessionFactory: GeminiLiveVoiceSessionFactory by lazy { GeminiLiveVoiceSessionFactory(app) }
    val voice: RealtimeVoiceInteractor by lazy {
        RealtimeVoiceInteractor(voiceCredentials, voiceSessionFactory)
    }

    val speech: KiraSpeechService by lazy { KiraSpeechService(app) }

    private val shenavaOfflineRepository: SherpaShenavaOfflineSpeechRepository by lazy {
        SherpaShenavaOfflineSpeechRepository(app)
    }
    val shenavaAsr: ShenavaOfflineSpeechInteractor by lazy {
        ShenavaOfflineSpeechInteractor(shenavaOfflineRepository)
    }

    fun libraryDirectory(): String = localCore.libraryDirectory()

    fun installedSkillCount(): Int = localCore.installedSkills().size
}
