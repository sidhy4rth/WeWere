package com.rollapp.shared

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.rollapp.shared.data.remote.InviteCodes
import com.rollapp.shared.domain.repository.AuthRepository
import com.rollapp.shared.domain.repository.UserRepository
import com.rollapp.shared.ui.navigation.RollNavHost
import com.rollapp.shared.ui.navigation.Routes
import com.rollapp.shared.ui.theme.RollTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Decides the first screen and keeps the splash up until it knows. */
@HiltViewModel
class RootViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    val startDestination: StateFlow<String?> = authRepository.currentUser
        .map { user -> if (user == null) Routes.ONBOARDING else Routes.HOME }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The FCM token is stored against the signed-in user, not the install: two people
     * sharing a phone must not receive each other's group notifications.
     */
    fun registerPushToken() {
        viewModelScope.launch {
            runCatching {
                val token = FirebaseMessaging.getInstance().token.await()
                userRepository.registerDeviceToken(token)
            }
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val root: RootViewModel by viewModels()
    private val pendingInvite = MutableStateFlow<String?>(null)
    private val pendingGroup = MutableStateFlow<String?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Declining is fine — everything except push still works. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hold the splash until we know whether to open onboarding or the home feed,
        // so the user never sees a flash of the wrong screen.
        splashScreen.setKeepOnScreenCondition { root.startDestination.value == null }

        handleInviteIntent(intent)

        lifecycleScope.launch {
            root.startDestination.collect { destination ->
                if (destination == Routes.HOME) {
                    root.registerPushToken()
                    requestNotificationPermissionIfNeeded()
                }
            }
        }

        setContent {
            RollTheme {
                val start by root.startDestination.collectAsState()
                val invite by pendingInvite.collectAsState()
                val group by pendingGroup.collectAsState()

                start?.let { destination ->
                    RollNavHost(
                        startDestination = destination,
                        pendingInviteCode = invite,
                        onInviteConsumed = { pendingInvite.value = null },
                        pendingGroupId = group,
                        onGroupConsumed = { pendingGroup.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInviteIntent(intent)
    }

    /**
     * Two link shapes arrive here: an invite (`https://roll.app/join/XXXXXX` or
     * `roll://join/XXXXXX`) and a notification tap (`roll://group/{groupId}`).
     */
    private fun handleInviteIntent(intent: Intent?) {
        val data = intent?.data ?: return

        if (data.scheme == "roll" && data.host == "group") {
            data.pathSegments.firstOrNull()?.let { pendingGroup.value = it }
            return
        }
        InviteCodes.fromLink(data.toString())?.let { code -> pendingInvite.value = code }
    }

    /**
     * Asked once the user is signed in and has groups worth being notified about,
     * rather than at first launch where it has no context and gets denied.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
