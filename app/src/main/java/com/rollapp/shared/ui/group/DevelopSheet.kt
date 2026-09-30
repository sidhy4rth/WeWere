package com.rollapp.shared.ui.group

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rollapp.shared.core.Limits
import com.rollapp.shared.domain.model.DevelopOffer
import com.rollapp.shared.domain.model.DevelopPlan
import com.rollapp.shared.domain.model.Group
import com.rollapp.shared.ui.components.DevelopingImage
import com.rollapp.shared.ui.components.GoldButton
import com.rollapp.shared.ui.components.Hairline
import com.rollapp.shared.ui.components.QuietButton
import com.rollapp.shared.ui.components.Readout
import com.rollapp.shared.ui.components.RiseIn
import com.rollapp.shared.ui.components.Settle
import com.rollapp.shared.ui.theme.Error
import com.rollapp.shared.ui.theme.Gold
import com.rollapp.shared.ui.theme.GoldBrush
import com.rollapp.shared.ui.theme.Ink
import com.rollapp.shared.ui.theme.Ivory
import com.rollapp.shared.ui.theme.IvoryMuted
import com.rollapp.shared.ui.theme.Muted
import com.rollapp.shared.ui.theme.OnGold
import com.rollapp.shared.ui.theme.Raised
import com.rollapp.shared.ui.theme.Surface
import kotlinx.coroutines.delay

/**
 * "Go Exclusive" — the one place WeWere asks for money. (Internally a paid roll is
 * still "developed": the Firestore field, the product id and the endpoint keep it.)
 *
 * The pitch is the film metaphor the app already speaks: a free roll is a roll of
 * 200 exposures; developing it finishes it properly, for everyone in it. One person
 * pays, the whole group benefits — which is also why a guest who sees a developed
 * roll has a reason to develop their own.
 */
@Composable
fun DevelopSheet(
    group: Group,
    onDismiss: () -> Unit,
    viewModel: DevelopViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activity = LocalContext.current.findActivity()

    LaunchedEffect(Unit) { viewModel.loadOffers() }

    ModalBottomSheet(
        onDismissRequest = { if (!state.isBusy) onDismiss() },
        sheetState = sheetState,
        containerColor = Surface,
        scrimColor = Ink.copy(alpha = 0.7f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(Gold.copy(alpha = 0.4f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            RiseIn {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Readout(
                        text = when {
                            group.isEarlyRoll -> "Early roll · no photo limit"
                            group.isFull -> "Roll full · ${group.photoCount} photos"
                            else -> "${group.exposuresLeft} photos left"
                        },
                        color = Gold
                    )
                    Text(
                        text = if (group.isFull) "This roll is full." else "Go Exclusive.",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Ivory
                    )
                    Text(
                        text = if (group.isEarlyRoll) {
                            "“${group.name}” is an early roll, so it already has no photo limit. " +
                                "Make it Exclusive for full quality and a whole-roll save — for everyone in it."
                        } else {
                            "Free rolls hold ${group.exposureLimit ?: Limits.FREE_ROLL_PHOTO_LIMIT} photos. Make " +
                                "“${group.name}” Exclusive once and everyone in it gets the upgrade."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = IvoryMuted
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            RiseIn(delayMillis = 80) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!group.isEarlyRoll) {
                        Perk(Icons.Rounded.AllInclusive, "Unlimited photos", "Keep adding — no ceiling on the roll")
                    }
                    Perk(Icons.Rounded.HighQuality, "Near-original quality", "Up to 12 MP per photo, not 4")
                    Perk(Icons.Rounded.Download, "Save the whole roll", "Anyone in it can keep every photo, in one tap")
                    Perk(Icons.Rounded.Movie, "Weekly montage", "Everyone's 5-second moments, cut into one video")
                    Perk(Icons.Rounded.AutoAwesome, "The Exclusive seal", "Everyone sees who made it Exclusive")
                }
            }

            Spacer(Modifier.height(22.dp))
            Hairline()
            Spacer(Modifier.height(22.dp))

            if (!state.isAvailable) {
                Text(
                    "Purchases aren't set up in this build.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                RiseIn(delayMillis = 140) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlanCard(
                            title = "This roll",
                            offer = state.offers.singleRoll,
                            cadence = "one-time",
                            note = "Makes “${group.name}” Exclusive",
                            selected = state.selected == DevelopPlan.SINGLE_ROLL,
                            badge = null,
                            onClick = { viewModel.select(DevelopPlan.SINGLE_ROLL) },
                            modifier = Modifier.weight(1f)
                        )
                        PlanCard(
                            title = "WeWere Gold",
                            offer = state.offers.gold,
                            cadence = "a month",
                            note = "Every roll you make Exclusive",
                            selected = state.selected == DevelopPlan.GOLD,
                            badge = if (state.hasGold) "Active" else "Best value",
                            onClick = { viewModel.select(DevelopPlan.GOLD) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                val selectedOffer = when (state.selected) {
                    DevelopPlan.SINGLE_ROLL -> state.offers.singleRoll
                    DevelopPlan.GOLD -> state.offers.gold
                }
                GoldButton(
                    text = when {
                        state.selected == DevelopPlan.GOLD && state.hasGold -> "Go Exclusive with Gold"
                        selectedOffer == null -> "Go Exclusive"
                        state.selected == DevelopPlan.GOLD -> "Start Gold · ${selectedOffer.price}"
                        else -> "Go Exclusive · ${selectedOffer.price}"
                    },
                    onClick = { activity?.let(viewModel::develop) },
                    enabled = activity != null && (selectedOffer != null || state.hasGold),
                    loading = state.isBusy || state.loadingOffers
                )

                Spacer(Modifier.height(10.dp))

                val status = when (state.step) {
                    DevelopStep.PURCHASING -> "Waiting for the store…"
                    DevelopStep.DEVELOPING -> "Making it Exclusive…"
                    DevelopStep.RESTORING -> "Restoring purchases…"
                    null -> null
                }
                (state.error?.message ?: status)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.error != null) Error else Gold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                QuietButton(
                    text = "Restore purchases",
                    onClick = viewModel::restore,
                    enabled = !state.isBusy
                )
            }

            Spacer(Modifier.height(4.dp))
            if (state.isDemo) {
                // Honest about the demo: nobody should think they were charged.
                Readout(
                    text = "Demo store · purchases are simulated · no real charge",
                    color = Gold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
            }
            Readout(
                text = "One person pays · everyone in the roll gets it",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Perk(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .border(1.dp, Gold.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ivory)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun PlanCard(
    title: String,
    offer: DevelopOffer?,
    cadence: String,
    note: String,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Raised else Color.Transparent)
            .border(if (selected) 1.5.dp else 1.dp, Gold.copy(alpha = if (selected) 0.9f else 0.3f), shape)
            .clickable(enabled = offer != null, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ivory, modifier = Modifier.weight(1f))
        }
        if (badge != null) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(GoldBrush)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(badge.uppercase(), style = MaterialTheme.typography.labelSmall, color = OnGold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = offer?.price ?: "—",
            style = MaterialTheme.typography.headlineMedium,
            color = if (selected) Gold else Ivory
        )
        Readout(text = cadence)
        Spacer(Modifier.height(2.dp))
        Text(note, style = MaterialTheme.typography.bodySmall, color = IvoryMuted, maxLines = 2)
    }
}

/**
 * The strip under a roll's title. A free roll counts its exposures down and offers
 * to develop; a developed roll wears its seal and offers the whole-roll save.
 */
@Composable
fun DevelopBanner(
    group: Group,
    developerName: String?,
    onDevelop: () -> Unit,
    onSaveRoll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    if (group.developed) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Brush.horizontalGradient(0f to Gold.copy(alpha = 0.16f), 1f to Color.Transparent))
                .border(1.dp, Gold.copy(alpha = 0.5f), shape)
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Gold, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Readout("Exclusive", color = Gold)
                Text(
                    text = developerName?.let { "by $it · unlimited, full quality" } ?: "Unlimited, full quality",
                    style = MaterialTheme.typography.bodySmall,
                    color = IvoryMuted,
                    maxLines = 1
                )
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onSaveRoll)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = Gold, modifier = Modifier.size(16.dp))
                Text("Save roll", style = MaterialTheme.typography.titleSmall, color = Gold)
            }
        }
    } else if (group.isEarlyRoll) {
        // No counter to show: the pitch is quality and the whole-roll save.
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, Gold.copy(alpha = 0.25f), shape)
                .clickable(onClick = onDevelop)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Readout("Early roll · no photo limit", modifier = Modifier.weight(1f))
            Text("Go Exclusive →", style = MaterialTheme.typography.titleSmall, color = Gold)
        }
    } else {
        val limit = group.exposureLimit ?: Limits.FREE_ROLL_PHOTO_LIMIT
        val left = group.exposuresLeft ?: 0
        val used = (limit - left).toFloat() / limit
        val urgent = left <= LOW_EXPOSURES
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, Gold.copy(alpha = if (urgent) 0.7f else 0.25f), shape)
                .clickable(onClick = onDevelop)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Readout(
                    text = when {
                        left == 0 -> "Roll full"
                        else -> "$left of $limit photos left"
                    },
                    color = if (urgent) Gold else Muted,
                    modifier = Modifier.weight(1f)
                )
                Text("Go Exclusive →", style = MaterialTheme.typography.titleSmall, color = Gold)
            }
            // The film counter: a hairline that fills with gold as the roll is used.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(Gold.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(used.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(GoldBrush)
                )
            }
        }
    }
}

