package org.razomy.rn.intellij.plugin

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object RnLanguage : Language("RazomyNotation") {
    private fun readResolve(): Any = RnLanguage
    override fun getDisplayName(): String = "Razomy Notation (.rn)"
}

object RnIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", RnIcons::class.java)
}

object RnFileType : LanguageFileType(RnLanguage) {
    override fun getName(): String = "Razomy Notation"
    override fun getDescription(): String =
        "Razomy Notation (.rn) — Layer 0 ontological data, outliner, and markup notation"
    override fun getDefaultExtension(): String = "rn"
    override fun getIcon(): Icon = RnIcons.FILE
}