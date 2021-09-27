/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.crev

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.execution.ExecutionException
import com.intellij.util.io.exists
import org.rust.cargo.toolchain.RsToolchainBase
import org.rust.cargo.toolchain.tools.cargo
import org.rust.openapiext.RsPathManager
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class CargoCrevCli(
    toolchain: RsToolchainBase
) {
    private val cargo = toolchain.cargo()
    private val cargoExecutablePath: String = cargo.executable.toString()
    private val tempProject = RsPathManager.tempPluginDirInSystem().resolve("cargo-crev-helper-repo")
    private val reviewDraft = RsPathManager.tempPluginDirInSystem().resolve("cargo-crev-review-draft.yaml")
    private val editorMock = RsPathManager.tempPluginDirInSystem().resolve("cat-cargo-crev-review-draft-to-file.sh")

    init {
        if (!tempProject.resolve("Cargo.toml").exists()) {
            execute0("new", "--bin", tempProject.toString())
        }
        Files.writeString(editorMock, """
            #!/bin/bash
            cat ${reviewDraft}>"${'$'}{1?}"
            sleep 1
            touch "${'$'}{1?}"
            # EOL!
        """.trimIndent())
        editorMock.toFile().setExecutable(true)
    }

    private fun execute0(vararg args: String): Process {
        val builder = ProcessBuilder(cargoExecutablePath, *args)
        val process = builder.start()
        val status = process.waitFor()
        if (status != 0) {
            throw ExecutionException("Exit code $status; " +
                "OUT: ${process.inputStream.bufferedReader().readText()};" +
                "ERR: ${process.errorStream.bufferedReader().readText()}")
        }
        return process
    }

    fun execute(vararg args: String): Process {
        return execute0("crev", *args)
    }

    fun review(crate: String, version: String, prototype: CrevPackagePrototype) {
        writeReviewFile(prototype, reviewDraft)

        val builder = ProcessBuilder(
            cargoExecutablePath,
            "crev",
            "review",
            "--skip-activity-check",
            "--unrelated",
            "--manifest-path",
            tempProject.toString(),
            crate,
            version
        )
        builder.environment()["EDITOR"] = editorMock.toString()
        val process = builder.start()
        val status = process.waitFor()
        if (status != 0) {
            throw ExecutionException("Exit code $status; " +
                "OUT: ${process.inputStream.bufferedReader().readText()};" +
                "ERR: ${process.errorStream.bufferedReader().readText()}")
        }
    }

    fun currentId(): String? {
        val builder = ProcessBuilder(cargoExecutablePath, "crev", "id", "current")
        val process = builder.start()
        val status = process.waitFor()
        if (status == 0) {
            val resultRaw = process.inputStream.bufferedReader().readText()
            // TODO: we're assuming here that only one id is returned. that might not be true in general.
            return resultRaw.split(" ")[0]
        } else {
            return null
        }
    }

    fun newId(url: String) {
        val builder = ProcessBuilder(cargoExecutablePath, "crev", "id", "new", "--url", url)
        val process = builder.start()
        val writer = PrintWriter(process.outputStream).buffered()
        writer.write("\n")
        writer.flush()
        writer.write("\n")
        writer.flush()
        val status = process.waitFor()
        if (status != 0) {
            throw ExecutionException("Exit code $status; " +
                    "OUT: ${process.inputStream.bufferedReader().readText()};" +
                    "ERR: ${process.errorStream.bufferedReader().readText()}")
        }
    }

    fun queryReviewsCurrentAuthor(): List<CrevPackage> {
        val id = currentId() ?: return emptyList()

        val process = execute(
            "proof",
            "find",
            "--author",
            id
        )
        val resultRaw = process.inputStream.bufferedReader().readText()

        return resultRaw.splitToSequence("---\n").mapNotNull {  yaml ->
            if (yaml.isBlank()) return@mapNotNull null
            val mapper = ObjectMapper(YAMLFactory()) // Enable YAML parsing
            mapper.registerModule(KotlinModule())
            val result = mapper.readValue(yaml, CrevPackage::class.java)
            result
        }.toList()
    }

    fun checkCrevExists(): Boolean {
        val builder = ProcessBuilder(cargoExecutablePath, "crev", "--version")
        val process = builder.start()
        val status = process.waitFor()
        return status == 0
    }

    fun publishRepo() {
        execute(
            "repo",
            "publish"
        )
    }
}

private fun writeReviewFile(prototype: CrevPackagePrototype, toFile: Path) {
    val review = prototype.review
    val comment = prototype.comment.replace("\n", "\n|  ")

    val alternatives = prototype.alternatives.fold("") { acc, name ->
        acc + """
        |  - source: "https://crates.io"
        |    name: $name
        |
    """.trimMargin()
    }

    val fileContent = """
        |review:
        |  thoroughness: ${review.thoroughness.toString().lowercase()}
        |  understanding: ${review.understanding.toString().lowercase()}
        |  rating: ${review.rating.toString().lowercase()}
        |alternatives:
        |$alternatives
        |comment: |-
        |  $comment
    """.trimMargin()

    Files.writeString(toFile, fileContent)
}
