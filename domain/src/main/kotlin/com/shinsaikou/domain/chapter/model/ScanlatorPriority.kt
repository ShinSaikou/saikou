package com.shinsaikou.domain.chapter.model

/** Per-manga scanlator ranking; the lowest priority wins when two groups post the same chapter. */
data class ScanlatorPriority(val mangaId: Long, val scanlator: String, val priority: Long)
