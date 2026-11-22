package ai.kira.mobile.application.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KiraNavigationPolicyTest {
    @Test
    fun backFromSettingsSubpageReturnsToSettingsHome() {
        assertEquals(BackTarget.SETTINGS_HOME, KiraNavigationPolicy.backTarget(KiraTab.SETTINGS, SettingsPage.VOICE))
    }

    @Test
    fun backFromSettingsHomeReturnsToPreviousTab() {
        assertEquals(BackTarget.PREVIOUS_TAB, KiraNavigationPolicy.backTarget(KiraTab.SETTINGS, SettingsPage.HOME))
    }

    @Test
    fun invalidSettingsPreviousTabFallsBackToChat() {
        assertEquals(KiraTab.CHAT, KiraNavigationPolicy.previousTabOrChat(KiraTab.SETTINGS))
    }

    @Test
    fun validPreviousTabIsRestored() {
        assertEquals(KiraTab.SEARCH, KiraNavigationPolicy.previousTabOrChat(KiraTab.SEARCH))
    }

    @Test
    fun backFromAnyOtherTopLevelTabReturnsToChat() {
        assertEquals(BackTarget.CHAT, KiraNavigationPolicy.backTarget(KiraTab.SEARCH, SettingsPage.HOME))
        assertEquals(BackTarget.CHAT, KiraNavigationPolicy.backTarget(KiraTab.IMAGE, SettingsPage.HOME))
    }

    @Test
    fun chatRootLeavesSystemBackHandlingUntouched() {
        assertNull(KiraNavigationPolicy.backTarget(KiraTab.CHAT, SettingsPage.HOME))
    }
}
