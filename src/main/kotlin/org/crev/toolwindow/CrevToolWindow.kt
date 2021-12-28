package org.crev.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.content.ContentManager

class CrevToolWindow(
    project: Project,
    private val contentMgr: ContentManager
) {
    private val initialSetupPage: InitialSetupPage = InitialSetupPage(project, this)
    private val crateListPage: CrateListPage = CrateListPage(project, this)
    private val creatingReviewPage: CreateReviewPage = CreateReviewPage(project, this)

    private var currentPage: Page? = null

    private fun showPage(newPage: Page) {
        val oldPage = currentPage
        if (oldPage != null) {
            contentMgr.removeContent(oldPage.content, false)
            oldPage.afterHide()
        }
        currentPage = newPage
        contentMgr.addContent(newPage.content)
        newPage.afterShow()
    }

    fun showInitialPage() {
        showPage(initialSetupPage)
    }

    fun showCrateList() {
        showPage(crateListPage)
    }

    fun showReviewPage(crate: CrateListItem) {
        creatingReviewPage.configureForCrate(crate)
        showPage(creatingReviewPage)
    }
}