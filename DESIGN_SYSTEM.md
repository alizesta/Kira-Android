# Kira Android Design System

The Android app uses a dedicated `:core:designsystem` module. Feature modules consume
its theme, semantic tokens and reusable Compose components instead of defining local
colors, typography, shapes or one-off controls.

## Foundations
- **Color:** use `MaterialTheme.colorScheme` for semantic roles. Use `KiraPalette` only for brand decoration.
- **Typography:** use the Material 3 type scale from `MaterialTheme.typography`; avoid hard-coded font sizes.
- **Shape:** use `MaterialTheme.shapes` for consistent geometry.
- **Components:** prefer `KiraPrimaryButton`, `KiraSecondaryButton`, `KiraTextField`,
  `KiraCard`, `KiraSectionHeader`, `KiraEmptyState` and `KiraLoadingIndicator`.

## Jakob's Law and Android conventions
Kira should behave like a familiar Android app. Users spend most of their time in
other apps, so navigation and controls should follow established platform patterns.
- Keep 3–5 primary destinations in persistent Material 3 bottom navigation.
- Use conventional app bars, standard back navigation and predictable screen hierarchy.
- Use dialogs or bottom sheets for focused decisions; avoid custom modal behavior.
- Preserve native text selection, clipboard, keyboard/IME actions and system back.
- Use familiar switches, checkboxes, radio controls and text fields for settings.
- Request runtime permissions when a feature needs them and explain why.
- Keep interactive targets at least 48dp; never encode state by color alone.
- Respect font scaling, RTL, accessibility semantics and TalkBack.
- Show clear loading, empty, disabled, success and error states.
- Make destructive actions explicit and confirm them.

## Module boundaries
`core/designsystem` owns visual foundations and reusable presentation primitives.
It must not depend on app features, repositories, Android services or provider clients.
Feature modules may depend on the design system; the reverse dependency is forbidden.

## Acceptance criteria
- App and design-system modules resolve through Gradle.
- Existing screens keep the `ai.kira.mobile.ui.theme.KiraTheme` API.
- Tokens and shared components are available to feature UI.
- New UI can follow Material 3 conventions without copying style definitions.
