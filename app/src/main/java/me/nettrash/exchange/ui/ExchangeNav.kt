/*
 * ExchangeNav.kt
 * Exchange (Android)
 *
 * Top-level NavHost. Mirrors the iOS sheet topology — Compose / Decrypt
 * / AddRecipient / MyIdentityQR / Settings sub-sheets become full-screen
 * Compose destinations launched from the home screen's TopAppBar.
 *
 * Routes are simple strings — no need for type-safe routes at this
 * scale, and it keeps the back stack URL-debuggable.
 */

package me.nettrash.exchange.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import me.nettrash.exchange.ui.screens.AddRecipientScreen
import me.nettrash.exchange.ui.screens.ComposeScreen
import me.nettrash.exchange.ui.screens.DecryptFileScreen
import me.nettrash.exchange.ui.screens.DecryptScreen
import me.nettrash.exchange.ui.screens.EncryptFileScreen
import me.nettrash.exchange.ui.screens.ExportIdentityScreen
import me.nettrash.exchange.ui.screens.HomeScreen
import me.nettrash.exchange.ui.screens.ImportIdentityScreen
import me.nettrash.exchange.ui.screens.LockScreen
import me.nettrash.exchange.ui.screens.MyIdentityQrScreen
import me.nettrash.exchange.ui.screens.ScanIdentityQrTransferScreen
import me.nettrash.exchange.ui.screens.SettingsScreen
import me.nettrash.exchange.ui.screens.ShowIdentityQrTransferScreen
import me.nettrash.exchange.ui.screens.SplashScreen

object Routes {
    const val HOME = "home"
    const val COMPOSE = "compose"
    const val DECRYPT = "decrypt"
    const val ENCRYPT_FILE = "encryptFile"
    const val DECRYPT_FILE = "decryptFile"
    const val ADD_RECIPIENT = "addRecipient"
    const val MY_IDENTITY_QR = "myIdentityQR"
    const val SETTINGS = "settings"
    const val EXPORT_IDENTITY = "exportIdentity"
    const val IMPORT_IDENTITY = "importIdentity"
    const val SHOW_IDENTITY_QR_TRANSFER = "showIdentityQrTransfer"
    const val SCAN_IDENTITY_QR_TRANSFER = "scanIdentityQrTransfer"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExchangeApp(
    viewModel: ExchangeViewModel = viewModel(),
    onRequestUnlock: () -> Unit = {},
) {
    val navController = rememberNavController()
    val identityState by viewModel.identityState.collectAsState()
    val pendingEnvelope by viewModel.pendingDecryptEnvelope.collectAsState()
    val isLocked by viewModel.isLocked.collectAsState()

    Box {
    // A pending envelope (from a share / deep link) skips straight to
    // Decrypt the moment the identity is loaded.
    when (val state = identityState) {
        is ExchangeViewModel.IdentityState.Loading ->
            SplashScreen(message = "Setting up your secure identity…", showsProgress = true)

        is ExchangeViewModel.IdentityState.Error ->
            SplashScreen(message = state.message, showsProgress = false)

        is ExchangeViewModel.IdentityState.Loaded -> {
            // Start destination depends on whether an envelope is waiting.
            val start = if (pendingEnvelope != null) Routes.DECRYPT else Routes.HOME

            NavHost(navController = navController, startDestination = start) {
                composable(Routes.HOME) {
                    HomeScreen(
                        viewModel = viewModel,
                        onCompose = { navController.navigate(Routes.COMPOSE) },
                        onDecrypt = { navController.navigate(Routes.DECRYPT) },
                        onAddRecipient = { navController.navigate(Routes.ADD_RECIPIENT) },
                        onShowMyQr = { navController.navigate(Routes.MY_IDENTITY_QR) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onEncryptFile = { navController.navigate(Routes.ENCRYPT_FILE) },
                        onDecryptFile = { navController.navigate(Routes.DECRYPT_FILE) },
                    )
                }
                composable(Routes.COMPOSE) {
                    ComposeScreen(
                        viewModel = viewModel,
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(Routes.DECRYPT) {
                    DecryptScreen(
                        viewModel = viewModel,
                        onClose = {
                            viewModel.setPendingDecryptEnvelope(null)
                            if (navController.previousBackStackEntry == null) {
                                navController.navigate(Routes.HOME) {
                                    popUpTo(Routes.DECRYPT) { inclusive = true }
                                }
                            } else {
                                navController.popBackStack()
                            }
                        },
                    )
                }
                composable(Routes.ENCRYPT_FILE) {
                    EncryptFileScreen(
                        viewModel = viewModel,
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(Routes.DECRYPT_FILE) {
                    DecryptFileScreen(
                        viewModel = viewModel,
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(Routes.ADD_RECIPIENT) {
                    AddRecipientScreen(
                        viewModel = viewModel,
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(Routes.MY_IDENTITY_QR) {
                    MyIdentityQrScreen(
                        viewModel = viewModel,
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onClose = { navController.popBackStack() },
                        onExport = { navController.navigate(Routes.EXPORT_IDENTITY) },
                        onImport = { navController.navigate(Routes.IMPORT_IDENTITY) },
                        onSendQr = { navController.navigate(Routes.SHOW_IDENTITY_QR_TRANSFER) },
                        onReceiveQr = { navController.navigate(Routes.SCAN_IDENTITY_QR_TRANSFER) },
                    )
                }
                composable(Routes.EXPORT_IDENTITY) {
                    ExportIdentityScreen(
                        viewModel = viewModel,
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(Routes.IMPORT_IDENTITY) {
                    ImportIdentityScreen(
                        viewModel = viewModel,
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(Routes.SHOW_IDENTITY_QR_TRANSFER) {
                    ShowIdentityQrTransferScreen(
                        viewModel = viewModel,
                        onClose = { navController.popBackStack() },
                    )
                }
                composable(Routes.SCAN_IDENTITY_QR_TRANSFER) {
                    ScanIdentityQrTransferScreen(
                        viewModel = viewModel,
                        onClose = { navController.popBackStack() },
                    )
                }
            }
        }
    }

        // Lock overlay sits on top of whatever is showing, preserving the
        // nav stack underneath while the app is locked.
        if (isLocked) {
            LockScreen(onUnlock = onRequestUnlock)
        }
    }
}
