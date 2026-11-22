package ai.kira.mobile.application.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayRouteParserTest {
    @Test
    fun parsesAllSupportedDestinations() {
        assertEquals(OverlayDestination.CHAT, OverlayRouteParser.parse("chat"))
        assertEquals(OverlayDestination.SEARCH, OverlayRouteParser.parse("search"))
        assertEquals(OverlayDestination.IMAGE, OverlayRouteParser.parse("image"))
        assertEquals(OverlayDestination.VOICE, OverlayRouteParser.parse("voice"))
    }

    @Test
    fun defaultsUnknownOrMissingRoutesToChat() {
        assertEquals(OverlayDestination.CHAT, OverlayRouteParser.parse(null))
        assertEquals(OverlayDestination.CHAT, OverlayRouteParser.parse("not-a-route"))
    }

    @Test
    fun ignoresWhitespaceAndCase() {
        assertEquals(OverlayDestination.SEARCH, OverlayRouteParser.parse(" SEARCH "))
    }
}
