package app.glimpse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.glimpse.R
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** Samsung One UI colours: white/black canvas, soft grey surfaces, Samsung blue for actions. */
private val Light: ColorScheme = lightColorScheme(
    primary = Color(0xFF3E91FF), onPrimary = Color.White,
    background = Color(0xFFFCFCFC), onBackground = Color(0xFF010101),
    surface = Color(0xFFF6F6F6), onSurface = Color(0xFF010101),
    surfaceVariant = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFF767676),
    surfaceContainerHigh = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFDCEBFF),
)
private val Dark: ColorScheme = darkColorScheme(
    primary = Color(0xFF3E91FF), onPrimary = Color.White,
    background = Color(0xFF000000), onBackground = Color(0xFFFAFAFA),
    surface = Color(0xFF171717), onSurface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFF252525), onSurfaceVariant = Color(0xFF8C8C8C),
    surfaceContainerHigh = Color(0xFF252525), secondaryContainer = Color(0xFF16304F),
)

enum class Access { NONE, PARTIAL, FULL }

class MainActivity : FragmentActivity() { // FragmentActivity: BiometricPrompt needs it
    private val vm: GlimpseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(vm) }
            }
        }
    }
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.POST_NOTIFICATIONS)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
private fun Root(vm: GlimpseViewModel) {
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun access() = when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> Access.FULL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        else -> Access.NONE
    }
    var access by remember { mutableStateOf(access()) }
    var refused by remember { mutableStateOf(false) } // Android stops asking after two refusals: send them to Settings
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = access()
        if (access != Access.NONE) vm.startScan()
        else refused = (ctx as? ComponentActivity)?.shouldShowRequestPermissionRationale(mediaPermissions()[0]) == false
    }
    // Coming back from Settings (or from the system photo picker) may have changed what Glimpse can see.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val now = access()
                if (now != access) { if (access == Access.NONE || now == Access.FULL) vm.startScan(); access = now }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    if (access == Access.NONE) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            // The launcher icon's two layers, cropped like a launcher would (the visible part is the middle 72 of 108).
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp))) {
                listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground).forEach {
                    Image(painterResource(it), null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
                }
            }
            Text("Glimpse", Modifier.padding(top = 16.dp), fontSize = 40.sp)
            Text(
                "Finds the people in your photos, on this phone. Glimpse reads your photos where they are. " +
                    "It never copies, moves, deletes or uploads them.",
                Modifier.padding(top = 12.dp, bottom = 28.dp), style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (refused) {
                Text("Access was turned off. Allow it in Settings under Permissions > Photos and videos.",
                    Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Button(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                }, shape = RoundedCornerShape(24.dp)) { Text("Open Settings", Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            } else {
                Button(onClick = { ask.launch(mediaPermissions()) }, shape = RoundedCornerShape(24.dp)) {
                    Text("Allow access to pictures", Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }
        }
        return
    }

    DisposableEffect(Unit) {
        vm.startScan()
        val obs = vm.observeGallery()
        onDispose { vm.unobserve(obs) }
    }
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.offer?.id) {
        val o = vm.offer ?: return@LaunchedEffect
        val undo = o.undo
        val r = snack.showSnackbar(o.message, actionLabel = undo?.let { "Undo" }, withDismissAction = false,
            duration = if (undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) undo?.invoke()
        if (vm.offer === o) vm.offer = null
    }
    Box(Modifier.fillMaxSize()) {
        when (val s = vm.screen) {
            Screen.Home -> HomeScreen(vm, partial = access == Access.PARTIAL, onWiden = { ask.launch(mediaPermissions()) })
            is Screen.Person -> PersonScreen(vm, s)
            is Screen.Viewer -> ViewerScreen(vm, s.ofPerson, s.start)
        }
        // Above the bottom tabs, or above the viewer's buttons.
        val lift = when (vm.screen) { Screen.Home -> 64.dp; is Screen.Viewer -> 150.dp; else -> 8.dp }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = lift))
    }
}
