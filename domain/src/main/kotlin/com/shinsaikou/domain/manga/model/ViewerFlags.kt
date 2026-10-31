package com.shinsaikou.domain.manga.model

import tachiyomi.domain.manga.model.Manga

val Manga.readingMode: ReadingMode get() = ReadingMode.fromFlags(viewerFlags)

val Manga.readerOrientation: ReaderOrientation get() = ReaderOrientation.fromFlags(viewerFlags)

/** Bits 0 to 5 of viewer_flags are defined; everything above is reserved and preserved untouched. */
fun Long.withReadingMode(mode: ReadingMode): Long =
    (this and ReadingMode.MASK.toLong().inv()) or mode.flagValue.toLong()

fun Long.withReaderOrientation(orientation: ReaderOrientation): Long =
    (this and ReaderOrientation.MASK.toLong().inv()) or orientation.flagValue.toLong()
