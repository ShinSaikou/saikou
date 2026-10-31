package com.shinsaikou.source.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UpdateStrategyTest {

    @Test
    fun `the ordinals the database column stores never move`() {
        UpdateStrategy.ALWAYS_UPDATE.ordinal shouldBe 0
        UpdateStrategy.ONLY_FETCH_ONCE.ordinal shouldBe 1
    }
}
