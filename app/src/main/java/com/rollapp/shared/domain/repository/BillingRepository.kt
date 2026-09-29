package com.rollapp.shared.domain.repository

import android.app.Activity
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.DevelopOffers
import com.rollapp.shared.domain.model.DevelopPlan
import kotlinx.coroutines.flow.StateFlow

/**
 * Purchases, and turning a purchase into a developed roll.
 *
 * Buying and developing are separate steps on purpose. The store purchase belongs
 * to a person; a developed roll belongs to a group. The server is what joins the
 * two — it checks the buyer's purchases with RevenueCat and only then marks the
 * roll, so a client can never develop a roll on its own say-so.
 */
interface BillingRepository {
    /** False when this build has no usable store key; the UI says so instead of failing. */
    val isAvailable: Boolean

    /** Whether the signed-in user holds an active Gold subscription. */
    val hasGold: StateFlow<Boolean>

    /** Configures the SDK. Call once, from Application.onCreate, on the main thread. */
    fun start()

    suspend fun loadOffers(): Outcome<DevelopOffers>

    /** Runs the store's purchase sheet. Success(false) means the user backed out. */
    suspend fun purchase(activity: Activity, plan: DevelopPlan): Outcome<Boolean>

    /** Restores past purchases, returning whether Gold is active afterwards. */
    suspend fun restore(): Outcome<Boolean>

    /**
     * Asks the server to develop [groupId] against this account's purchases.
     * Success(false) means the account has nothing to spend on it — no active Gold
     * and no unspent single-roll purchase — which is the cue to open the store.
     */
    suspend fun developRoll(groupId: String): Outcome<Boolean>
}
