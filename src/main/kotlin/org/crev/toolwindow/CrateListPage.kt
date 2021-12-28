package org.crev.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.layout.LCFlags
import com.intellij.ui.layout.Row
import com.intellij.ui.layout.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import org.rust.cargo.icons.CargoIcons
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.workspace.PackageOrigin
import javax.swing.*

class CrateListPage(
    project: Project,
    private val toolWindow: CrevToolWindow
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

    private val selectedDependency: CrateListItem?
        get() = dependencyList.selectedValue

    private lateinit var newReviewButton: JButton
    private lateinit var crateNameLabel: JLabel
    private lateinit var crateVersionLabel: JLabel
    private lateinit var infoRow: Row

    init {
        project.messageBus.connect().subscribe(CargoProjectsService.CARGO_PROJECTS_TOPIC,
            CargoProjectsService.CargoProjectsListener { _, projects ->
                updateDependencyList(projects)
            }
        )
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
                dependencyList.selectedValue?.let { toolWindow.showReviewPage(it) }
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