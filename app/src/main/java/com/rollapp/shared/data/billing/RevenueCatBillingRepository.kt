package com.rollapp.shared.data.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.rollapp.shared.BuildConfig
import com.rollapp.shared.core.AppError
import com.rollapp.shared.core.Outcome
import com.rollapp.shared.di.ApplicationScope
import com.rollapp.shared.domain.model.DevelopOffer
import com.rollapp.shared.domain.model.DevelopOffers
import com.rollapp.shared.domain.model.DevelopPlan
import com.rollapp.shared.domain.model.INVITE_HOST
import com.rollapp.shared.domain.repository.BillingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * RevenueCat behind [BillingRepository].
 *
 * The RevenueCat app user id is always the Firebase uid. That is the whole trick
 * that lets the server trust a purchase: it verifies the caller's Firebase token,
 * then asks RevenueCat what *that uid* has bought.
 */
@Singleton
class RevenueCatBillingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val client: OkHttpClient,
    @ApplicationScope private val appScope: CoroutineScope
) : BillingRepository {

    private val apiKey = BuildConfig.REVENUECAT_API_KEY

    // The SDK deliberately crashes on a Test Store key unless the app is debuggable,
    // so this applies exactly the SDK's own test — never BuildConfig.DEBUG, which a
    // demo release build (debuggable, see app/build.gradle.kts) would get wrong.
    override val isAvailable: Boolean =
        apiKey.isNotBlank() && (!apiKey.startsWith(TEST_KEY_PREFIX) || context.isDebuggable())

    override val isDemo: Boolean = isAvailable && apiKey.startsWith(TEST_KEY_PREFIX)

    private val gold = MutableStateFlow(false)
    override val hasGold: StateFlow<Boolean> = gold.asStateFlow()

    override fun start() {
        if (!isAvailable) return
        if (BuildConfig.DEBUG) Purchases.logLevel = LogLevel.DEBUG

        Purchases.configure(
            PurchasesConfiguration.Builder(context, apiKey)
                .appUserID(auth.currentUser?.uid)
                .build()
        )
        Purchases.sharedInstance.updatedCustomerInfoListener =
            UpdatedCustomerInfoListener { gold.value = it.hasGold() }

        // Follow the Firebase account: whoever signs in is who RevenueCat sees.
        auth.addAuthStateListener { firebase ->
            val uid = firebase.currentUser?.uid ?: return@addAuthStateListener
            appScope.launch {
                runCatching { identify(uid) }
                    .onFailure { Log.w(TAG, "RevenueCat logIn failed", it) }
            }
        }
    }

    override suspend fun loadOffers(): Outcome<DevelopOffers> = billingCall {
        purchases().awaitOfferings().toDevelopOffers()
    }

    override suspend fun purchase(activity: Activity, plan: DevelopPlan): Outcome<Boolean> =
        billingCall {
            val purchases = purchases()
            val pkg = purchases.awaitOfferings().packageFor(plan)
                ?: throw BillingUnavailable("That plan isn't on sale right now")
            try {
                val result = purchases.awaitPurchase(PurchaseParams.Builder(activity, pkg).build())
                gold.value = result.customerInfo.hasGold()
                true
            } catch (e: PurchasesTransactionException) {
                if (e.userCancelled) false else throw e
            }
        }

    override suspend fun restore(): Outcome<Boolean> = billingCall {
        purchases().awaitRestore().hasGold().also { gold.value = it }
    }

    override suspend fun developRoll(groupId: String): Outcome<Boolean> = withContext(Dispatchers.IO) {
        try {
            val user = auth.currentUser ?: return@withContext Outcome.Failure(AppError.NotAuthenticated)
            val token = user.getIdToken(false).await().token
                ?: return@withContext Outcome.Failure(AppError.NotAuthenticated)

            val request = Request.Builder()
                .url(DEVELOP_ENDPOINT)
                .header("Authorization", "Bearer $token")
                .post("""{"groupId":"$groupId"}""".toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) return@withContext Outcome.Success(true)
                if (response.code == 402) return@withContext Outcome.Success(false)
                val message = response.body?.string()?.let(::serverMessage)
                Outcome.Failure(
                    when (response.code) {
                        401 -> AppError.NotAuthenticated
                        403 -> AppError.PermissionDenied
                        404 -> AppError.GroupNotFound
                        else -> AppError.Validation(message ?: "Couldn't make the roll Exclusive")
                    }
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: IOException) {
            Outcome.Failure(AppError.Offline)
        } catch (t: Throwable) {
            Log.w(TAG, "developRoll failed", t)
            Outcome.Failure(AppError.Unknown(t.message))
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun purchases(): Purchases {
        if (!isAvailable) throw BillingUnavailable("Purchases aren't available in this build")
        val uid = auth.currentUser?.uid ?: throw BillingUnavailable("Sign in first")
        identify(uid)
        return Purchases.sharedInstance
    }

    /** Makes sure RevenueCat is looking at [uid], and refreshes the Gold flag. */
    private suspend fun identify(uid: String) {
        val purchases = Purchases.sharedInstance
        val info = if (purchases.appUserID == uid) purchases.awaitCustomerInfo()
        else purchases.awaitLogIn(uid).customerInfo
        gold.value = info.hasGold()
    }

    /** Same rule as the server: a single-roll purchase never counts as Gold. */
    private fun CustomerInfo.hasGold() = entitlements[GOLD_ENTITLEMENT]
        ?.let { it.isActive && it.productIdentifier.substringBefore(':') != SINGLE_ROLL_PRODUCT } == true

    private fun Offerings.toDevelopOffers() = DevelopOffers(
        singleRoll = packageFor(DevelopPlan.SINGLE_ROLL)
            ?.let { DevelopOffer(DevelopPlan.SINGLE_ROLL, it.product.price.formatted) },
        gold = packageFor(DevelopPlan.GOLD)
            ?.let { DevelopOffer(DevelopPlan.GOLD, it.product.price.formatted) }
    )

    /**
     * Found by product id rather than by package slot, so the dashboard's offering
     * layout can change without an app update. Google suffixes subscription ids with
     * the base plan (`gold_monthly:monthly`), hence the prefix match.
     */
    private fun Offerings.packageFor(plan: DevelopPlan): Package? {
        val productId = when (plan) {
            DevelopPlan.SINGLE_ROLL -> SINGLE_ROLL_PRODUCT
            DevelopPlan.GOLD -> GOLD_PRODUCT
        }
        val packages = (listOfNotNull(current) + all.values).flatMap { it.availablePackages }
        return packages.firstOrNull { it.product.id.substringBefore(':') == productId }
    }

    private suspend inline fun <T> billingCall(crossinline block: suspend () -> T): Outcome<T> =
        try {
            Outcome.Success(block())
        } catch (c: CancellationException) {
            throw c
        } catch (e: BillingUnavailable) {
            Outcome.Failure(AppError.Validation(e.message))
        } catch (e: PurchasesTransactionException) {
            Outcome.Failure(AppError.Validation(e.error.message))
        } catch (e: PurchasesException) {
            Log.w(TAG, "RevenueCat call failed: ${e.error}", e)
            Outcome.Failure(AppError.Validation(e.error.message))
        }

    private fun serverMessage(body: String): String? = runCatching {
        Json.parseToJsonElement(body).let { it as JsonObject }["message"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun Context.isDebuggable() =
        applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    private class BillingUnavailable(message: String) : Exception(message)

    companion object {
        private const val TAG = "Billing"
        private const val TEST_KEY_PREFIX = "test_"

        /** Must match the dashboard and `site/api/develop.js`. */
        const val GOLD_ENTITLEMENT = "gold"
        const val SINGLE_ROLL_PRODUCT = "develop_roll"
        const val GOLD_PRODUCT = "gold_monthly"

        private const val DEVELOP_ENDPOINT = "https://$INVITE_HOST/api/develop"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
