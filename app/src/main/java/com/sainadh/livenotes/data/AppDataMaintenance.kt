package com.sainadh.livenotes.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/** Process-wide gate shared by the screen and foreground recording service. */
object AppDataMaintenance {
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private var owner: Any? = null

    @Synchronized
    fun tryBegin(canStart: () -> Boolean): Closeable? {
        if (owner != null || !canStart()) return null
        val token = Any()
        owner = token
        mutableBusy.value = true
        return Closeable {
            synchronized(this) {
                if (owner === token) {
                    owner = null
                    mutableBusy.value = false
                }
            }
        }
    }
}
