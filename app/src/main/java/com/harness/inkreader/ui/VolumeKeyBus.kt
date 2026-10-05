package com.harness.inkreader.ui

/**
 * 音量键翻页的中转站。
 *
 * 音量键会被系统先接走，Compose 的按键修饰符拿不到，必须在 Activity 的
 * `onKeyDown` 里拦截。阅读页通过 [handler] 注册处理器，返回 true 表示已消费。
 */
object VolumeKeyBus {
    @Volatile
    var handler: ((keyCode: Int) -> Boolean)? = null
}
