package com.sainadh.livenotes.billing

import org.junit.Assert.*
import org.junit.Test

class SupportPurchasesTest {
    private val product = SupportTier.SMALL.productId
    private val inr = SupportOffer(product, "₹179.00", "INR", 179_000_000)

    @Test fun usesPlayPriceAndBlocksCheckoutIfPriceChanges() {
        val store = FakeStore(listOf(inr))
        val controller = SupportPurchases(store)
        controller.refresh()
        assertEquals("₹179.00", controller.state.value.offers.single().price)
        store.catalog = listOf(inr.copy(price = "₹189.00", micros = 189_000_000))
        var launched = false
        controller.buy(product) { launched = true; Result.success(Unit) }
        assertFalse(launched)
        assertFalse(controller.state.value.checkingOut)
        assertTrue(controller.state.value.message!!.contains("Prices changed"))
    }

    @Test fun pendingPaymentIsNeverConsumedAndCompletesAfterRestart() {
        val store = FakeStore(listOf(inr))
        store.owned = listOf(SupportPurchase("pending", listOf(product), true))
        val controller = SupportPurchases(store)
        controller.refresh()
        assertTrue(controller.state.value.pendingProducts.contains(product))
        assertTrue(store.consumed.isEmpty())
        controller.buy(product) { fail("Pending item must not launch again"); Result.success(Unit) }
        controller.close()
        val recoveredStore = FakeStore(listOf(inr))
        recoveredStore.owned = listOf(SupportPurchase("pending", listOf(product), false))
        val recovered = SupportPurchases(recoveredStore)
        recovered.refresh()
        assertEquals(listOf("pending"), recoveredStore.consumed)
        assertTrue(recovered.state.value.thanked)
        assertTrue(recovered.state.value.pendingProducts.isEmpty())
    }

    @Test fun duplicateCallbacksConsumeOnceAndAnotherPurchaseIsAllowed() {
        val store = FakeStore(listOf(inr))
        val controller = SupportPurchases(store)
        controller.refresh()
        val purchase = SupportPurchase("first", listOf(product), false)
        store.onPurchases(Result.success(listOf(purchase, purchase)))
        store.onPurchases(Result.success(listOf(purchase)))
        assertEquals(listOf("first"), store.consumed)
        var launched = false
        controller.buy(product) { launched = true; Result.success(Unit) }
        assertTrue(launched)
        store.onPurchases(Result.success(listOf(purchase.copy(token = "second"))))
        assertEquals(listOf("first", "second"), store.consumed)
    }

    @Test fun failedConsumptionIsRetriedAndDoesNotReportSuccessOrPermitAnotherPayment() {
        val store = FakeStore(listOf(inr))
        val controller = SupportPurchases(store)
        store.owned = listOf(SupportPurchase("retry", listOf(product), false))
        store.consumeResult = Result.failure(IllegalStateException("offline"))
        controller.refresh()
        assertFalse(controller.state.value.thanked)
        controller.buy(product) { fail("Do not allow payment while confirmation is outstanding"); Result.success(Unit) }
        store.consumeResult = Result.success(Unit)
        controller.refresh()
        assertEquals(listOf("retry", "retry"), store.consumed)
        assertTrue(controller.state.value.thanked)
        assertTrue(controller.state.value.unconfirmedProducts.isEmpty())
    }

    @Test fun cancellationAndUnknownProductsNeverShowSuccess() {
        val store = FakeStore(listOf(inr))
        val controller = SupportPurchases(store)
        controller.refresh()
        controller.buy(product) { Result.success(Unit) }
        store.onPurchases(Result.failure(IllegalStateException("Purchase cancelled")))
        assertFalse(controller.state.value.checkingOut)
        assertFalse(controller.state.value.thanked)
        store.onPurchases(Result.success(listOf(SupportPurchase("other", listOf("premium"), false))))
        assertTrue(store.consumed.isEmpty())
    }

    @Test fun checkoutCannotStartWhileRecoveryQueryIsOutstanding() {
        val store = FakeStore(listOf(inr))
        store.deferRecovery = true
        val controller = SupportPurchases(store)
        controller.refresh()
        assertTrue(controller.state.value.loading)
        controller.buy(product) { fail("Recovery must finish before checkout"); Result.success(Unit) }
        // A newer purchase event must win over an older recovery snapshot.
        store.onPurchases(Result.success(listOf(SupportPurchase("pending", listOf(product), true))))
        store.recoveryCallback!!(Result.success(emptyList()))
        assertTrue(controller.state.value.pendingProducts.contains(product))
        assertFalse(controller.state.value.loading)
    }

    @Test fun missingProductsCannotBePurchasedAndClosedControllerIgnoresCallbacks() {
        val store = FakeStore(emptyList())
        val controller = SupportPurchases(store)
        controller.refresh()
        controller.buy(product) { fail("Unavailable products must not launch"); Result.success(Unit) }
        controller.close()
        store.onPurchases(Result.success(listOf(SupportPurchase("late", listOf(product), false))))
        assertTrue(store.consumed.isEmpty())
    }

    private class FakeStore(var catalog: List<SupportOffer>) : SupportStore {
        override var onPurchases: (Result<List<SupportPurchase>>) -> Unit = {}
        var owned = emptyList<SupportPurchase>()
        var consumeResult = Result.success(Unit)
        var deferRecovery = false
        var recoveryCallback: ((Result<List<SupportPurchase>>) -> Unit)? = null
        val consumed = mutableListOf<String>()
        override fun connect(callback: (Result<Unit>) -> Unit) = callback(Result.success(Unit))
        override fun offers(callback: (Result<List<SupportOffer>>) -> Unit) = callback(Result.success(catalog))
        override fun purchases(callback: (Result<List<SupportPurchase>>) -> Unit) {
            if (deferRecovery) recoveryCallback = callback else callback(Result.success(owned))
        }
        override fun consume(token: String, callback: (Result<Unit>) -> Unit) {
            consumed.add(token)
            callback(consumeResult)
        }
        override fun close() = Unit
    }
}
