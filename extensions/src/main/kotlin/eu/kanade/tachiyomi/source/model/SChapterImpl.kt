// Vendored from upstream v0.20.4: source-api/src/main/kotlin/eu/kanade/tachiyomi/source/model/SChapterImpl.kt (modified: yes) imports EMPTY from its moved package
@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import tachiyomi.core.common.extensions.EMPTY

class SChapterImpl : SChapter {

    override lateinit var url: String

    override lateinit var name: String

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    override var date_upload: Long = 0

    override var memo: JsonObject = JsonObject.EMPTY
}
