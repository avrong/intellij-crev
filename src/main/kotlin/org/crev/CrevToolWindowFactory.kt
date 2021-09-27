/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.crev

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.ide.BrowserUtil
import com.intellij.ide.FileSelectInContext
import com.intellij.ide.SelectInManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.layout.CCFlags
import com.intellij.ui.layout.LCFlags
import com.intellij.ui.layout.Row
import com.intellij.ui.layout.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import org.rust.cargo.icons.CargoIcons
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.CargoProjectsService.CargoProjectsListener
import org.rust.cargo.project.model.CargoProjectsService.Companion.CARGO_PROJECTS_TOPIC
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.toolchain.tools.Cargo
import org.rust.cargo.toolchain.tools.RsTool
import org.rust.cargo.toolchain.tools.cargo
import org.rust.cargo.util.RsCommandLineEditor
import org.rust.ide.notifications.showBalloon
import org.rust.openapiext.execute
import org.rust.openapiext.toPsiFile
import java.nio.file.Path
import javax.swing.*

class CrevToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val crevToolWindow = CrevToolWindow(project, toolWindow.contentManager)
        crevToolWindow.showInitialPage()
    }
}

class CrevToolWindow(
    project: Project,
    private val contentMgr: ContentManager
) {
    private val initialSetupPage = InitialSetupPage(project, this)
    private val crateListPage = CrateListPage(project, this)
    private val creatingReviewPage = CreatingCrevReviewPage(project, this)

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

interface Page {
    val content: Content
    fun afterShow() {}
    fun afterHide() {}
}

class InitialSetupPage(
    private val project: Project,
    private val toolwindow: CrevToolWindow,
) : Page {
    private lateinit var noToolchainRow: Row
    private lateinit var noIdRow: Row
    private lateinit var noCrevRow: Row
    private lateinit var installCargoCrev: JComponent
    private lateinit var url: JBTextField

    private fun getContentPanel() = panel(LCFlags.fillX) {
        noCrevRow = row {
            row {
                label("No cargo-crev found!")
            }
            row {
                installCargoCrev = link("Install cargo-crev using Cargo") {
                    installCargoCrev()
                }.component
            }
            visible = false
            subRowsVisible = false
        }
        noToolchainRow = row {
            row {
                label("No Rust toolchain found!")
            }
            row {
                browserLink("Please install Rust", "https://rust-lang.org/tools/install")
            }
            visible = false
            subRowsVisible = false
        }
        url = JBTextField("")
        noIdRow = row {
            nestedPanel {
                row {
                    label("No Crev ID!")
                }
                row {
                    browserLink("Fork the template on GitHub...", "https://github.com/crev-dev/crev-proofs/fork")
                }
                row {
                    label("...and insert the resulting URL here:")
                }
                row {
                    url = textField({ "" }, {}).component
                }
                row {
                    button("Create Crev ID with this URL") {
                        val cli = CargoCrevCli(project.toolchain!!)
                        cli.newId(url.text)
                        cli.publishRepo()
                        toolwindow.showCrateList()
                    }
                }
            }
            visible = false
            subRowsVisible = false
        }
    }.apply {
        border = JBUI.Borders.empty(8, 8, 8, 8)
    }

    override val content by lazy {
        ContentFactory.SERVICE.getInstance().createContent(getContentPanel(), "Setup", false)
    }

    override fun afterShow() {
        update()
    }

    private fun update() {
        val toolchain = project.toolchain
        noToolchainRow.visible = toolchain == null
        noToolchainRow.subRowsVisible = toolchain == null
        if (toolchain == null) {
            return
        }
        val cli = CargoCrevCli(toolchain)
        val crevExists = cli.checkCrevExists()
        noCrevRow.visible = !crevExists
        noCrevRow.subRowsVisible = !crevExists
        if (!crevExists) {
            return
        }
        val hasId = cli.currentId() != null
        noIdRow.visible = !hasId
        noIdRow.subRowsVisible = !hasId

        if (hasId) {
            SwingUtilities.invokeLater {
                toolwindow.showCrateList()
            }
        }
    }

    private fun installCargoCrev() {
        val cargo = project.toolchain?.cargo() ?: return

        object : Task.Modal(null, "Installing Cargo-Crev", true) {
            var exitCode = 0

            override fun onSuccess() {
                if (exitCode != 0) {
                    PopupUtil.showBalloonForComponent(
                        installCargoCrev,
                        "Failed to install cargo-crev",
                        MessageType.ERROR,
                        true,
                        Disposer.newDisposable("Never disposed")
                    )
                }

                update()
            }

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                cargo.installCargoCrev(Disposer.newDisposable("Never disposed"), listener = object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        indicator.text = "Installing using Cargo..."
                        indicator.text2 = event.text.trim()
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        exitCode = event.exitCode
                    }
                })
            }
        }.queue()
    }
}

