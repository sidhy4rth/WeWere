package com.rollapp.shared.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.layout.Column
import com.rollapp.shared.ui.components.Hairline
import com.rollapp.shared.ui.theme.Gold
import com.rollapp.shared.ui.theme.Ink
import com.rollapp.shared.ui.theme.Muted
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rollapp.shared.ui.activity.ActivityScreen
import com.rollapp.shared.ui.auth.AuthScreen
import androidx.hilt.navigation.compose.hiltViewModel
import com.rollapp.shared.ui.camera.CameraScreen
import com.rollapp.shared.ui.camera.CameraViewModel
import com.rollapp.shared.ui.camera.ReviewScreen
import com.rollapp.shared.ui.carousel.CarouselScreen
import com.rollapp.shared.ui.creategroup.CreateGroupScreen
import com.rollapp.shared.ui.group.GroupScreen
import com.rollapp.shared.ui.group.GroupSettingsScreen
import com.rollapp.shared.ui.home.HomeScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rollapp.shared.ui.joingroup.JoinGroupScreen
import com.rollapp.shared.ui.joingroup.JoinGroupViewModel
import com.rollapp.shared.ui.joingroup.ScanQrScreen
import com.rollapp.shared.ui.members.MembersScreen
import com.rollapp.shared.ui.onboarding.OnboardingScreen
import com.rollapp.shared.ui.profile.ProfileScreen

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(Routes.HOME, "Home", Icons.Rounded.Home),
    TabItem(Routes.CAMERA_TAB, "Camera", Icons.Rounded.PhotoCamera),
    TabItem(Routes.ACTIVITY, "Activity", Icons.Rounded.Notifications),
    TabItem(Routes.PROFILE, "You", Icons.Rounded.Person)
)

/** Destinations that own the whole screen — no bottom bar over a full-bleed photo. */
private val fullScreenRoutes = setOf(
    Routes.AUTH, Routes.ONBOARDING, Routes.CAROUSEL, Routes.CAMERA, Routes.REVIEW
)

