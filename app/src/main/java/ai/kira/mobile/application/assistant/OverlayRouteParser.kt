package ai.kira.mobile.application.assistant

enum class OverlayDestination {
    CHAT,
    SEARCH,
    IMAGE,
    VOICE
}

object OverlayRouteParser {
    fun parse(route: String?): OverlayDestination = when (route?.trim()?.lowercase()) {
        "search" -> OverlayDestination.SEARCH
        "image" -> OverlayDestination.IMAGE
        "voice" -> OverlayDestination.VOICE
        else -> OverlayDestination.CHAT
    }
}
