/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.crev.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class CrevToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val crevToolWindow = CrevToolWindow(project, toolWindow.contentManager)
        crevToolWindow.showInitialPage()
    }
}