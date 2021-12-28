package org.crev.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.ide.FileSelectInContext
import com.intellij.ide.SelectInManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.layout.CCFlags
import com.intellij.ui.layout.LCFlags
import com.intellij.ui.layout.panel
import com.intellij.util.ui.JBUI
import org.crev.*
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.util.RsCommandLineEditor
import org.rust.ide.notifications.showBalloon
import org.rust.openapiext.toPsiFile
import javax.swing.ListCellRenderer

class CreateReviewPage(
    private val project: Project,
    private val toolWindow: CrevToolWindow
) : Page {
    private var crate: CrateListItem? = null
    private var thoroughness: Level = Level.Low
    private var understanding: Level = Level.Medium
    private var rating: Rating = Rating.Positive

    private var commentArea: JBTextArea = JBTextArea(10, 0).apply {
        emptyText.text = "Comment"
        border = JBUI.Borders.empty(1, 8, 4, 8)
        lineWrap = true
    }

    private val alternatives: RsCommandLineEditor = RsCommandLineEditor(project, CrevCrateCompletionProvider())

    private val contentPanel: DialogPanel by lazy {
        panel(LCFlags.fillX) {
            val cli = CargoCrevCli(project.toolchain!!)

            row {
                textField({ crate?.packageName.toString() }, {}).apply {
                    component.border = null
                    component.isEditable = false
                }
                textField({ crate?.version.toString() }, {}).apply {
                    component.border = null
                    component.isEditable = false
                }
                link("How to Review") {
                    BrowserUtil.browse(REVIEW_GUIDE_LINK)
                }
            }

            row("Thoroughness:") {
                comboBox(
                    EnumComboBoxModel(Level::class.java),
                    ::thoroughness,
                    newToStringCellRenderer()
                )
            }
            row("Understanding:") {
                comboBox(
                    EnumComboBoxModel(Level::class.java),
                    ::understanding,
                    newToStringCellRenderer()
                )
            }
            row("Rating:") {
                comboBox(
                    EnumComboBoxModel(Rating::class.java),
                    ::rating,
                    newToStringCellRenderer()
                )
            }
            row("Alternatives:") { alternatives(CCFlags.growX) }

            row {
                scrollPane(commentArea)
            }

            row {
                button("Publish review") {
                    publishReview(cli)
                }
                button("Cancel") {
                    toolWindow.showCrateList()
                }
            }
        }.apply {
            border = JBUI.Borders.empty(8, 8, 8, 8)
        }
    }

    private fun publishReview(cli: CargoCrevCli) {
        contentPanel.apply()
        val crate = crate ?: return
        val review = Review(
            thoroughness = thoroughness,
            understanding = understanding,
            rating = rating,
        )
        val alternativesList = alternatives.text.split(',')

        val proto = CrevPackagePrototype(review, alternativesList, commentArea.text)

        class PublishReviewTask : Task.Backgroundable(project, "Publishing Crev review", false) {
            override fun run(indicator: ProgressIndicator) {
                cli.review(crate.packageName, crate.version, proto)
                cli.publishRepo()
            }

            override fun onSuccess() {
                project.showBalloon("Pushed the review!", NotificationType.INFORMATION)
            }

            override fun onThrowable(error: Throwable) {
                project.showBalloon("Failed to publish the review!", NotificationType.ERROR)
                super.onThrowable(error) // log it
            }
        }

        PublishReviewTask().queue()

        toolWindow.showCrateList()
    }

    override val content: Content by lazy {
        ContentFactory.SERVICE.getInstance().createContent(contentPanel, "Review", false)
    }

    private fun reset() {
        thoroughness = Level.Low
        understanding = Level.Medium
        rating = Rating.Positive
        commentArea.text = ""
        contentPanel.reset()
    }

    fun configureForCrate(crate: CrateListItem) {
        this.crate = crate
        reset()
    }

    override fun afterShow() {
        val realCrate = project.cargoProjects.allProjects
            .flatMap { it.workspace?.packages.orEmpty() }
            .filter { it.origin == PackageOrigin.DEPENDENCY }
            .find { it.name == crate?.packageName && it.version == crate?.version }
            ?: return

        val crateRootVFile = realCrate.libTarget?.crateRoot ?: return
        val crateRoot = crateRootVFile.toPsiFile(project) ?: return
        crateRoot.navigate(false)
        SelectInManager.findSelectInTarget(ToolWindowId.PROJECT_VIEW, project)
            .selectIn(FileSelectInContext(project, crateRootVFile), false)
    }

    companion object {
        private const val REVIEW_GUIDE_LINK = "https://github.com/crev-dev/cargo-crev/blob/master/crev-lib/rc/doc/editing-package-review.md"
    }
}

fun <T> newToStringCellRenderer(): ListCellRenderer<T> = SimpleListCellRenderer.create("") {
    it.toString()
}