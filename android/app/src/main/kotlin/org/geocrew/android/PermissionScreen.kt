package org.geocrew.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The permissions GeoCrew asks for, on one screen: before anything else, and
 * again from the settings ([onBack]). All-files access for the GeoCrew folder
 * (ADR 0002) comes first, then location, the camera, notifications and
 * background running. What is not granted can be skipped, all at once: it is
 * asked for when first needed (without the files, projects stay inside the
 * app). At start the screen is left out once each is granted or skipped; from
 * the settings it shows the cards alone. [resumed] changes whenever the user
 * comes back from the system settings, so the cards show what is granted now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(graph: AppGraph, resumed: Int, onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    var answered by remember { mutableIntStateOf(0) }
    val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        graph.settings.markLocationAsked()
        answered++
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        graph.settings.markCameraAsked()
        answered++
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        graph.settings.markNotificationsAsked()
        answered++
    }
    // Read again after every return from the settings and every answer.
    val files = remember(resumed, answered) { graph.hasAllFilesAccess }
    val located = remember(resumed, answered) { graph.hasLocation(context) }
    val filming = remember(resumed, answered) { mayUseCamera(context) }
    val notified = remember(resumed, answered) { notificationsShown(context) }
    val free = remember(resumed, answered) { unrestricted(context) }
    // What was not granted is skipped: asked for when first needed, and the screen does not come up for it again.
    // Without the files, projects stay inside the app.
    fun leave() {
        if (!files) graph.settings.chooseOwnFolder()
        if (!located) graph.settings.markLocationAsked()
        if (!filming) graph.settings.markCameraAsked()
        if (!notified) graph.settings.markNotificationsAsked()
        if (!free) graph.settings.markBatteryAsked()
        onDone()
    }

    val header: @Composable () -> Unit = {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.VerifiedUser, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.permissions_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.permissions_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val cards: @Composable () -> Unit = {
        PermissionCard(
            icon = Icons.Rounded.FolderOpen,
            title = stringResource(R.string.permission_storage),
            body = stringResource(R.string.permission_storage_body),
            granted = files,
            onGrant = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null)),
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            icon = Icons.Rounded.MyLocation,
            title = stringResource(R.string.permission_location),
            body = stringResource(R.string.permission_location_body),
            granted = located,
            onGrant = {
                location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            icon = Icons.Rounded.PhotoCamera,
            title = stringResource(R.string.permission_camera),
            body = stringResource(R.string.permission_camera_body),
            granted = filming,
            onGrant = { camera.launch(Manifest.permission.CAMERA) },
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            icon = Icons.Rounded.Notifications,
            title = stringResource(R.string.permission_notifications),
            body = stringResource(R.string.permission_notifications_body),
            granted = notified,
            onGrant = {
                // Asked for where the system asks (Android 13 on, not allowed yet); else turned on in the app's notification settings.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    graph.settings.markNotificationsAsked()
                    openNotificationSettings(context)
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        PermissionCard(
            icon = Icons.Rounded.BatteryFull,
            title = stringResource(R.string.permission_background),
            body = stringResource(R.string.permission_background_body),
            granted = free,
            onGrant = {
                graph.settings.markBatteryAsked()
                graph.settings.markBatteryPrompted()
                askUnrestricted(context)
            },
        )
    }
    // All granted, the screen goes on; else it skips what is left, saying where that can be granted later.
    val all = files && located && filming && notified && free
    val actions: @Composable () -> Unit = {
        if (all) {
            Button(onClick = { leave() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.permissions_continue))
            }
        } else {
            Text(
                stringResource(R.string.permissions_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // Skipped, the projects stay inside the app for good: said before it is chosen.
            if (!files && !graph.settings.ownFolderChosen) {
                Text(
                    stringResource(R.string.permissions_own_folder_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { leave() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.permissions_skip))
            }
        }
    }

    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_permissions)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    },
                )
            }
        },
    ) { padding ->
        if (onBack != null) {
            // From the settings: the cards alone, to change what was chosen.
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp)) { cards() }
            }
            return@Scaffold
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth > maxHeight && maxWidth >= 600.dp) {
                // Landscape: what it is about on the left, what to grant on the right.
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                    ) { header() }
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                        cards()
                        Spacer(Modifier.height(16.dp))
                        actions()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                        Spacer(Modifier.height(32.dp))
                        header()
                        Spacer(Modifier.height(20.dp))
                        cards()
                        Spacer(Modifier.height(16.dp))
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) { actions() }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: String, body: String, granted: Boolean, onGrant: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.permission_granted), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                FilledTonalButton(onClick = onGrant) { Text(stringResource(R.string.permission_grant)) }
            }
        }
    }
}
