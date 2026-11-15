package com.tvku.core.common

import javax.inject.Qualifier

/** Qualifies an injected [kotlinx.coroutines.CoroutineDispatcher]. Bound in the app module. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: TvkuDispatchers)

enum class TvkuDispatchers { Default, IO }

/** Qualifies the process-wide [kotlinx.coroutines.CoroutineScope]. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
