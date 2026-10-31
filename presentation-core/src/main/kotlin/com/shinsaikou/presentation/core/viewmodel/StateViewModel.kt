// Vendored from upstream v0.20.4: core/viewmodel/src/main/kotlin/tachiyomi/core/viewmodel/StateViewModel.kt (modified: yes)
package com.shinsaikou.presentation.core.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

abstract class StateViewModel<S>(initialState: S) : ViewModel() {
    protected val mutableState: MutableStateFlow<S> = MutableStateFlow(initialState)
    val state: StateFlow<S> = mutableState.asStateFlow()
}
