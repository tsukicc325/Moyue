package com.harness.inkreader.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 设置落盘与读回：每一项都必须原样往返，且局部修改不能抹掉其它项。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsStoreRobolectricTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStoreSettingsStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = File(temp.root, "reader_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        store = DataStoreSettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `nothing written yet yields defaults`() = runBlocking {
        assertEquals(ReaderSettings(), store.settings.first())
    }

    @Test
    fun `every field round trips`() = runBlocking {
        val custom = ReaderSettings(
            textSizeSp = 26f,
            lineSpacingMultiplier = 2.1f,
            letterSpacingEm = 0.12f,
            firstLineIndentEm = 0f,
            paragraphSpacingEm = 0.6f,
            fontId = "file:自定义.ttf",
            bold = true,
            justify = true,
            horizontalPaddingDp = 36,
            verticalPaddingDp = 18,
            themeId = ReaderThemeId.NIGHT.name,
            backgroundImagePath = "/data/bg.jpg",
            backgroundImageAlpha = 0.5f,
            brightness = 0.42f,
            pageTurnMode = PageTurnMode.COVER.name,
            volumeKeyPaging = false,
            volumeKeyReversed = true,
            tapLeftIsNext = true,
            keepScreenOn = false,
            showFooter = false,
        )

        store.update { custom }

        assertEquals(custom, store.settings.first())
    }

    @Test
    fun `partial updates keep the other fields`() = runBlocking {
        store.update { it.copy(textSizeSp = 22f, themeId = ReaderThemeId.GREEN.name) }
        store.update { it.copy(lineSpacingMultiplier = 2.0f) }

        val settings = store.settings.first()
        assertEquals(22f, settings.textSizeSp)
        assertEquals(ReaderThemeId.GREEN.name, settings.themeId)
        assertEquals(2.0f, settings.lineSpacingMultiplier)
        assertEquals(ReaderSettings().firstLineIndentEm, settings.firstLineIndentEm)
    }

    @Test
    fun `clearing the background image really removes it`() = runBlocking {
        store.update { it.copy(backgroundImagePath = "/data/bg.jpg") }
        assertEquals("/data/bg.jpg", store.settings.first().backgroundImagePath)

        store.update { it.copy(backgroundImagePath = null) }
        assertNull(store.settings.first().backgroundImagePath)

        // 再写一次别的字段，背景图不能莫名其妙复活
        store.update { it.copy(textSizeSp = 20f) }
        assertNull(store.settings.first().backgroundImagePath)
    }

    @Test
    fun `brightness can go back to following the system`() = runBlocking {
        store.update { it.copy(brightness = 0.3f) }
        assertTrue(!store.settings.first().followsSystemBrightness)

        store.update { it.copy(brightness = ReaderSettings.FOLLOW_SYSTEM_BRIGHTNESS) }
        assertTrue(store.settings.first().followsSystemBrightness)
    }
}
