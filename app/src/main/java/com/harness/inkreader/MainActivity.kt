package com.harness.inkreader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.harness.inkreader.ui.InkNavHost
import com.harness.inkreader.ui.VolumeKeyBus
import com.harness.inkreader.ui.theme.InkReaderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM),
        )
        handleImportIntent(intent)
        setContent {
            InkReaderTheme {
                InkNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    /**
     * 音量键翻页必须在这里拦：系统会先把音量键交给音频服务，
     * Compose 的按键修饰符拿不到。阅读页通过 [VolumeKeyBus] 注册处理器。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (VolumeKeyBus.handler?.invoke(keyCode) == true) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleImportIntent(intent: Intent?) {
        val path = intent?.getStringExtra(InkApp.EXTRA_IMPORT_PATH) ?: return
        (application as InkApp).pendingImportPath.value = path
    }

    private companion object {
        const val LIGHT_SCRIM = 0xE6FAF7F0.toInt()
        const val DARK_SCRIM = 0xE6121212.toInt()
    }
}
