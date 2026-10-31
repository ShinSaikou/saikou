package com.shinsaikou.data.extension

import tachiyomi.domain.extension.model.ExtensionStore

/**
 * The `extension_store` columns in declaration order. The two contact columns fold back into the
 * nested [ExtensionStore.Contact] the model declares.
 */
fun mapExtensionStore(
    indexUrl: String,
    name: String,
    badgeLabel: String,
    signingKey: String,
    contactWebsite: String,
    contactDiscord: String?,
    isLegacy: Boolean,
    extensionListUrl: String?,
): ExtensionStore = ExtensionStore(
    indexUrl = indexUrl,
    name = name,
    badgeLabel = badgeLabel,
    signingKey = signingKey,
    contact = ExtensionStore.Contact(website = contactWebsite, discord = contactDiscord),
    isLegacy = isLegacy,
    extensionListUrl = extensionListUrl,
)
