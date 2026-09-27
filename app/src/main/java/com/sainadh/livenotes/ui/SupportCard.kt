package com.sainadh.livenotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sainadh.livenotes.billing.SupportState
import com.sainadh.livenotes.billing.SupportTier

@Composable
internal fun SupportCard(state: SupportState, onBuy: (String) -> Unit, onRefresh: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Buy me a coffee", style = MaterialTheme.typography.titleLarge)
            Text("Enjoying Live Meeting Notes? An optional virtual coffee helps support its development. All app features stay free.")
            Text("One-time purchase. No subscription. Choose an amount below; Google Play handles payment.", style = MaterialTheme.typography.bodySmall)
            if (state.loading || state.processing) LinearProgressIndicator(Modifier.fillMaxWidth())
            SupportTier.entries.forEach { tier ->
                val offer = state.offers.singleOrNull { it.productId == tier.productId }
                val pending = tier.productId in state.pendingProducts
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = offer != null && !state.busy && state.unconfirmedProducts.isEmpty() && !pending,
                    onClick = { onBuy(tier.productId) },
                ) {
                    Text("${tier.title} · ${if (pending) "Payment pending" else offer?.price ?: "Unavailable"}")
                }
            }
            if (!state.loading && state.offers.isEmpty()) {
                Text("Support purchases aren't available right now. You can keep using the app for free.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Prices use your Google Play country and currency. You can support again whenever you wish.", style = MaterialTheme.typography.bodySmall)
            state.message?.let {
                Text(if (state.thanked) "☕ $it" else it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text("Refresh") }
        }
    }
}
