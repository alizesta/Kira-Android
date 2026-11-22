# Kira Android application architecture

Kira Android is a native modular monolith aligned with the Windows application's
architectural responsibilities, using platform-native adapters rather than sharing
Python/Qt implementations.

| Windows responsibility | Android counterpart |
| --- | --- |
| `kira/domain` value objects | `domain/model` |
| `kira/core/interfaces` ports | `domain/repository` |
| `kira/services` application services | `application` interactors/services |
| `kira/infrastructure` adapters | `data/local`, `data/remote` |
| `kira/app/dependency_container.py` and `kira/ui/bootstrap.py` composition | `KiraMobileApplication` + `KiraContainer` |
| `kira/main.py` entry point | `MainActivity` entry point |

## Runtime composition

`MainActivity` handles Android lifecycle and incoming overlay intents only.
`KiraMobileApplication` owns the process-scoped `application.KiraContainer`. The container wires
application interactors, local repositories and remote gateways once. Compose screens
receive the root container or a use case; they do not construct cores, databases,
credential stores, HTTP clients or provider transports.

## Layer responsibilities

- **Domain:** platform-independent chat/provider/memory/skill/search models, repository
  ports and pure eligibility/navigation policies. No Android, Compose, SQLite, JSON
  transport or HTTP dependencies.
- **Application:** chat turn orchestration, prompt/context construction, model eligibility,
  provider failover, bounded connector rounds and feature use cases.
- **Data/local:** `SqliteKiraStore` owns SQLite, Android Keystore, local provider/skill configuration,
  encrypted attachments, and Kira Core session credentials. Repository adapters expose narrow Domain ports.
- **Data/remote:** OpenAI-compatible/Gemini chat transport, provider model discovery,
  approved HTTPS Connector calls, Kira Core API client, Tavily search, image generation and Gemini Live.
- **UI:** Compose screens and Android Activity/service lifecycle.
- **Composition:** `application.KiraContainer` is the sole app-level dependency composition root.

## Chat flow

`KiraChatScreen → ChatInteractor + ChatContextBuilder → ChatRepository +
ChatCompletionGateway + ConnectorExecutionGateway → LocalChatRepository +
HttpChatCompletionGateway + HttpApiConnectorGateway`.

Application owns message persistence decisions, free-route selection, failover and
connector round limits. A provider gateway performs one model turn. The Connector
gateway validates the model-requested call, checks the locally enabled configuration,
restricts requests to HTTPS/public destinations, disables redirects, and caps response
size. Decrypted credentials are short-lived in memory and are never written back in
plaintext. The local adapter only reads configuration and persists conversation data.

Provider discovery follows the same boundary:
`ProviderApplicationService → ProviderRepository + ProviderDiscoveryRemoteDataSource`.
The local store caches verified catalogs; the remote adapter performs all discovery
HTTP and free-model filtering. Kira Core access tokens are encrypted by `KiraCoreSessionStore`
in `data/local`; authenticated HTTP calls are isolated in `data/remote/KiraCoreClient`.

## Composition and migration boundary

`SqliteKiraStore` is the Android infrastructure implementation for SQLite, Android Keystore,
provider settings, encrypted attachments, and skill/connector configuration. It contains no
provider Chat, provider discovery, or Connector HTTP transport and no chat turn
orchestration/prompt composition. New feature rules belong in Domain/Application;
platform and network details stay in Data.

The Windows `kira/main.py` remains a thin entry point and delegates desktop composition
to `kira/ui/bootstrap.py`, matching Android's split between MainActivity and the
application composition root.


## Android chat attachments (Task 8)

Android chat accepts up to five user-selected files per turn. Application validates
safe names, MIME allow-lists, image signatures and byte limits (8 MiB per image,
512 KiB per text file, 12 MiB total). PNG/JPEG/WebP images and supported text/code
formats are accepted; arbitrary binary files and HTML are rejected.

The local adapter persists attachment metadata in SQLite and stores bytes in the
app-private directory encrypted with a dedicated Android Keystore AES/GCM key.
The HTTP gateway creates OpenAI-compatible image data URLs or Gemini inline image
parts. Text file contents are bounded and explicitly treated as untrusted reference
data. Images are routed only to Gemini or recognized Vision model IDs.
