package com.shinsaikou.ui.settings

import androidx.compose.runtime.Composable
import com.shinsaikou.domain.source.interactor.languageDisplayName
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Which languages the extension list, the Sources page and Global Search offer (ruling R57).
 *
 * A ticked box means enabled, the same direction the stored set reads. [candidates] arrive in the
 * order the view model settled on, `"all"` first. The working copy is local and keyed on the
 * input, so nothing is written until the owner confirms.
 */
@Composable
internal fun EnabledLanguagesDialog(
    candidates: List<String>,
    enabled: Set<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    MultiSelectDialog(
        title = stringResource(R.string.pref_enabled_languages),
        entries = candidates.map { it to enabledLanguageLabel(it) },
        selected = enabled,
        onDismissRequest = onDismissRequest,
        onConfirm = onConfirm,
    )
}

/**
 * The full display name rather than the language alone: a restored set can hold both `en` and
 * `en-US`, and two rows that both read "English" could not be told apart. `"all"` is the code of
 * multi-language sources and has no locale behind it. Any other code is worded by
 * [languageDisplayName], which the source filter also sorts by.
 */
@Composable
internal fun enabledLanguageLabel(code: String): String = when {
    code == LANGUAGE_ALL -> stringResource(R.string.ext_language_all)
    code.isBlank() -> stringResource(R.string.pref_enabled_languages_unspecified)
    else -> languageDisplayName(code)
}

private const val LANGUAGE_ALL = "all"
