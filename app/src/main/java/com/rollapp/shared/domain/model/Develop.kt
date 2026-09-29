package com.rollapp.shared.domain.model

/**
 * The two ways to develop a roll.
 *
 * [SINGLE_ROLL] is a one-time purchase that develops the roll it was bought from.
 * [GOLD] is a monthly subscription: every roll its holder develops while it is
 * active costs nothing more.
 */
enum class DevelopPlan { SINGLE_ROLL, GOLD }

/** A plan as the store prices it, in the buyer's currency. */
data class DevelopOffer(
    val plan: DevelopPlan,
    val price: String
)

data class DevelopOffers(
    val singleRoll: DevelopOffer? = null,
    val gold: DevelopOffer? = null
) {
    val isEmpty: Boolean get() = singleRoll == null && gold == null
}
