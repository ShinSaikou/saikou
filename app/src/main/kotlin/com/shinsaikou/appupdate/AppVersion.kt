package com.shinsaikou.appupdate

/**
 * A version as dot-separated numbers, compared part by part as numbers rather than as text, so
 * 0.10.0 is newer than 0.9.0. A missing part counts as zero: 0.1 and 0.1.0 are the same version.
 */
class AppVersion private constructor(private val parts: List<Int>) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val difference = parts.getOrElse(index) { 0 }.compareTo(other.parts.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0

    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()

    /** The numbers as written, `0.0.2`, which is how the prompt names the version and how a skip is stored. */
    override fun toString(): String = parts.joinToString(".")

    companion object {
        /** Up to nine digits a part, so every part fits an `Int`. */
        private val NUMBERS = Regex("""\d{1,9}(\.\d{1,9})*""")

        /**
         * A release tag, `v0.0.2` or `0.0.2`. Anything else (a suffix, a word, an empty tag) is null,
         * and a release whose tag is null offers no update.
         */
        fun fromTag(tag: String): AppVersion? {
            val text = tag.trim().removePrefix("v").removePrefix("V")
            if (!NUMBERS.matches(text)) return null
            return AppVersion(text.split('.').map(String::toInt))
        }

        /**
         * The installed build's version name, with a build type suffix such as `-debug` (or anything
         * after a `-` or a `+`) removed first, so `0.0.1-debug` is 0.0.1.
         */
        fun fromVersionName(versionName: String): AppVersion? =
            fromTag(versionName.substringBefore('-').substringBefore('+'))
    }
}
