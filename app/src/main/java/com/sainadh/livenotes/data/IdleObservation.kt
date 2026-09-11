package com.sainadh.livenotes.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Cancel expensive archive observers during capture, rather than reading and dropping their results.
 * A downstream StateFlow keeps its previous snapshot; becoming idle starts a fresh observation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> observeWhenIdle(captureActive: Flow<Boolean>, observe: () -> Flow<T>): Flow<T> =
    captureActive.distinctUntilChanged().flatMapLatest { active ->
        if (active) emptyFlow() else observe()
    }
