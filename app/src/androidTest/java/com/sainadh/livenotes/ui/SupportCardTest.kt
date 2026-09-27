package com.sainadh.livenotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sainadh.livenotes.LiveNotesTheme
import com.sainadh.livenotes.billing.SupportOffer
import com.sainadh.livenotes.billing.SupportState
import com.sainadh.livenotes.billing.SupportTier
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupportCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun displaysPlayRupeePricesAndSelectsTheCorrectProduct() {
        // Fixture prices, not promised INR equivalents or configured live products.
        val prices = listOf("₹179.00", "₹349.00", "₹599.00", "₹899.00")
        val offers = SupportTier.entries.mapIndexed { index, tier ->
            SupportOffer(tier.productId, prices[index], "INR", 1)
        }
        var selected: String? = null
        compose.setContent {
            LiveNotesTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SupportCard(SupportState(offers = offers), { selected = it }, {})
                }
            }
        }
        SupportTier.entries.forEachIndexed { index, tier ->
            compose.onNodeWithText("${tier.title} · ${prices[index]}")
                .performScrollTo().assertIsEnabled().performClick()
            assertEquals(tier.productId, selected)
        }
        compose.onNodeWithText("$2.00", substring = true).assertDoesNotExist()
    }

    @Test fun unavailableAndPendingOptionsCannotStartCheckout() {
        val first = SupportTier.SMALL
        compose.setContent {
            LiveNotesTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SupportCard(SupportState(
                        offers = listOf(SupportOffer(first.productId, "$2.00", "USD", 2_000_000)),
                        pendingProducts = setOf(first.productId),
                    ), { error("Must not launch checkout") }, {})
                }
            }
        }
        compose.onNodeWithText("A little thanks · Payment pending").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("A coffee · Unavailable").performScrollTo().assertIsNotEnabled()
    }
}