@Composable
fun RollNavHost(
    startDestination: String,
    pendingInviteCode: String?,
    onInviteConsumed: () -> Unit,
    pendingGroupId: String? = null,
    onGroupConsumed: () -> Unit = {},
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute != null &&
        fullScreenRoutes.none { currentRoute.startsWith(it.substringBefore("/{").substringBefore("?")) } &&
        tabs.any { currentRoute == it.route } 

    // An invite arriving from a link while the app is already open.
    androidx.compose.runtime.LaunchedEffect(pendingInviteCode) {
        pendingInviteCode?.let { code ->
            navController.navigate(Routes.joinGroup(code))
            onInviteConsumed()
        }
    }

    // A tapped notification, which names the group it came from.
    androidx.compose.runtime.LaunchedEffect(pendingGroupId) {
        pendingGroupId?.let { groupId ->
            navController.navigate(Routes.group(groupId))
            onGroupConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    Hairline()
                    NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy?.any {
                            it.route == tab.route
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    // Single instance per tab, state preserved across switches.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label.uppercase()) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Gold,
                                selectedTextColor = Gold,
                                unselectedIconColor = Muted,
                                unselectedTextColor = Muted,
                                indicatorColor = Gold.copy(alpha = 0.12f)
                            )
                        )
                    }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(if (showBottomBar) padding else androidx.compose.foundation.layout.PaddingValues(0.dp))
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Routes.AUTH) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.AUTH) {
                AuthScreen(
                    onSignedIn = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.HOME) {
                HomeScreen(
                    onOpenGroup = { navController.navigate(Routes.group(it)) },
                    onCreateGroup = { navController.navigate(Routes.CREATE_GROUP) },
                    onJoinGroup = { navController.navigate(Routes.joinGroup()) },
                    onOpenProfile = { navController.navigate(Routes.PROFILE) }
                )
            }

            // The camera tab needs a group to publish into, so it routes through the
            // group list rather than opening a camera with nowhere to send the photo.
            composable(Routes.CAMERA_TAB) {
                HomeScreen(
                    onOpenGroup = { navController.navigate(Routes.camera(it)) },
                    onCreateGroup = { navController.navigate(Routes.CREATE_GROUP) },
                    onJoinGroup = { navController.navigate(Routes.joinGroup()) },
                    onOpenProfile = { navController.navigate(Routes.PROFILE) }
                )
            }

            composable(Routes.ACTIVITY) {
                ActivityScreen(onOpenGroup = { navController.navigate(Routes.group(it)) })
            }

            composable(Routes.PROFILE) {
                ProfileScreen(
                    onSignedOut = {
                        navController.navigate(Routes.AUTH) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.CREATE_GROUP) {
                CreateGroupScreen(
                    onBack = navController::popBackStack,
                    onGroupReady = { groupId ->
                        navController.navigate(Routes.group(groupId)) {
                            popUpTo(Routes.CREATE_GROUP) { inclusive = true }
                        }
                    }
                )
            }

            composable(
                route = Routes.JOIN_GROUP,
                arguments = listOf(
                    navArgument(NavArgs.CODE) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) {
                val joinViewModel: JoinGroupViewModel = hiltViewModel(it)

                // The scanner pops back with the code in its own saved state, which
                // survives the scanner screen being destroyed on the way out.
                val scanned = it.savedStateHandle
                    .getStateFlow<String?>(NavArgs.CODE, null)
                    .collectAsStateWithLifecycle()

                androidx.compose.runtime.LaunchedEffect(scanned.value) {
                    scanned.value?.let { code ->
                        joinViewModel.onCodeScanned(code)
                        it.savedStateHandle[NavArgs.CODE] = null
                    }
                }

                JoinGroupScreen(
                    onBack = navController::popBackStack,
                    onScanQr = { navController.navigate(Routes.SCAN_QR) },
                    onJoined = { groupId ->
                        navController.navigate(Routes.group(groupId)) {
                            popUpTo(Routes.HOME)
                        }
                    },
                    viewModel = joinViewModel
                )
            }

            composable(Routes.SCAN_QR) {
                ScanQrScreen(
                    onClose = navController::popBackStack,
                    onCodeScanned = { code ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle?.set(NavArgs.CODE, code)
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Routes.GROUP,
                arguments = listOf(navArgument(NavArgs.GROUP_ID) { type = NavType.StringType })
            ) {
                GroupScreen(
                    onBack = navController::popBackStack,
                    onOpenCamera = { navController.navigate(Routes.camera(it)) },
                    onOpenPhoto = { groupId, photoId, filter ->
                        navController.navigate(Routes.carousel(groupId, photoId, filter))
                    },
                    onOpenSlideshow = { groupId, filter ->
                        navController.navigate(Routes.slideshow(groupId, filter))
                    },
                    onOpenSettings = { navController.navigate(Routes.groupSettings(it)) },
                    onOpenMembers = { navController.navigate(Routes.members(it)) }
                )
            }

            composable(
                route = Routes.GROUP_SETTINGS,
                arguments = listOf(navArgument(NavArgs.GROUP_ID) { type = NavType.StringType })
            ) {
                GroupSettingsScreen(
                    onBack = navController::popBackStack,
                    onOpenMembers = { navController.navigate(Routes.members(it)) },
                    onLeftGroup = {
                        navController.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                    }
                )
            }

            composable(
                route = Routes.MEMBERS,
                arguments = listOf(navArgument(NavArgs.GROUP_ID) { type = NavType.StringType })
            ) {
                MembersScreen(onBack = navController::popBackStack)
            }

            composable(
                route = Routes.CAROUSEL,
                arguments = listOf(
                    navArgument(NavArgs.GROUP_ID) { type = NavType.StringType },
                    navArgument(NavArgs.PHOTO_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(NavArgs.FILTER) {
                        type = NavType.StringType
                        defaultValue = "all"
                    },
                    navArgument(NavArgs.SLIDESHOW) {
                        type = NavType.StringType
                        defaultValue = "false"
                    }
                ),
                // The viewer rises over the grid; a horizontal push would fight the
                // pager's own gesture.
                enterTransition = { fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 12 } },
                exitTransition = { fadeOut(tween(160)) },
                popExitTransition = { fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 12 } }
            ) {
                CarouselScreen(onClose = navController::popBackStack)
            }

            composable(
                route = Routes.CAMERA,
                arguments = listOf(navArgument(NavArgs.GROUP_ID) { type = NavType.StringType })
            ) { entry ->
                val groupId = entry.arguments?.getString(NavArgs.GROUP_ID).orEmpty()
                val cameraViewModel: CameraViewModel = hiltViewModel()
                CameraScreen(
                    onClose = navController::popBackStack,
                    onPhotoCaptured = { uri, capturedAt ->
                        navController.navigate(Routes.review(groupId, uri.toString(), capturedAt))
                    },
                    onPickFromGallery = { uris ->
                        // Queue what they picked, then show them the group receiving it.
                        cameraViewModel.uploadFromGallery(uris)
                        navController.navigate(Routes.group(groupId)) {
                            popUpTo(Routes.CAMERA) { inclusive = true }
                        }
                    }
                )
            }

            composable(
                route = Routes.REVIEW,
                arguments = listOf(
                    navArgument(NavArgs.GROUP_ID) { type = NavType.StringType },
                    navArgument(NavArgs.URI) { type = NavType.StringType },
                    navArgument(NavArgs.CAPTURED_AT) {
                        type = NavType.StringType
                        defaultValue = "0"
                    }
                )
            ) { entry ->
                val groupId = entry.arguments?.getString(NavArgs.GROUP_ID).orEmpty()
                ReviewScreen(
                    onRetake = navController::popBackStack,
                    onShared = {
                        // Land back on the group so the queued photo is visible at once.
                        navController.navigate(Routes.group(groupId)) {
                            popUpTo(Routes.GROUP) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}