class CrateListPage(
    project: Project,
    private val toolwindow: CrevToolWindow,
) : Page {
    private val dependencyListModel: DefaultListModel<CrateListItem> =
        JBList.createDefaultListModel(emptyList())

    private val dependencyList: JBList<CrateListItem> = JBList(dependencyListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectedIndex = 0
        addListSelectionListener { update() }
        cellRenderer = object : ColoredListCellRenderer<CrateListItem>() {
            override fun customizeCellRenderer(
                list: JList<out CrateListItem>,
                value: CrateListItem,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                icon = CargoIcons.ICON
                append(value.packageName)

                append(" ")
                append(value.version, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    val selectedDependency: CrateListItem?
        get() = dependencyList.selectedValue

    private lateinit var newReviewButton: JButton
    private lateinit var crateNameLabel: JLabel
    private lateinit var crateVersionLabel: JLabel
    private lateinit var infoRow: Row

    init {
        project.messageBus.connect().subscribe(CARGO_PROJECTS_TOPIC, CargoProjectsListener { _, projects ->
            updateDependencyList(projects)
        })
        updateDependencyList(project.cargoProjects.allProjects)
    }

    private fun updateDependencyList(projects: Collection<CargoProject>) {
        val deps = projects
            .flatMap { it.workspace?.packages.orEmpty() }
            .filter { it.origin == PackageOrigin.DEPENDENCY }
            .map { CrateListItem(it.name, it.version) }
        dependencyListModel.removeAllElements()
        dependencyListModel.addAll(deps)
    }

    private fun update() {
        if (selectedDependency != null) {
            infoRow.apply {
                visible = true
                subRowsVisible = true
            }
        }

        val selectedCrate = dependencyList.selectedValue ?: return
        newReviewButton.isEnabled = true
        crateNameLabel.text = selectedCrate.packageName
        crateVersionLabel.text = selectedCrate.version
    }

    private fun getContentPanel() = panel(LCFlags.fillX) {
        row {
            scrollPane(dependencyList)
        }

        row {
            visible = false
            subRowsVisible = false

            row {

                label("", JBFont.h3()).apply {
                    crateNameLabel = component
                }
                label("", JBFont.h4().asItalic()).apply {
                    crateVersionLabel = component
                }
            }

            row {
                cell {
                    link("Crates.io") {
                        BrowserUtil.browse("https://crates.io/crates/${selectedDependency?.packageName}/${selectedDependency?.version}")
                    }

                    link("Deps.rs") {
                        BrowserUtil.browse("https://deps.rs/crate/${selectedDependency?.packageName}/${selectedDependency?.version}")
                    }
                }
            }
        }.apply {
            infoRow = this
        }

        row {
            button("New review") {
                dependencyList.selectedValue?.let { toolwindow.showReviewPage(it) }
            }.apply {
                component.isEnabled = false
                newReviewButton = component
            }
        }
    }.apply {
        border = JBUI.Borders.empty(8, 8, 8, 8)
    }

    override val content: Content by lazy {
        ContentFactory.SERVICE.getInstance().createContent(getContentPanel(), "Dependencies", false)
    }
}

data class CrateListItem(
    val packageName: String,
    val version: String,
)

class CreatingCrevReviewPage(
    private val project: Project,
    private val toolwindow: CrevToolWindow
) : Page {
    private var crate: CrateListItem? = null
    private var thoroughness = Level.Low
    private var understanding = Level.Medium
    private var rating = Rating.Positive

    private var commentArea = JBTextArea(10, 0).apply {
        emptyText.text = "Comment"
        border = JBUI.Borders.empty(1, 8, 4, 8)
        lineWrap = true
    }

    private val alternatives = RsCommandLineEditor(project, CrevCrateCompletionProvider())

    // EditorTextFieldProvider.getInstance().getEditorField(
    //        FileTypes.PLAIN_TEXT.language,
    //        project,
    //        hashSetOf(
    //            SoftWrapsEditorCustomization.ENABLED,
    //            AdditionalPageAtBottomEditorCustomization.DISABLED
    //        )
    //    ).apply {
    //        setPlaceholder("Comment")
    //    }

    private val contentPanel: DialogPanel by lazy {
        panel(LCFlags.fillX) {
            val cli = CargoCrevCli(project.toolchain!!)

            //        val parameters = panel {
            //            row("Thoroughness:") { thoroughness() }
            //            row("Understanding:") { understanding() }
            //            row("Rating:") { rating() }
            //        }

            //        val scrollPane = JBScrollPane(parameters)

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
                    BrowserUtil.browse("https://github.com/crev-dev/cargo-crev/blob/" +
                        "82f6372b94cacb995f5e5c1735cfb6457cff02fc/crev-lib/rc/doc/editing-package-review.md")
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
                //            textArea(::commentValue, {
                //                commentValue = it
                //            }, 10)
                scrollPane(commentArea)
                //            commentArea()
            }

            row {
                button("Publish review") {
                    publishReview(cli)
                }
                button("Cancel") {
                    toolwindow.showCrateList()
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

        toolwindow.showCrateList()
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
}

fun <T> newToStringCellRenderer(): ListCellRenderer<T> = SimpleListCellRenderer.create("") {
    it.toString()
}

private fun Cargo.installCargoCrev(owner: Disposable, listener: ProcessListener) {
    hackCreateBaseCommandLine(listOf("install", "cargo-crev")).execute(owner, listener = listener)
}

// TODO oh God, wtf
private fun Cargo.hackCreateBaseCommandLine(
    parameters: List<String>,
    workingDirectory: Path? = null,
    environment: Map<String, String> = emptyMap()
): GeneralCommandLine {
    val m = RsTool::class.java.getDeclaredMethod("createBaseCommandLine", List::class.java, Path::class.java, Map::class.java)
    m.isAccessible = true
    return m.invoke(this, parameters, workingDirectory, environment) as GeneralCommandLine
}