package com.shinsaikou.source.model

/**
 * Publication status with upstream's integer codes (SManga.UNKNOWN .. SManga.ON_HIATUS). The codes
 * are what the database column, the backup format and the extension ABI all carry; never reorder.
 */
enum class MangaStatus(val code: Int) {
    UNKNOWN(0),
    ONGOING(1),
    COMPLETED(2),
    LICENSED(3),
    PUBLISHING_FINISHED(4),
    CANCELLED(5),
    ON_HIATUS(6),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** Any code outside 0..6 maps to [UNKNOWN]; extensions do emit stray values. */
        fun fromCode(code: Int): MangaStatus = byCode[code] ?: UNKNOWN
    }
}
