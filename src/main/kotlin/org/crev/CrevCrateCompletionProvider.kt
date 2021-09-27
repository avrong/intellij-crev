/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.crev

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.util.TextFieldCompletionProvider
import org.rust.toml.crates.local.CratesLocalIndexException
import org.rust.toml.crates.local.CratesLocalIndexService

class CrevCrateCompletionProvider : TextFieldCompletionProvider() {
    override fun getPrefix(currentTextPrefix: String): String =
        currentTextPrefix.substringAfterLast(",").trim()

    override fun addCompletionVariants(text: String, offset: Int, prefix: String, result: CompletionResultSet) {
        val crateNames = try {
            CratesLocalIndexService.getInstance().getAllCrateNames()
        } catch (e: CratesLocalIndexException) {
            return
        }
        result.addAllElements(crateNames.map { LookupElementBuilder.create(it) })
    }
}
