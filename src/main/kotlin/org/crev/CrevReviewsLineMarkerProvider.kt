/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.crev

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import org.rust.ide.lineMarkers.RsLineMarkerInfoUtils
import org.rust.toml.isCargoToml
import org.rust.toml.isDependencyListHeader
import org.rust.toml.tomlPluginIsAbiCompatible
import org.toml.lang.psi.TomlKeyValue
import org.toml.lang.psi.TomlTable

class CrevReviewsLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<PsiElement>? {
        if (!tomlPluginIsAbiCompatible()) return null
        if (!element.containingFile.isCargoToml) return null

        val keyValue = element as? TomlKeyValue ?: return null
        if ((keyValue.parent as? TomlTable)?.header?.isDependencyListHeader != true) return null

        val pkgName = element.key.text

        return RsLineMarkerInfoUtils.create(
            element,
            element.textRange,
            AllIcons.Ide.Notification.NoEvents,
            { _, _ -> BrowserUtil.browse("https://lib.rs/crates/$pkgName/crev") },
            GutterIconRenderer.Alignment.LEFT
        ) { "Open crev reviews for `$pkgName`" }
    }
}
