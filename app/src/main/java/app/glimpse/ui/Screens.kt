package app.glimpse.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.glimpse.android.Decode
import app.glimpse.android.uriOf
import app.glimpse.data.FaceInPhoto
import app.glimpse.data.Person
import app.glimpse.data.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/* ----------------------------------------------------------------------------------- building blocks */

/** A picture square or face circle: shown at once if it's in memory, otherwise fades in when loaded. */
@Composable
private fun Thumb(key: Any, desc: String, modifier: Modifier, cached: () -> ImageBitmap?, load: suspend () -> ImageBitmap?) {
    val img by produceState(remember(key) { cached() }, key) { value = cached() ?: withContext(Dispatchers.IO) { load() } }
    val fade = remember(key) { Animatable(if (img != null) 1f else 0f) }
    LaunchedEffect(key, img != null) { if (img != null) fade.animateTo(1f, tween(140)) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).semantics { contentDescription = desc }) {
        img?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun FaceCircle(vm: GlimpseViewModel, person: Person, size: Int) {
    val face = person.coverFace
    Thumb(face ?: -person.id, person.name ?: "Unnamed person", Modifier.size(size.dp).clip(CircleShape),
        cached = { face?.let(vm.thumbs::cachedFace) }) {
        face?.let { f -> vm.faceRef(f)?.let { vm.thumbs.face(f, it) } }
    }
}

private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "${"%,d".format(n)} $many"

/** One UI's tall header: big centred title over the top third of the screen, which scrolls away. */
private fun LazyGridScope.bigHeader(title: String, subtitle: String, extra: @Composable () -> Unit = {}) {
    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
        val h = LocalConfiguration.current.screenHeightDp
        Column(Modifier.fillMaxWidth().height((h * 0.30f).dp).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 36.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 2)
            Text(subtitle, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra()
        }
    }
}

/**
 * The compact bar on top: its title fades in once the big header has scrolled away.
 * [center] replaces the title (the People search field); the bar is then always solid.
 */
@Composable
private fun BoxScope.TopBar(
    title: String, grid: LazyGridState, nav: (@Composable () -> Unit)? = null,
    center: (@Composable RowScope.() -> Unit)? = null, actions: @Composable () -> Unit = {},
) {
    val scrolled by remember { derivedStateOf { grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 240 } }
    val solid = scrolled || center != null
    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
        .background(if (solid) MaterialTheme.colorScheme.background else Color.Transparent)
        .statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        nav?.invoke() ?: Spacer(Modifier.width(12.dp))
        if (center != null) center()
        else {
            AnimatedVisibility(scrolled, Modifier.weight(1f), enter = fadeIn(), exit = fadeOut()) {
                Text(title, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!scrolled) Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

@Composable
private fun Action(icon: ImageVector, desc: String, tint: Color = MaterialTheme.colorScheme.onBackground, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, desc, tint = tint) }
}

/** Two fingers change the number of columns, like Gallery; one finger still scrolls. */
private fun Modifier.pinchColumns(vm: GlimpseViewModel) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var acc = 1f
        do {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            if (ev.changes.count { it.pressed } >= 2) {
                acc *= ev.calculateZoom()
                if (acc > 1.3f) { vm.columns = (vm.columns - 1).coerceAtLeast(2); acc = 1f }
                if (acc < 0.77f) { vm.columns = (vm.columns + 1).coerceAtMost(7); acc = 1f }
                ev.changes.forEach { it.consume() }
            }
        } while (ev.changes.any { it.pressed })
    }
}

private fun dayLabel(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(c, now)) return "Today"
    if (sameDay(c, (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })) return "Yesterday"
    val pattern = if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "EEE, d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}

/**
 * [photos] (newest first) laid out as grid entries: a header for each day, then its pictures.
 * Built once per list, so a 12k-photo library isn't re-grouped on every scroll.
 */
private class Dated(val photos: List<Photo>) {
    val days = ArrayList<String>()
    private val dayKeys = ArrayList<Long>()
    val entries: IntArray // >= 0: index into photos; < 0: header of day -(e + 1)
    val dayOf: IntArray // entry -> its day
    val entryOf = IntArray(photos.size) // photo -> its entry

    init {
        val tz = TimeZone.getDefault()
        val e = ArrayList<Int>(photos.size + 256)
        val d = ArrayList<Int>(photos.size + 256)
        var last = Long.MIN_VALUE
        photos.forEachIndexed { i, p ->
            val day = if (p.date > 0) Math.floorDiv(p.date + tz.getOffset(p.date), 86_400_000L) else -1L
            if (day != last) {
                last = day
                days += if (p.date > 0) dayLabel(p.date) else "Undated"
                dayKeys += day
                e += -days.size; d += days.lastIndex
            }
            entryOf[i] = e.size
            e += i; d += days.lastIndex
        }
        entries = e.toIntArray()
        dayOf = d.toIntArray()
    }

