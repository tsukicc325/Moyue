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
        if (intent == null) return
        // 1) adb / 脚本用的显式路径参数
        intent.getStringExtra(InkApp.EXTRA_IMPORT_PATH)?.let { path ->
            (application as InkApp).pendingImportPath.value = path
            return
        }
        // 2) 文件管理器「打开方式」/「分享」送来的 content:// URI
        val app = application as InkApp
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { app.pendingImportUri.value = it.toString() }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                uri?.let { app.pendingImportUri.value = it.toString() }
            }
            else -> Unit
        }
    }

    private companion object {
        const val LIGHT_SCRIM = 0xE6FAF7F0.toInt()
        const val DARK_SCRIM = 0xE6121212.toInt()
    }
}
