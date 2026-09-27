package com.sainadh.livenotes.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** USD amounts are Console setup references only. Never use them as checkout prices. */
enum class SupportTier(val productId: String, val title: String, val usd: Int) {
    SMALL("support_coffee_2", "A little thanks", 2),
    MEDIUM("support_coffee_4", "A coffee", 4),
    LARGE("support_coffee_7", "A generous thanks", 7),
    EXTRA("support_coffee_10", "An extra boost", 10);
}

data class SupportOffer(val productId: String, val price: String, val currency: String, val micros: Long)
data class SupportPurchase(val token: String, val products: List<String>, val pending: Boolean)
data class SupportState(
    val offers: List<SupportOffer> = emptyList(),
    val loading: Boolean = false,
    val checkingOut: Boolean = false,
    val processing: Boolean = false,
    val pendingProducts: Set<String> = emptySet(),
    val unconfirmedProducts: Set<String> = emptySet(),
    val message: String? = null,
    val thanked: Boolean = false,
) {
    val busy: Boolean get() = loading || checkingOut || processing
}

/** All callbacks are delivered on the main thread. No Activity is retained here. */
interface SupportStore {
    var onPurchases: (Result<List<SupportPurchase>>) -> Unit
    fun connect(callback: (Result<Unit>) -> Unit)
    fun offers(callback: (Result<List<SupportOffer>>) -> Unit)
    fun purchases(callback: (Result<List<SupportPurchase>>) -> Unit)
    fun consume(token: String, callback: (Result<Unit>) -> Unit)
    fun close()
}

class SupportPurchases(private val store: SupportStore) {
    private val mutableState = MutableStateFlow(SupportState())
    val state = mutableState.asStateFlow()
    private val inFlight = mutableSetOf<String>()
    private val completed = mutableSetOf<String>()
    private var purchaseRevision = 0L
    private var closed = false

    init {
        store.onPurchases = { result ->
            if (!closed) {
                purchaseRevision++
                update { copy(checkingOut = false) }
                result.fold(::process, ::error)
            }
        }
    }

    fun refresh() {
        if (closed || state.value.loading) return
        update { copy(loading = true, message = null, thanked = false) }
        store.connect { connection ->
            if (!closed) connection.fold({
                var offersDone = false
                var purchasesDone = false
                fun finish() {
                    if (offersDone && purchasesDone) update { copy(loading = false) }
                }
                store.offers { result ->
                    offersDone = true
                    if (!closed) result.fold({ offers ->
                        update { copy(offers = offers) }
                    }, { failure ->
                        update { copy(offers = emptyList()) }
                        error(failure)
                    })
                    if (!closed) finish()
                }
                val revision = purchaseRevision
                store.purchases { result ->
                    purchasesDone = true
                    if (!closed) result.fold({ purchases ->
                        // A checkout callback received since this query wins over its older snapshot.
                        if (revision == purchaseRevision) {
                            update { copy(checkingOut = false, pendingProducts = emptySet()) }
                            // A previous consume may have reached Play even if its response was lost.
                            // An empty fresh ownership query must allow future support purchases.
                            if (inFlight.isEmpty()) update { copy(unconfirmedProducts = emptySet()) }
                            process(purchases)
                        }
                    }, ::error)
                    if (!closed) finish()
                }
            }, { failure ->
                update { copy(offers = emptyList(), loading = false) }
                error(failure)
            })
        }
    }

    /** Refresh before checkout; never silently charge a changed price. */
    fun buy(productId: String, launch: (SupportOffer) -> Result<Unit>) {
        val current = state.value
        if (closed || current.busy || current.unconfirmedProducts.isNotEmpty() || productId in current.pendingProducts) return
        val shown = current.offers.singleOrNull { it.productId == productId } ?: return
        update { copy(checkingOut = true, message = null, thanked = false) }
        store.offers { result ->
            if (!closed) result.fold({ offers ->
                update { copy(offers = offers) }
                val fresh = offers.singleOrNull { it.productId == productId }
                if (fresh == null || fresh != shown) {
                    update { copy(checkingOut = false, message = "Prices changed or this option is unavailable. Review the options and try again.") }
                } else {
                    launch(fresh).onFailure {
                        update { copy(checkingOut = false) }
                        error(it)
                    }
                }
            }, {
                update { copy(checkingOut = false) }
                error(it)
            })
        }
    }

    private fun process(purchases: List<SupportPurchase>) {
        purchases.forEach { purchase ->
            if (purchase.products.isEmpty() || purchase.products.any { id -> SupportTier.entries.none { it.productId == id } }) return@forEach
            if (purchase.pending) {
                update { copy(pendingProducts = pendingProducts + purchase.products, message = "Payment pending. Google Play will confirm when it completes.") }
                return@forEach
            }
            update { copy(pendingProducts = pendingProducts - purchase.products.toSet()) }
            if (purchase.token in completed || !inFlight.add(purchase.token)) return@forEach
            update { copy(processing = true, unconfirmedProducts = unconfirmedProducts + purchase.products) }
            // Consume is Google's completion acknowledgement for a repeatable purchase.
            // Do not show success before Google accepts it; failed tokens remain recoverable.
            store.consume(purchase.token) { result ->
                if (!closed) {
                    inFlight.remove(purchase.token)
                    update { copy(processing = inFlight.isNotEmpty()) }
                    result.fold({
                        completed.add(purchase.token)
                        update { copy(unconfirmedProducts = unconfirmedProducts - purchase.products.toSet(), thanked = true, message = "Thank you for supporting Live Meeting Notes!") }
                    }, {
                        update { copy(message = "Your payment needs confirmation. Tap Refresh to retry; please do not pay again yet.") }
                    })
                }
            }
        }
    }

    private fun error(failure: Throwable) {
        update { copy(checkingOut = false, thanked = false, message = failure.message ?: "Google Play is unavailable. Please try again.") }
    }

    private fun update(block: SupportState.() -> SupportState) {
        mutableState.value = state.value.block()
    }

    fun close() {
        closed = true
        store.close()
    }
}