    fun isDay(k: Int) = entries[k] < 0
    fun key(k: Int): Any = entries[k].let { if (it >= 0) photos[it].fingerprint else "d${dayKeys[-it - 1]}" }
    fun dayAt(k: Int): String? = dayOf.getOrNull(k.coerceIn(0, (dayOf.size - 1).coerceAtLeast(0)))?.let(days::get)
}

/** Pictures grouped under day headers, like Gallery's Pictures tab. */
private fun LazyGridScope.datedGrid(vm: GlimpseViewModel, d: Dated, onOpen: (Int) -> Unit) {
    items(d.entries.size, key = d::key, span = { if (d.isDay(it)) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
        contentType = { if (d.isDay(it)) "day" else "photo" }) { k ->
        val e = d.entries[k]
        if (e < 0) {
            Text(d.days[-e - 1], Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        } else {
            val p = d.photos[e]
            Thumb(p.fingerprint, "Picture, ${d.days[d.dayOf[k]]}", Modifier.aspectRatio(1f).clickable { onOpen(e) },
                cached = { vm.thumbs.cachedPhoto(p) }) { vm.thumbs.photo(p) }
        }
    }
}

/** Back from the viewer: if the last picture shown isn't on screen, scroll to it ([before] = grid items above the pictures). */
@Composable
private fun ReturnToViewed(vm: GlimpseViewModel, grid: LazyGridState, d: Dated, before: Int) {
    LaunchedEffect(d) {
        val fp = vm.returnTo ?: return@LaunchedEffect
        val i = d.photos.indexOfFirst { it.fingerprint == fp }
        if (i < 0) return@LaunchedEffect // not loaded yet; runs again when the list arrives
        vm.returnTo = null
        val idx = before + d.entryOf[i]
        val visible = grid.layoutInfo.visibleItemsInfo
        val fully = visible.drop(vm.columns).dropLast(vm.columns) // rows half under the top bar or the tabs don't count
        if (fully.none { it.index == idx }) grid.scrollToItem((idx - vm.columns * 2).coerceAtLeast(before))
    }
}

/**
 * Gallery-style fast scroll: a handle on the right edge while scrolling. Drag it to fly through
 * thousands of pictures; a bubble shows the day you're at.
 */
@Composable
private fun BoxScope.FastScroller(grid: LazyGridState, d: Dated, before: Int, bottom: androidx.compose.ui.unit.Dp) {
    val long by remember { derivedStateOf { grid.layoutInfo.let { it.totalItemsCount > it.visibleItemsInfo.size * 4 } } }
    if (!long) return
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = grid.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true else { delay(1500); shown = false }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "scroller")
    fun span() = grid.layoutInfo.let { (it.totalItemsCount - it.visibleItemsInfo.size).coerceAtLeast(1) }
    fun position() = (grid.firstVisibleItemIndex.toFloat() / span()).coerceIn(0f, 1f)
    LaunchedEffect(dragging) {
        if (dragging) snapshotFlow { drag }.collectLatest { grid.scrollToItem((it * span()).roundToInt()) }
    }
    val day by remember { derivedStateOf { d.dayAt(grid.firstVisibleItemIndex - before + 1) } }
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.align(Alignment.TopEnd).fillMaxHeight().statusBarsPadding().padding(top = 64.dp, bottom = bottom)) {
        val handle = 48.dp
        val track = constraints.maxHeight - with(LocalDensity.current) { handle.roundToPx() }
        Row(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, ((if (dragging) drag else position()) * track).roundToInt()) }
            .graphicsLayer { this.alpha = alpha }, verticalAlignment = Alignment.CenterVertically) {
            if (dragging && day != null) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
                    Text(day!!, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.size(32.dp, handle)
                .then(if (shown) Modifier.pointerInput(track) {
                    detectVerticalDragGestures(
                        onDragStart = { drag = position(); dragging = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                    ) { c, dy -> c.consume(); drag = (drag + dy / track).coerceIn(0f, 1f) }
                } else Modifier)
                .semantics { contentDescription = "Fast scroll" },
                contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.padding(end = 4.dp).size(if (dragging) 8.dp else 5.dp, 40.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
    }
}

/** "Finding faces 312 of 2,340 · 4 min left" with a thin progress line and Stop. */
@Composable
private fun ScanStatus(vm: GlimpseViewModel) {
    val s by vm.scan.collectAsState()
    if (!s.running && s.message.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val text = when {
            s.running && s.total > 0 -> {
                val left = if (s.rate > 0) ((s.total - s.done) / s.rate / 60).toInt() else -1
                "${s.phase} ${"%,d".format(s.done)} of ${"%,d".format(s.total)}" +
                    (if (left >= 1) " · $left min left" else if (left == 0) " · under a minute left" else "")
            }
            s.running -> s.phase
            else -> s.message
        } + if (s.running && s.onPc) " · on your PC" else ""
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (s.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.total > 0) {
                    // Read now: the lambda runs later, when `s` may already be a phase with total = 0 (0/0 = NaN crashes).
                    val fraction = s.done.toFloat() / s.total
                    LinearProgressIndicator(progress = { fraction }, Modifier.width(160.dp))
                } else LinearProgressIndicator(Modifier.width(160.dp))
                TextButton(onClick = vm::stopScan) { Text("Stop") }
            }
        }
    }
}

