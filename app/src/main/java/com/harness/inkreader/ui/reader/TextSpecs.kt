package com.harness.inkreader.ui.reader

import android.graphics.Typeface
import com.harness.inkreader.data.settings.ReaderPalettes
import com.harness.inkreader.data.settings.ReaderSettings
import com.harness.inkreader.engine.TextSpec

object TextSpecs {

    /** 把用户设置翻译成排版规格。任何一个字段变化都会让阅读页重新分页。 */
    fun from(settings: ReaderSettings, typeface: Typeface): TextSpec = TextSpec(
        textSizeSp = settings.textSizeSp,
        lineSpacingMultiplier = settings.lineSpacingMultiplier,
        letterSpacingEm = settings.letterSpacingEm,
        firstLineIndentEm = settings.firstLineIndentEm,
        paragraphSpacingEm = settings.paragraphSpacingEm,
        typeface = typeface,
        bold = settings.bold,
        justify = settings.justify,
        textColor = ReaderPalettes.of(settings.theme).text.toInt(),
    )
}
