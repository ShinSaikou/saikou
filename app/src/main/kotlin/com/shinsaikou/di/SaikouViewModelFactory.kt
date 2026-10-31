package com.shinsaikou.di

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.MetroViewModelFactory
import dev.zacsweers.metrox.viewmodel.ViewModelAssistedFactory
import kotlin.reflect.KClass

/**
 * The one `ViewModelProvider.Factory` in the app (contract 08 section 1.5). Every screen view model
 * reaches the graph through this: plain ones by `@ViewModelKey`, ones that take a navigation
 * argument by a `@ManualViewModelAssistedFactoryKey` factory.
 *
 * `MainActivity` provides it once as `LocalMetroViewModelFactory`, so a screen calls
 * `metroViewModel<T>()` and never names a factory.
 *
 * **Every contributed view model must spell its binding out:**
 * `@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())`. Without the explicit
 * binding Metro infers the bound type from the class's own supertype, so a view model extending
 * `StateViewModel<S>` lands in a `Map<KClass<*>, StateViewModel<S>>` that nothing requests instead
 * of in [viewModelProviders]. Nothing catches that at build time: the unrequested map is dropped,
 * this factory still resolves, and the app compiles, installs and then throws
 * `IllegalArgumentException: Unknown model class` on the first screen that is not `MainViewModel`
 * (which extends `ViewModel` directly and was therefore the only one ever in the map). The device
 * test `AppGraphTest` asserts the map's contents for exactly this reason.
 */
@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class SaikouViewModelFactory(
    override val viewModelProviders: Map<KClass<out ViewModel>, () -> ViewModel>,
    override val assistedFactoryProviders: Map<KClass<out ViewModel>, () -> ViewModelAssistedFactory>,
    override val manualAssistedFactoryProviders:
        Map<KClass<out ManualViewModelAssistedFactory>, () -> ManualViewModelAssistedFactory>,
) : MetroViewModelFactory()
