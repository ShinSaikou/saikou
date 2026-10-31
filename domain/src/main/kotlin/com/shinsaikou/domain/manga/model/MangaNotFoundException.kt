package com.shinsaikou.domain.manga.model

/**
 * An entry's row left the database while a screen held its id: Clear database removed it (ruling
 * R119 item 4). The reader closes its session with it (R119 carry-over 1), and
 * [com.shinsaikou.domain.error.ErrorKey] gives it its own key, so the reader says why in the manga
 * screen's words. The message is for the log only; no screen shows it.
 */
class MangaNotFoundException(mangaId: Long) : IllegalStateException("manga $mangaId is no longer in the database")
