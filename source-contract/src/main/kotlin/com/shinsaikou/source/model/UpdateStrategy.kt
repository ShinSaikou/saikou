package com.shinsaikou.source.model

/** Mirrors eu.kanade.tachiyomi.source.model.UpdateStrategy; ordinal order matters for the database column. */
enum class UpdateStrategy {
    /** Default: the library update pass refreshes this manga. */
    ALWAYS_UPDATE,

    /** Chapters are fetched once and never refreshed automatically (one-shots, completed series). */
    ONLY_FETCH_ONCE,
}
