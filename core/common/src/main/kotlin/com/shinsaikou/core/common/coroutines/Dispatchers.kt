package com.shinsaikou.core.common.coroutines

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The three dispatchers every module takes from the graph instead of reaching for
 * [kotlinx.coroutines.Dispatchers] directly, so a test can substitute test dispatchers.
 */
data class Dispatchers(
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
    val main: CoroutineDispatcher,
)
