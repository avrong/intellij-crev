package org.crev.toolwindow

import com.intellij.ui.content.Content

interface Page {
    val content: Content
    fun afterShow() {}
    fun afterHide() {}
}