/* ----------------------------------------------------------------------------------- home: tabs */

@Composable
fun HomeScreen(vm: GlimpseViewModel, partial: Boolean, onWiden: () -> Unit) {
    BackHandler(enabled = vm.tab != 0) { vm.tab = 0 } // back from People goes to Pictures first, like Gallery
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (vm.tab == 0) PicturesTab(vm, partial, onWiden) else PeopleTab(vm)
        }
        BottomTabs(vm)
    }
}

@Composable
private fun BottomTabs(vm: GlimpseViewModel) {
    val scope = rememberCoroutineScope()
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            listOf("Pictures" to GlimpseIcons.Pictures, "People" to GlimpseIcons.People).forEachIndexed { i, (label, icon) ->
                val sel = vm.tab == i
                val fg = if (sel) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.clip(RoundedCornerShape(20.dp)).selectable(sel, role = Role.Tab) {
                    if (!sel) vm.tab = i
                    else scope.launch { // tapping the open tab again goes back to the top
                        val g = if (i == 0) vm.picturesGrid else vm.peopleGrid
                        if (g.firstVisibleItemIndex > 60) g.scrollToItem(0) else g.animateScrollToItem(0)
                    }
                }.padding(horizontal = 20.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .padding(horizontal = 18.dp, vertical = 4.dp)) {
                        Icon(icon, null, Modifier.size(22.dp), tint = fg)
                    }
                    Text(label, Modifier.padding(top = 2.dp), fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, color = fg)
                }
            }
        }
    }
}

@Composable
private fun PicturesTab(vm: GlimpseViewModel, partial: Boolean, onWiden: () -> Unit) {
    val grid = vm.picturesGrid
    val dated = remember(vm.all) { Dated(vm.all) }
    val scanning = vm.scan.collectAsState().value.running
    var menu by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    // Items above the pictures: the header, then maybe the "picked pictures" notice and the people row.
    val before = 1 + (if (partial) 1 else 0) + (if (vm.people.isNotEmpty()) 1 else 0)
    ReturnToViewed(vm, grid, dated, before)
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Fixed(vm.columns), Modifier.fillMaxSize().pinchColumns(vm), grid,
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            bigHeader("Pictures", count(vm.all.size, "picture", "pictures")) { ScanStatus(vm) }
            if (partial) item(span = { GridItemSpan(maxLineSpan) }, key = "partial") {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Glimpse can see only the pictures you picked.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onWiden) { Text("Allow all") }
                }
            }
            if (vm.people.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "people") {
                Column {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("People", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { vm.tab = 1 }) { Text("View all") }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(vm.people.take(20), key = { it.id }) { p ->
                            Column(Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable { vm.openPerson(listOf(p.id)) }
                                .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                FaceCircle(vm, p, 64)
                                Text(p.name ?: "", Modifier.padding(top = 4.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (vm.all.isEmpty() && !scanning) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                Text("No pictures yet. Pictures you take or save appear here.", Modifier.padding(32.dp),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            datedGrid(vm, dated) { vm.openViewer(false, it) }
        }
        FastScroller(grid, dated, before, bottom = 8.dp)
        TopBar("Pictures", grid) {
            Box {
                Action(GlimpseIcons.More, "More options") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    val pc = vm.pc
                    if (pc == null) DropdownMenuItem({ Text("Find faces on PC…") }, { menu = false; pairing = true })
                    else DropdownMenuItem({ Text("Stop using PC (${pc.host})") }, { menu = false; vm.unpairPc() })
                    DropdownMenuItem({ Text("About Glimpse") }, { menu = false; about = true })
                }
            }
        }
    }
    if (pairing) PairPcDialog(vm) { pairing = false }
    if (about) AboutDialog { about = false }
}

const val SOURCE_URL = "https://github.com/sam404rihan/glimpse"

/** Version, what Glimpse does with your photos, and whose work it stands on. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismiss, title = { Text("Glimpse ${app.glimpse.BuildConfig.VERSION_NAME}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Finds the people in your photos, on this phone. Your pictures are only read: never copied, moved, " +
                    "deleted or uploaded. Faces are only sent anywhere if you pair your own PC, and then only over your home Wi-Fi.")
                Text("Open source", Modifier.padding(top = 16.dp, bottom = 4.dp), fontWeight = FontWeight.SemiBold)
                Text("Glimpse: Apache License 2.0.\n" +
                    "Face models: InsightFace buffalo_m (SCRFD, ArcFace), for non-commercial use only.\n" +
                    "ONNX Runtime: MIT License.\n" +
                    "Jetpack Compose and AndroidX: Apache License 2.0.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL))) }
            }) { Text("Source code") }
        })
}

/** Pairs with pc_helper.py: the PC then finds faces while photos stay on the phone. */
@Composable
private fun PairPcDialog(vm: GlimpseViewModel, onDone: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun connect() {
        if (busy) return
        busy = true
        scope.launch {
            error = vm.pairPc(code)
            busy = false
            if (error == null) onDone()
        }
    }
    AlertDialog(onDone, title = { Text("Find faces on PC") },
        text = {
            Column {
                Text("On the PC, run pc_helper.py and type the code it shows. Photos are sent over your Wi-Fi " +
                    "only to analyse them, and the PC doesn't keep them. When the PC is off, the phone does the work.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(code, { code = it; error = null }, Modifier.padding(top = 12.dp), singleLine = true,
                    placeholder = { Text("192.168.1.5:8765/abcdefghjk") }, isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { connect() }))
            }
        },
        confirmButton = { TextButton(onClick = ::connect, enabled = !busy) { Text(if (busy) "Connecting…" else "Connect") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } })
}

@Composable
private fun rememberAutoFocus(): FocusRequester {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } } // after the field is attached
    return focus
}

