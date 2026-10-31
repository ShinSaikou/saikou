package com.shinsaikou.ui.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import tachiyomi.core.common.preference.Preference

/**
 * The Android preference store replays the current value from `changes()`; the in-memory store the
 * tests use does not. Prepending the current value makes both behave the same way, and the extra
 * emission it costs in production collapses in the `MutableStateFlow` behind a view model's state.
 *
 * A `combine` over preferences needs this: without it, the whole projection waits on the first
 * write to any one of them and the screen never leaves its loading state under test.
 */
internal fun <T> Preference<T>.withCurrent(): Flow<T> = changes().onStart { emit(get()) }
