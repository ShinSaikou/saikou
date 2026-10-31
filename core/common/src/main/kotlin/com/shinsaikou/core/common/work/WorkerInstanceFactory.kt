package com.shinsaikou.core.common.work

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/**
 * Map key for the worker multibinding the app's WorkerFactory reads.
 */
@MapKey
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WorkerKey(val value: KClass<out ListenableWorker>)

/**
 * The assisted factory every worker declares. It lives here, not in the app module, because
 * the modules that own the workers cannot depend on the app module (architecture ruling R8).
 */
interface WorkerInstanceFactory<T : ListenableWorker> {
    fun create(params: WorkerParameters): T
}