/** Splits "alex, sam & ali" into names; the last part is the one still being typed. */
private val NAME_SEPARATORS = Regex("""\s*(?:,|&|\+|\band\b)\s*""", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeopleTab(vm: GlimpseViewModel) {
    val grid = vm.peopleGrid
    val haptic = LocalHapticFeedback.current
    var query by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(listOf<Long>()) } // the group: long-press, or pick while searching
    var deleting by remember { mutableStateOf<List<Long>?>(null) }
    BackHandler(enabled = query != null) { query = null }
    BackHandler(enabled = selected.isNotEmpty() && query == null) { selected = emptyList() }
    val term = query?.split(NAME_SEPARATORS)?.lastOrNull()?.trim().orEmpty()
    val shown = if (term.isEmpty()) vm.people else vm.people.filter { (it.name ?: "").contains(term, ignoreCase = true) }
    fun toggle(id: Long) { selected = if (id in selected) selected - id else selected + id }
    fun addTyped() { // the Search key: every name typed so far joins the group
        val ids = vm.resolveNames(query.orEmpty())
        if (ids.isNotEmpty()) { selected = (selected + ids).distinct(); query = "" }
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), grid,
            contentPadding = PaddingValues(bottom = if (selected.isEmpty()) 72.dp else 104.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            bigHeader("People", count(vm.people.size, "person", "people")) { ScanStatus(vm) }
            if (query != null && selected.isEmpty() && term.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "hint") {
                Text("Pick one or more people to see the pictures they're in together. You can also type names, like “Alex, Sam”.",
                    Modifier.padding(horizontal = 32.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.people.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                Text(
                    if (vm.scan.collectAsState().value.running) "Looking for faces. People appear here once someone is in ${app.glimpse.data.PeopleStore.MIN_PICTURES} pictures."
                    else "No people yet. Someone needs to be in at least ${app.glimpse.data.PeopleStore.MIN_PICTURES} pictures.",
                    Modifier.padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "none") {
                Text("No one called “$term”. Only named people can be found.", Modifier.padding(32.dp),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shown, key = { it.id }) { p ->
                val sel = p.id in selected
                Column(
                    Modifier.clip(RoundedCornerShape(16.dp)).combinedClickable(
                        onClick = {
                            when {
                                query != null -> { toggle(p.id); query = "" } // searching = building a group
                                selected.isEmpty() -> vm.openPerson(listOf(p.id))
                                else -> toggle(p.id)
                            }
                        },
                        onLongClickLabel = "Select",
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); toggle(p.id) },
                    ).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        FaceCircle(vm, p, 92)
                        if (selected.isNotEmpty() || query != null) Box(Modifier.align(Alignment.TopStart).size(24.dp).clip(CircleShape)
                            .background(if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                            if (sel) Icon(GlimpseIcons.Check, null, Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                    Text(vm.label(p), Modifier.padding(top = 8.dp).padding(horizontal = 4.dp), fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (p.name == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                    Text(count(p.count, "picture", "pictures"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (query != null) {
            val focus = rememberAutoFocus()
            TopBar("People", grid, nav = { Action(GlimpseIcons.Back, "Close search") { query = null } }, center = {
                TextField(query!!, { query = it }, Modifier.weight(1f).focusRequester(focus).clip(RoundedCornerShape(26.dp)),
                    placeholder = { Text(if (selected.isEmpty()) "Search names" else "Add someone") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { addTyped() }),
                    trailingIcon = { if (query!!.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(GlimpseIcons.Close, "Clear") } },
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            })
        } else TopBar("People", grid) {
            Action(GlimpseIcons.Search, "Search names") { query = "" }
        }
        if (selected.isNotEmpty()) GroupBar(vm, selected, Modifier.align(Alignment.BottomCenter),
            onCancel = { selected = emptyList(); query = null },
            onShow = { vm.openPerson(selected); selected = emptyList(); query = null },
            onDelete = { deleting = selected })
    }
    deleting?.let { ids -> DeleteFacesDialog(vm, ids, onDone = { deleted -> deleting = null; if (deleted) { selected = emptyList(); query = null } }) }
}

/**
 * The group being built, as one floating row: close, their faces, who and how many pictures they share, Show.
 * Taking someone out = tapping them again in the grid. Delete lives in the ⋮ menu, away from Show.
 */
@Composable
private fun GroupBar(vm: GlimpseViewModel, group: List<Long>, modifier: Modifier, onCancel: () -> Unit, onShow: () -> Unit, onDelete: () -> Unit) {
    val together by produceState<Int?>(null, group, vm.people) { value = vm.togetherCount(group) }
    var menu by remember { mutableStateOf(false) }
    val names = group.map { vm.person(it)?.name ?: "Unnamed" }
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Action(GlimpseIcons.Close, "Clear selection", MaterialTheme.colorScheme.onSurfaceVariant, onCancel)
            Row(horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
                group.take(3).forEach { id -> vm.person(id)?.let { FaceCircle(vm, it, 32) } }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp)) {
                Text(
                    when (names.size) { 1 -> names[0]; 2 -> "${names[0]} & ${names[1]}"; else -> "${names[0]}, ${names[1]} +${names.size - 2}" },
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val n = together
                Text(
                    when {
                        n == null -> " "
                        group.size == 1 -> count(n, "picture", "pictures")
                        n == 0 -> "No pictures together"
                        else -> count(n, "picture", "pictures") + " together"
                    },
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            androidx.compose.material3.Button(onClick = onShow, enabled = together != 0, shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 18.dp)) { Text("Show") }
            Box {
                Action(GlimpseIcons.More, "More options", MaterialTheme.colorScheme.onSurfaceVariant) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Delete face data…", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete() })
                }
            }
        }
    }
}

/* ----------------------------------------------------------------------------------- person */

@Composable
fun PersonScreen(vm: GlimpseViewModel, screen: Screen.Person) {
    val ids = screen.people
    val grid = vm.personGrid(screen)
    val dated = remember(vm.personPhotos) { Dated(vm.personPhotos) }
    var rename by remember { mutableStateOf<Long?>(null) }
    var mergeFrom by remember { mutableStateOf<Long?>(null) }
    var addPerson by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val one = ids.singleOrNull()?.let { vm.person(it) }
    val title = if (ids.size == 1) one?.name ?: "Unnamed" else ids.joinToString(" & ") { vm.person(it)?.name ?: "Unnamed" }
    ReturnToViewed(vm, grid, dated, before = 1)
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Fixed(vm.columns), Modifier.fillMaxSize().pinchColumns(vm), grid,
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 64.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-18).dp)) { ids.take(4).forEach { id -> vm.person(id)?.let { FaceCircle(vm, it, if (ids.size == 1) 112 else 72) } } }
                    if (ids.size == 1 && one?.name == null) {
                        TextButton(onClick = { rename = ids[0] }, Modifier.padding(top = 8.dp)) { Text("+ Add name", fontSize = 20.sp) }
                    } else {
                        Text(title, Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp)
                            .then(if (ids.size == 1) Modifier.clip(RoundedCornerShape(8.dp)).clickable { rename = ids[0] } else Modifier),
                            fontSize = 26.sp, textAlign = TextAlign.Center)
                    }
                    Text(
                        if (vm.personPhotos.isEmpty()) when {
                            screen.only && ids.size > 1 -> "No pictures of only them"
                            screen.only -> "No pictures of them alone"
                            ids.size > 1 -> "No pictures of them together"
                            else -> "No pictures"
                        } else count(vm.personPhotos.size, "picture", "pictures"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Together: all of them are in it, anyone else may be too. Only them: nobody else clearly visible.
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to (if (ids.size > 1) "Together" else "All"), true to (if (ids.size > 1) "Only them" else "Alone"))
                            .forEach { (only, label) ->
                                FilterChip(screen.only == only, onClick = { if (screen.only != only) vm.openPerson(ids, only) },
                                    label = { Text(label) }, shape = RoundedCornerShape(18.dp))
                            }
                    }
                    if (ids.size > 1) LazyRow(Modifier.padding(top = 10.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ids) { id ->
                            Row(Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { vm.openPerson(ids - id, screen.only) }.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                vm.person(id)?.let { FaceCircle(vm, it, 26) }
                                Text(vm.person(id)?.name ?: "Unnamed", Modifier.padding(horizontal = 6.dp), fontSize = 13.sp)
                                Icon(GlimpseIcons.Close, "Remove", Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
            datedGrid(vm, dated) { vm.openViewer(true, it) }
        }
        FastScroller(grid, dated, before = 1, bottom = 24.dp)
        TopBar(title, grid, nav = { Action(GlimpseIcons.Back, "Back") { vm.back() } }) {
            Box {
                Action(GlimpseIcons.More, "More options") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    if (ids.size == 1) {
                        DropdownMenuItem({ Text(if (one?.name == null) "Add name" else "Edit name") }, { menu = false; rename = ids[0] })
                        DropdownMenuItem({ Text("Same person as…") }, { menu = false; mergeFrom = ids[0] })
                    }
                    DropdownMenuItem({ Text("Add person (pictures together)") }, { menu = false; addPerson = true })
                    DropdownMenuItem({ Text("Delete face data…", color = MaterialTheme.colorScheme.error) }, { menu = false; deleting = true })
                }
            }
        }
    }
    rename?.let { id -> RenameDialog(vm, id) { rename = null } }
    mergeFrom?.let { id ->
        PickPerson(vm, "Same person as…", exclude = listOf(id), suggestFor = id, { mergeFrom = null }) { other -> vm.merge(id, other); mergeFrom = null }
    }
    if (deleting) DeleteFacesDialog(vm, ids, onDone = { deleting = false })
    if (addPerson) PickPerson(vm, "Pictures together with…", exclude = ids, suggestFor = null, { addPerson = false }) { other ->
        vm.openPerson(ids + other, screen.only); addPerson = false
    }
}

/** Names a person. Typing a name someone else already has offers to put the two together instead of making twins. */
@Composable
private fun RenameDialog(vm: GlimpseViewModel, id: Long, onDismiss: () -> Unit) {
    val initial = vm.person(id)?.name ?: ""
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = rememberAutoFocus()
    val same = vm.namesake(id, name.text)
    val typed = name.text.trim()
    val matches = if (typed.isEmpty() || same != null) emptyList()
        else vm.people.filter { it.id != id && it.name?.startsWith(typed, ignoreCase = true) == true }.take(4)
    fun save() { vm.rename(id, name.text); onDismiss() }
    AlertDialog(onDismiss, title = { Text(if (initial.isEmpty()) "Add name" else "Edit name") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    placeholder = { Text("Who is this?") },
                    supportingText = same?.let { { Text("${it.name} is already in ${count(it.count, "picture", "pictures")}. Saving puts them together.") } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }))
                if (matches.isNotEmpty()) Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    matches.forEach { p ->
                        Row(Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { val n = p.name!!; name = TextFieldValue(n, TextRange(n.length)) }
                            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            FaceCircle(vm, p, 26)
                            Text(p.name!!, Modifier.padding(start = 6.dp), fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::save) { Text(if (same != null) "Put together" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun PickPerson(vm: GlimpseViewModel, title: String, exclude: List<Long>, suggestFor: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val suggested by produceState(emptyList<Person>(), suggestFor) { value = suggestFor?.let { vm.similar(it) } ?: emptyList() }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val others = (suggested + vm.people.filter { it.id !in exclude && suggested.none { s -> s.id == it.id } })
        .filter { q.isEmpty() || it.name?.contains(q, ignoreCase = true) == true }
    AlertDialog(onDismiss, title = { Text(title) }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }, text = {
        Column {
            if (vm.people.size > 12) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true,
                placeholder = { Text("Search names") }, leadingIcon = { Icon(GlimpseIcons.Search, null) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search))
            LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (suggested.isNotEmpty() && q.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Most alike first", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (others.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (q.isEmpty()) "No one else yet." else "No one called “$q”.", Modifier.padding(vertical = 24.dp),
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(others, key = { it.id }) { p ->
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(p.id) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FaceCircle(vm, p, 64)
                        Text(p.name ?: "Unnamed", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text("${p.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    })
}

/**
 * Deletes what Glimpse stored about [ids]' faces, after the phone's own lock (fingerprint, face or PIN) says it's you.
 * [onDone] gets whether anything was deleted.
 */
@Composable
private fun DeleteFacesDialog(vm: GlimpseViewModel, ids: List<Long>, onDone: (Boolean) -> Unit) {
    val activity = LocalActivity.current as? androidx.fragment.app.FragmentActivity
    var error by remember { mutableStateOf<String?>(null) }
    val names = ids.map { vm.person(it)?.name ?: "Unnamed" }
    val faces = ids.sumOf { vm.person(it)?.count ?: 0 }
    val locked = activity != null && Auth.available(activity)
    AlertDialog({ onDone(false) },
        icon = { Icon(GlimpseIcons.Delete, null) },
        title = { Text(if (ids.size == 1) "Delete ${names[0]}'s face data?" else "Delete face data of ${ids.size} people?") },
        text = {
            Column {
                Text("Glimpse forgets these faces" + (if (names.any { it != "Unnamed" }) " and names" else "") +
                    " (" + count(faces, "picture", "pictures") + "). They won't come back on later scans. " +
                    "Your pictures stay exactly where they are. This can't be undone.")
                if (!locked) Text("Set a screen lock (fingerprint, face or PIN) in Settings first. Deleting face data needs it.",
                    Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = locked, onClick = {
                Auth.confirm(activity!!, "Delete face data", "Confirm it's you",
                    onOk = { vm.forget(ids); onDone(true) }, onError = { error = it })
            }) { Text("Delete", color = if (locked) MaterialTheme.colorScheme.error else Color.Unspecified) }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Cancel") } })
}

/* ----------------------------------------------------------------------------------- viewer */

@Composable
fun ViewerScreen(vm: GlimpseViewModel, ofPerson: Boolean, start: Int) {
    val photos = vm.viewerPhotos(ofPerson)
    if (photos.isEmpty()) { LaunchedEffect(Unit) { vm.back() }; return } // e.g. "Not them" on their last picture
    val pager = rememberPagerState(initialPage = start.coerceIn(0, photos.lastIndex)) { photos.size }
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val side = LocalConfiguration.current.let { maxOf(it.screenWidthDp, it.screenHeightDp) } * 2
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var dismiss by remember { mutableFloatStateOf(0f) } // how far the picture has been dragged down to close
    val person = (vm.stack.lastOrNull { it is Screen.Person } as? Screen.Person)?.people?.singleOrNull()?.takeIf { ofPerson }
    val current by rememberUpdatedState(photos)
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { vm.returnTo = current.getOrNull(it)?.fingerprint } }

    // Light icons on the black viewer; tapping the picture hides the system bars too. Both are put back on leaving.
    val activity = LocalActivity.current
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    val bars = remember(activity) { activity?.window?.let { WindowCompat.getInsetsController(it, view) } }
    DisposableEffect(bars) {
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            bars?.isAppearanceLightStatusBars = !dark
            bars?.isAppearanceLightNavigationBars = !dark
        }
    }
    LaunchedEffect(chrome) { if (chrome) bars?.show(WindowInsetsCompat.Type.systemBars()) else bars?.hide(WindowInsetsCompat.Type.systemBars()) }

    val closeAt = with(density) { 110.dp.toPx() }
    Box(Modifier.fillMaxSize().drawBehind {
        drawRect(Color.Black.copy(alpha = (1f - abs(dismiss) / (size.height / 2)).coerceIn(0f, 1f)))
    }) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { photos[it].fingerprint }) { page ->
            val p = photos[page]
            val img by produceState(vm.thumbs.cachedPhoto(p), p.fingerprint) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        val d = Decode.pixels(ctx.contentResolver, uriOf(p.volume, p.mediaId), side)
                        android.graphics.Bitmap.createBitmap(d.px, d.w, d.h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
                    }.getOrNull()
                } ?: value
            }
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var box by remember { mutableStateOf(IntSize.Zero) }
            val scope = rememberCoroutineScope()
            // Keeps the zoomed picture covering the screen: it can't be dragged off into the black.
            fun bound(o: Offset, s: Float): Offset {
                val b = img ?: return Offset.Zero
                if (box.width == 0 || box.height == 0) return Offset.Zero
                val fit = minOf(box.width / b.width.toFloat(), box.height / b.height.toFloat())
                val mx = ((b.width * fit * s - box.width) / 2).coerceAtLeast(0f)
                val my = ((b.height * fit * s - box.height) / 2).coerceAtLeast(0f)
                return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
            }
            fun zoomTo(s: Float, o: Offset) {
                val s0 = scale; val o0 = offset
                zoomed = s > 1f
                scope.launch { animate(0f, 1f, animationSpec = tween(220)) { t, _ -> scale = s0 + (s - s0) * t; offset = o0 + (o - o0) * t } }
            }
            val zoom = rememberTransformableState { z, pan, _ ->
                scale = (scale * z).coerceIn(1f, 5f)
                offset = bound(offset + pan, scale)
                zoomed = scale > 1f
            }
            Box(Modifier.fillMaxSize().onSizeChanged { box = it }
                .transformable(zoom, canPan = { scale > 1f })
                .pointerInput(p.fingerprint) {
                    detectTapGestures(onTap = { chrome = !chrome }, onDoubleTap = { tap ->
                        if (scale > 1f) zoomTo(1f, Offset.Zero)
                        else {
                            val s = 2.5f
                            val c = Offset(box.width / 2f, box.height / 2f)
                            zoomTo(s, bound((c - tap) * (s - 1f), s)) // the spot you tapped stays under your finger
                        }
                    })
                }
                .pointerInput(p.fingerprint) {
                    // Drag down (or up) to close, like Gallery. Only at normal size, with one finger, and mostly vertical.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (scale > 1f) return@awaitEachGesture
                        var dx = 0f; var dy = 0f; var dragging = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } > 1) { dragging = false; dismiss = 0f; break } // a pinch
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val m = c.positionChange()
                            if (!dragging) {
                                dx += m.x; dy += m.y
                                if (abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy)) break // the pager's swipe
                                if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx) * 1.5f) { dragging = true; chrome = false }
                            }
                            if (dragging) { dismiss += m.y; c.consume() }
                        }
                        if (dragging) {
                            if (abs(dismiss) > closeAt) vm.back()
                            else scope.launch { animate(dismiss, 0f, animationSpec = tween(180)) { v, _ -> dismiss = v } }
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                img?.let {
                    Image(it, "Picture", Modifier.fillMaxSize().graphicsLayer {
                        val shrink = 1f - (abs(dismiss) / size.height).coerceAtMost(.5f) * .4f
                        scaleX = scale * shrink; scaleY = scale * shrink
                        translationX = offset.x; translationY = offset.y + dismiss
                    }, contentScale = ContentScale.Fit)
                }
            }
        }
        val p = photos.getOrNull(pager.currentPage) ?: return@Box
        val faces by produceState(emptyList<FaceInPhoto>(), p.fingerprint, vm.people) { value = vm.facesIn(p) }
        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .35f)).statusBarsPadding().height(56.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Action(GlimpseIcons.Back, "Back", Color.White) { vm.back() }
                Column(Modifier.weight(1f)) {
                    if (p.date > 0) {
                        Text(SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(p.date)), color = Color.White, fontSize = 15.sp)
                        Text(SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(p.date)), color = Color.White.copy(.7f), fontSize = 12.sp)
                    }
                }
                Text("${pager.currentPage + 1} of ${"%,d".format(photos.size)}", Modifier.padding(end = 16.dp),
                    color = Color.White.copy(.7f), fontSize = 12.sp)
            }
        }
        AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .35f)).navigationBarsPadding()) {
                val named = faces.filter { f -> f.person != null && vm.person(f.person) != null }
                if (named.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(named, key = { it.id }) { f ->
                        val who = vm.person(f.person!!)
                        Column(Modifier.width(56.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.jumpToPerson(f.person) },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            who?.let { FaceCircle(vm, it, 44) }
                            Text(who?.name ?: "Add name", color = if (who?.name == null) Color.White.copy(.6f) else Color.White,
                                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    val uri = uriOf(p.volume, p.mediaId)
                    BarButton(GlimpseIcons.Share, "Share") {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
                    }
                    BarButton(GlimpseIcons.Open, "Open in") {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open in"))
                    }
                    if (person != null && faces.any { it.person == person }) {
                        BarButton(GlimpseIcons.PersonOff, "Not ${vm.person(person)?.name ?: "them"}") { vm.notThem(p, person) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White)
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
}
