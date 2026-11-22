package ai.kira.mobile.application.navigation

/** Stable top-level destinations shared by UI state restoration and overlay routing. */
enum class KiraTab(val label: String, val glyph: String) {
    CHAT("چت", "✦"),
    SEARCH("جستجو", "⌕"),
    IMAGE("تصویر", "▧"),
    CHARACTERS("شخصیت‌ها", "♧"),
    SETTINGS("تنظیمات", "⚙")
}

enum class SettingsPage { HOME, ROUTE, MEMORY, SKILLS, ASSISTANT, VOICE }

enum class BackTarget { SETTINGS_HOME, PREVIOUS_TAB, CHAT }

/** Platform-neutral back policy; Android BackHandler is only its UI adapter. */
object KiraNavigationPolicy {
    fun previousTabOrChat(previousTab: KiraTab): KiraTab =
        if (previousTab == KiraTab.SETTINGS) KiraTab.CHAT else previousTab

    fun backTarget(tab: KiraTab, settingsPage: SettingsPage): BackTarget? = when {
        tab == KiraTab.SETTINGS && settingsPage != SettingsPage.HOME -> BackTarget.SETTINGS_HOME
        tab == KiraTab.SETTINGS -> BackTarget.PREVIOUS_TAB
        tab != KiraTab.CHAT -> BackTarget.CHAT
        else -> null
    }
}