/**
 * The moment a roll turns developed: its newest photo comes up in the tray, then
 * the word. Plays for everyone who has the roll open, not only the buyer — the
 * group sees the upgrade land together. Tap anywhere, or wait, to dismiss.
 */
@Composable
fun DevelopedReveal(
    visible: Boolean,
    photoUrl: String?,
    developerName: String?,
    onDone: () -> Unit
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(400)), exit = fadeOut(tween(500))) {
        val lift = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            lift.animateTo(1f, tween(1400, easing = Settle))
            delay(REVEAL_HOLD_MILLIS)
            onDone()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Ink.copy(alpha = 0.94f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDone
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // An ivory-bordered print, tilted like one just lifted from the tray.
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .graphicsLayer {
                            rotationZ = -4f + 2f * lift.value
                            translationY = (1f - lift.value) * 40.dp.toPx()
                        }
                        .background(Ivory)
                        .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 34.dp)
                ) {
                    if (photoUrl != null) {
                        DevelopingImage(
                            model = photoUrl,
                            contentDescription = null,
                            durationMillis = 2000,
                            delayMillis = 300,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.8f)
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).background(GoldBrush))
                    }
                }
                Spacer(Modifier.height(36.dp))
                RiseIn(delayMillis = 900) {
                    Text("Exclusive.", style = MaterialTheme.typography.displayLarge, color = Gold)
                }
                Spacer(Modifier.height(10.dp))
                RiseIn(delayMillis = 1200) {
                    Readout(
                        text = developerName?.let { "Thanks to $it · unlimited · full quality" }
                            ?: "Unlimited · full quality · save the roll",
                        color = IvoryMuted
                    )
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Below this many exposures the banner turns gold to catch the eye. */
private const val LOW_EXPOSURES = 20
private const val REVEAL_HOLD_MILLIS = 2600L
