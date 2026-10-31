package com.shinsaikou.ui.util

import java.util.Locale

/**
 * The display name of a source contract language code, for a list heading or a row.
 *
 * `Locale.forLanguageTag` is fed the code as it came off the source rather than a validated subset,
 * because the contract's own set is wider than ISO 639-1: a region or script subtag ("pt-BR",
 * "zh-Hans") and a three-letter ISO 639-3 code ("fil", "ceb") are all values a source may honestly
 * report, and the platform resolves every one of them. Anything it cannot name falls back to the
 * code itself, which is still more use than a blank.
 */
internal fun languageName(lang: String): String =
    Locale.forLanguageTag(lang).displayLanguage.ifBlank { lang }
