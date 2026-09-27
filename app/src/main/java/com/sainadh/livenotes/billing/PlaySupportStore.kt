package com.sainadh.livenotes.billing

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

class SupportViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PlaySupportStore(application)
    val purchases = SupportPurchases(store)
    fun buy(activity: Activity, productId: String) = purchases.buy(productId) { store.launch(activity, it) }
    override fun onCleared() = purchases.close()
}

private class PlaySupportStore(application: Application) : SupportStore {
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var connecting = false
    private val connectionCallbacks = mutableListOf<(Result<Unit>) -> Unit>()
    private val products = mutableMapOf<String, ProductDetails>()
    override var onPurchases: (Result<List<SupportPurchase>>) -> Unit = {}
    private val client = BillingClient.newBuilder(application)
        .setListener { result, purchases ->
            deliver {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    onPurchases(Result.success(purchases.orEmpty().mapNotNull(::purchase)))
                } else {
                    onPurchases(Result.failure(problem(result)))
                }
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override fun connect(callback: (Result<Unit>) -> Unit) {
        if (client.isReady) { callback(Result.success(Unit)); return }
        connectionCallbacks.add(callback)
        if (connecting) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) = deliver {
                connecting = false
                val callbacks = connectionCallbacks.toList()
                connectionCallbacks.clear()
                callbacks.forEach { it(outcome(result)) }
            }
            override fun onBillingServiceDisconnected() = deliver {
                connecting = false
                val callbacks = connectionCallbacks.toList()
                connectionCallbacks.clear()
                callbacks.forEach { it(Result.failure(IllegalStateException("Google Play disconnected. Tap Refresh to reconnect."))) }
            }
        })
    }

    override fun offers(callback: (Result<List<SupportOffer>>) -> Unit) {
        val params = QueryProductDetailsParams.newBuilder().setProductList(SupportTier.entries.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it.productId)
                .setProductType(BillingClient.ProductType.INAPP).build()
        }).build()
        client.queryProductDetailsAsync(params) { result, details -> deliver {
            products.clear()
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                callback(Result.failure(problem(result)))
            } else {
                val offers = details.productDetailsList.mapNotNull { product ->
                    val offer = baseOffer(product) ?: return@mapNotNull null
                    products[product.productId] = product
                    SupportOffer(product.productId, offer.formattedPrice, offer.priceCurrencyCode, offer.priceAmountMicros)
                }
                callback(Result.success(offers))
            }
        } }
    }

    // Console uses one buy option named "support" and no discounts/rentals/preorders.
    // Never pick an arbitrary offer whose displayed price could differ from checkout.
    private fun baseOffer(product: ProductDetails) = product.oneTimePurchaseOfferDetailsList
        ?.singleOrNull { it.purchaseOptionId == "support" && it.offerId == null }

    override fun purchases(callback: (Result<List<SupportPurchase>>) -> Unit) {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, list -> deliver {
            callback(if (result.responseCode == BillingClient.BillingResponseCode.OK) Result.success(list.mapNotNull(::purchase)) else Result.failure(problem(result)))
        } }
    }

    private fun purchase(value: Purchase): SupportPurchase? = when (value.purchaseState) {
        Purchase.PurchaseState.PENDING -> SupportPurchase(value.purchaseToken, value.products, true)
        Purchase.PurchaseState.PURCHASED -> SupportPurchase(value.purchaseToken, value.products, false)
        else -> null
    }

    override fun consume(token: String, callback: (Result<Unit>) -> Unit) {
        client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build()) { result, _ -> deliver {
            callback(outcome(result))
        } }
    }

    fun launch(activity: Activity, offer: SupportOffer): Result<Unit> {
        if (activity.isFinishing || activity.isDestroyed) return Result.failure(IllegalStateException("Reopen Settings to continue."))
        val product = products[offer.productId] ?: return Result.failure(IllegalStateException("Refresh prices and try again."))
        val details = baseOffer(product) ?: return Result.failure(IllegalStateException("This support option is unavailable."))
        val item = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product)
            .setOfferToken(details.offerToken).build()
        return outcome(client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(item)).build()))
    }

    private fun outcome(result: BillingResult): Result<Unit> = if (result.responseCode == BillingClient.BillingResponseCode.OK) Result.success(Unit) else Result.failure(problem(result))

    private fun problem(result: BillingResult) = IllegalStateException(when (result.responseCode) {
        BillingClient.BillingResponseCode.USER_CANCELED -> "Purchase cancelled. You can keep using every app feature."
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "An earlier payment needs confirmation. Tap Refresh before trying again."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "This support option is not available for your Google Play account."
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE, BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "Purchases are unavailable. Use the Google Play version with an eligible Play account."
        else -> "Google Play could not complete the request. Check your connection and tap Refresh."
    })

    private fun deliver(block: () -> Unit) {
        handler.post { if (!closed) block() }
    }

    override fun close() {
        closed = true
        connectionCallbacks.clear()
        handler.removeCallbacksAndMessages(null)
        client.endConnection()
    }
}
