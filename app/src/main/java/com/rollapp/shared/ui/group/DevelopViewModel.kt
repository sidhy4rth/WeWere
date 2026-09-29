package com.rollapp.shared.ui.group

import android.app.Activity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.domain.model.DevelopOffers
import com.rollapp.shared.domain.model.DevelopPlan
import com.rollapp.shared.domain.repository.BillingRepository
import com.rollapp.shared.ui.navigation.NavArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DevelopUiState(
    val isAvailable: Boolean = false,
    val offers: DevelopOffers = DevelopOffers(),
    val loadingOffers: Boolean = false,
    val hasGold: Boolean = false,
    val selected: DevelopPlan = DevelopPlan.SINGLE_ROLL,
    /** Which step is running: the store sheet, or the server developing the roll. */
    val step: DevelopStep? = null,
    val error: AppError? = null
) {
    val isBusy: Boolean get() = step != null
}

enum class DevelopStep { PURCHASING, DEVELOPING, RESTORING }

/**
 * The Develop sheet: pick a plan, pay, and have the server develop the roll.
 *
 * Shares the group screen's back-stack entry, so [NavArgs.GROUP_ID] is the roll the
 * sheet was opened from. The group listener on that screen is what notices the roll
 * turning developed — this ViewModel never flips any local "developed" flag itself.
 */
@HiltViewModel
class DevelopViewModel @Inject constructor(
    private val billing: BillingRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val groupId: String = checkNotNull(savedStateHandle[NavArgs.GROUP_ID])
    private val local = MutableStateFlow(DevelopUiState(isAvailable = billing.isAvailable))

    val state: StateFlow<DevelopUiState> = combine(local, billing.hasGold) { s, gold ->
        s.copy(hasGold = gold)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    /** Called when the sheet opens. Prices come from the store, never hard-coded. */
    fun loadOffers() {
        if (!billing.isAvailable || local.value.loadingOffers) return
        viewModelScope.launch {
            local.update { it.copy(loadingOffers = true, error = null) }
            when (val outcome = billing.loadOffers()) {
                is Outcome.Success -> local.update {
                    it.copy(
                        offers = outcome.data,
                        loadingOffers = false,
                        // Default to whichever plan the store actually has on offer.
                        selected = if (outcome.data.singleRoll == null && outcome.data.gold != null)
                            DevelopPlan.GOLD else it.selected
                    )
                }
                is Outcome.Failure -> local.update { it.copy(loadingOffers = false, error = outcome.error) }
            }
        }
    }

    fun select(plan: DevelopPlan) = local.update { it.copy(selected = plan, error = null) }

    /**
     * Develops the roll, paying only if there is nothing already paid for.
     *
     * The server is asked first: an active Gold subscription, or a single-roll
     * purchase whose develop call never landed (app killed, network dropped), is
     * spent before the store is ever opened — nobody pays twice for one roll.
     */
    fun develop(activity: Activity) {
        if (local.value.isBusy) return
        val plan = local.value.selected
        viewModelScope.launch {
            local.update { it.copy(step = DevelopStep.DEVELOPING, error = null) }
            when (val first = billing.developRoll(groupId)) {
                is Outcome.Failure -> return@launch fail(first.error)
                is Outcome.Success -> if (first.data) return@launch done()
            }

            local.update { it.copy(step = DevelopStep.PURCHASING) }
            when (val bought = billing.purchase(activity, plan)) {
                is Outcome.Failure -> return@launch fail(bought.error)
                is Outcome.Success -> if (!bought.data) return@launch done() // backed out
            }

            local.update { it.copy(step = DevelopStep.DEVELOPING) }
            when (val second = billing.developRoll(groupId)) {
                is Outcome.Failure -> fail(second.error)
                is Outcome.Success -> if (second.data) done() else fail(
                    // The store took the payment but RevenueCat hasn't told the server
                    // yet. The purchase is safe; the next tap will find it.
                    AppError.Validation("Payment received — give it a moment, then tap Develop again")
                )
            }
        }
    }

    fun restore() {
        if (local.value.isBusy) return
        viewModelScope.launch {
            local.update { it.copy(step = DevelopStep.RESTORING, error = null) }
            when (val restored = billing.restore()) {
                is Outcome.Success -> local.update {
                    it.copy(
                        step = null,
                        selected = if (restored.data) DevelopPlan.GOLD else it.selected,
                        error = if (restored.data) null
                        else AppError.Validation("No Gold subscription on this account")
                    )
                }
                is Outcome.Failure -> fail(restored.error)
            }
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }

    private fun done() = local.update { it.copy(step = null) }

    private fun fail(error: AppError) = local.update { it.copy(step = null, error = error) }
}
