package org.crev.toolwindow

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.layout.LCFlags
import com.intellij.ui.layout.Row
import com.intellij.ui.layout.panel
import com.intellij.util.ui.JBUI
import org.crev.CargoCrevCli
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.toolchain.tools.Cargo
import org.rust.cargo.toolchain.tools.RsTool
import org.rust.cargo.toolchain.tools.cargo
import org.rust.openapiext.execute
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.SwingUtilities

class InitialSetupPage(
    private val project: Project,
    private val toolWindow: CrevToolWindow
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
                        toolWindow.showCrateList()
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
                toolWindow.showCrateList()
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