package com.harness.inkreader.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SettingsStore {
    val settings: Flow<ReaderSettings>

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings)
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "reader_settings",
)

class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    override val settings: Flow<ReaderSettings> = dataStore.data.map { it.toSettings() }

    override suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        dataStore.edit { preferences ->
            preferences.applySettings(transform(preferences.toSettings()))
        }
    }

    private companion object {
        val TEXT_SIZE = floatPreferencesKey("text_size_sp")
        val LINE_SPACING = floatPreferencesKey("line_spacing")
        val LETTER_SPACING = floatPreferencesKey("letter_spacing")
        val INDENT = floatPreferencesKey("first_line_indent")
        val PARAGRAPH_SPACING = floatPreferencesKey("paragraph_spacing")
        val FONT_ID = stringPreferencesKey("font_id")
        val BOLD = booleanPreferencesKey("bold")
        val JUSTIFY = booleanPreferencesKey("justify")
        val H_PADDING = intPreferencesKey("h_padding")
        val V_PADDING = intPreferencesKey("v_padding")
        val THEME_ID = stringPreferencesKey("theme_id")
        val BACKGROUND_IMAGE = stringPreferencesKey("background_image")
        val BACKGROUND_ALPHA = floatPreferencesKey("background_alpha")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val PAGE_TURN = stringPreferencesKey("page_turn")
        val READING_MODE = stringPreferencesKey("reading_mode")
        val VOLUME_PAGING = booleanPreferencesKey("volume_paging")
        val VOLUME_REVERSED = booleanPreferencesKey("volume_reversed")
        val TAP_LEFT_IS_NEXT = booleanPreferencesKey("tap_left_is_next")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SHOW_FOOTER = booleanPreferencesKey("show_footer")

        fun Preferences.toSettings(): ReaderSettings {
            val defaults = ReaderSettings()
            return ReaderSettings(
                textSizeSp = this[TEXT_SIZE] ?: defaults.textSizeSp,
                lineSpacingMultiplier = this[LINE_SPACING] ?: defaults.lineSpacingMultiplier,
                letterSpacingEm = this[LETTER_SPACING] ?: defaults.letterSpacingEm,
                firstLineIndentEm = this[INDENT] ?: defaults.firstLineIndentEm,
                paragraphSpacingEm = this[PARAGRAPH_SPACING] ?: defaults.paragraphSpacingEm,
                fontId = this[FONT_ID] ?: defaults.fontId,
                bold = this[BOLD] ?: defaults.bold,
                justify = this[JUSTIFY] ?: defaults.justify,
                horizontalPaddingDp = this[H_PADDING] ?: defaults.horizontalPaddingDp,
                verticalPaddingDp = this[V_PADDING] ?: defaults.verticalPaddingDp,
                themeId = this[THEME_ID] ?: defaults.themeId,
                backgroundImagePath = this[BACKGROUND_IMAGE],
                backgroundImageAlpha = this[BACKGROUND_ALPHA] ?: defaults.backgroundImageAlpha,
                brightness = this[BRIGHTNESS] ?: defaults.brightness,
                readingMode = this[READING_MODE] ?: defaults.readingMode,
                pageTurnMode = this[PAGE_TURN] ?: defaults.pageTurnMode,
                volumeKeyPaging = this[VOLUME_PAGING] ?: defaults.volumeKeyPaging,
                volumeKeyReversed = this[VOLUME_REVERSED] ?: defaults.volumeKeyReversed,
                tapLeftIsNext = this[TAP_LEFT_IS_NEXT] ?: defaults.tapLeftIsNext,
                keepScreenOn = this[KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
                showFooter = this[SHOW_FOOTER] ?: defaults.showFooter,
            )
        }

        fun androidx.datastore.preferences.core.MutablePreferences.applySettings(
            settings: ReaderSettings,
        ) {
            this[TEXT_SIZE] = settings.textSizeSp
            this[LINE_SPACING] = settings.lineSpacingMultiplier
            this[LETTER_SPACING] = settings.letterSpacingEm
            this[INDENT] = settings.firstLineIndentEm
            this[PARAGRAPH_SPACING] = settings.paragraphSpacingEm
            this[FONT_ID] = settings.fontId
            this[BOLD] = settings.bold
            this[JUSTIFY] = settings.justify
            this[H_PADDING] = settings.horizontalPaddingDp
            this[V_PADDING] = settings.verticalPaddingDp
            this[THEME_ID] = settings.themeId
            val image = settings.backgroundImagePath
            if (image == null) {
                remove(BACKGROUND_IMAGE)
            } else {
                this[BACKGROUND_IMAGE] = image
            }
            this[BACKGROUND_ALPHA] = settings.backgroundImageAlpha
            this[BRIGHTNESS] = settings.brightness
            this[READING_MODE] = settings.readingMode
            this[PAGE_TURN] = settings.pageTurnMode
            this[VOLUME_PAGING] = settings.volumeKeyPaging
            this[VOLUME_REVERSED] = settings.volumeKeyReversed
            this[TAP_LEFT_IS_NEXT] = settings.tapLeftIsNext
            this[KEEP_SCREEN_ON] = settings.keepScreenOn
            this[SHOW_FOOTER] = settings.showFooter
        }
    }
}
