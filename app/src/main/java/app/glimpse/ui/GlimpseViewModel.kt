package app.glimpse.ui

import android.app.Application
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.glimpse.GlimpseApp
import app.glimpse.android.PcClient
import app.glimpse.android.PcLink
import app.glimpse.android.PcRefused
import app.glimpse.android.Thumbs
import app.glimpse.data.FaceInPhoto
import app.glimpse.data.Person
import app.glimpse.data.Photo
import app.glimpse.data.Stats
import app.glimpse.scan.ScanService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    /** Pictures with all of [people] in them; [only] = and no one else. */
    data class Person(val people: List<Long>, val only: Boolean = false) : Screen
    data class Viewer(val ofPerson: Boolean, val start: Int) : Screen
}

/** A snackbar message, with Undo when [undo] is set. [id] makes two identical messages two snackbars. */
class UndoOffer(val message: String, val undo: (() -> Unit)?) { val id = System.nanoTime() }

class GlimpseViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GlimpseApp
    val thumbs = Thumbs(app.contentResolver)
    val scan = app.scans.state

    var tab by mutableIntStateOf(0) // 0 Pictures, 1 People
    var columns by mutableIntStateOf(4) // pinch to change, like Gallery
    var people by mutableStateOf<List<Person>>(emptyList()); private set
    var stats by mutableStateOf(Stats(0, 0)); private set
    var all by mutableStateOf<List<Photo>>(emptyList()); private set
    var personPhotos by mutableStateOf<List<Photo>>(emptyList()); private set
    var pc by mutableStateOf(PcLink.load(app)); private set // faces are found on this PC when it answers
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()

    // Scroll positions live here, not in the screens, so opening a picture and coming back keeps your place.
    val picturesGrid = LazyGridState()
    val peopleGrid = LazyGridState()
    private val personGrids = HashMap<Screen.Person, LazyGridState>()
    fun personGrid(s: Screen.Person) = personGrids.getOrPut(s) { LazyGridState() }

    /** The picture the viewer was showing when it closed: the grid underneath scrolls to it. */
    var returnTo: String? = null

    var offer by mutableStateOf<UndoOffer?>(null)

    init {
        viewModelScope.launch { app.scans.changes.collect { reload() } }
        reload()
    }

    fun startScan() = ScanService.start(app)
    fun stopScan() = app.scans.stop()
    fun observeGallery() = app.scans.observe { startScan() }
    fun unobserve(o: android.database.ContentObserver) = app.contentResolver.unregisterContentObserver(o)

    /** Keeps the code only if the PC answers to it; returns what went wrong, or null. */
    suspend fun pairPc(code: String): String? {
        val link = PcLink.parse(code) ?: return "Type the code pc_helper.py shows, like 192.168.1.5:8765/abcdefghjk"
        val error = withContext(Dispatchers.IO) {
            runCatching { PcClient(link).hello() }.exceptionOrNull()?.let { e ->
                if (e is PcRefused) "The PC didn't accept that code. Check it, or restart pc_helper.py."
                else "Couldn't reach the PC. Is pc_helper.py running, with the phone on the same Wi-Fi?"
            }
        }
        if (error == null) { PcLink.save(app, link); pc = link }
        return error
    }

    fun unpairPc() { PcLink.save(app, null); pc = null }

    fun reload() {
        viewModelScope.launch {
            val (p, s, a) = withContext(Dispatchers.IO) { Triple(app.people.list(), app.people.stats(), app.people.allPhotos()) }
            people = p; stats = s; all = a
            selectedPerson()?.let { loadPerson(it) }
        }
    }

    private fun selectedPerson(): Screen.Person? = stack.lastOrNull { it is Screen.Person } as Screen.Person?

    private fun loadPerson(s: Screen.Person) {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { app.people.photos(s.people, s.only) }
            if (selectedPerson() == s) personPhotos = found // a quicker, later request may already have answered
        }
    }

    /** How many pictures [ids] are in together (for the group bar), off the main thread. */
    suspend fun togetherCount(ids: List<Long>): Int = withContext(Dispatchers.IO) { app.people.photos(ids).size }

    /**
     * People named in [text], e.g. "alex, sam & ali" or "alex and sam": each part goes to the best
     * match among named people (exact name, then starts with, then contains). Parts matching nobody are skipped.
     */
    fun resolveNames(text: String): List<Long> = text.split(Regex("""\s*(?:,|&|\+|\band\b)\s*""", RegexOption.IGNORE_CASE))
        .map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { part ->
            val named = people.filter { it.name != null }
            (named.firstOrNull { it.name.equals(part, true) }
                ?: named.firstOrNull { it.name!!.startsWith(part, true) }
                ?: named.firstOrNull { it.name!!.contains(part, true) })?.id
        }.distinct()

    fun person(id: Long): Person? = people.firstOrNull { it.id == id }
    fun label(p: Person?) = p?.name ?: "Add name"
    private fun nameOf(id: Long) = person(id)?.name

    fun openPerson(ids: List<Long>, only: Boolean = false) {
        personPhotos = emptyList()
        val next = Screen.Person(ids, only)
        val s = screen
        if (s is Screen.Person) { personGrids.remove(s); stack[stack.lastIndex] = next } else stack += next
        loadPerson(next)
    }

    fun openViewer(ofPerson: Boolean, index: Int) { stack += Screen.Viewer(ofPerson, index) }
    fun viewerPhotos(ofPerson: Boolean) = if (ofPerson) personPhotos else all

    fun back(): Boolean {
        if (stack.size <= 1) return false
        (stack.removeAt(stack.lastIndex) as? Screen.Person)?.let { personGrids.remove(it) }
        selectedPerson()?.let { loadPerson(it) }
        return true
    }

    /** From the viewer straight to someone's pictures. */
    fun jumpToPerson(id: Long) {
        while (stack.size > 1) back()
        returnTo = null
        openPerson(listOf(id))
    }

    suspend fun facesIn(p: Photo): List<FaceInPhoto> = withContext(Dispatchers.IO) { app.people.facesIn(p.fingerprint) }
    suspend fun faceRef(faceId: Long) = withContext(Dispatchers.IO) { app.people.face(faceId) }
    suspend fun similar(id: Long) = withContext(Dispatchers.IO) { app.people.similar(id) }

    private fun edit(block: () -> Unit) {
        viewModelScope.launch { withContext(Dispatchers.IO) { block() }; reload() }
    }

    /** Someone else already called [name]: saving it combines the two (asked in the dialog). */
    fun namesake(id: Long, name: String): Person? = name.trim().takeIf { it.isNotEmpty() }?.let { n ->
        people.firstOrNull { it.id != id && it.name.equals(n, ignoreCase = true) }
    }

    fun rename(id: Long, name: String) {
        val same = namesake(id, name)
        if (same != null) merge(id, same.id) else edit { app.people.rename(id, name) }
    }

    fun merge(src: Long, dst: Long) {
        val was = screen as? Screen.Person
        val before = was?.people
        val after = before?.map { if (it == src) dst else it }?.distinct()
        val label = nameOf(dst)?.let { "Combined with $it" } ?: "Combined"
        viewModelScope.launch {
            val undo = withContext(Dispatchers.IO) { app.people.merge(src, dst) }
            reload()
            if (undo != null) offer = UndoOffer(label) {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { app.people.unmerge(undo) }
                    if (before != null && (screen as? Screen.Person)?.people == after) openPerson(before, was.only)
                    reload()
                }
            }
        }
        if (after != null) openPerson(after, was.only)
    }

    /** Deletes the face data of [ids] (after the phone's lock was checked) and leaves any screen showing them. */
    fun forget(ids: List<Long>) {
        val names = ids.mapNotNull { nameOf(it) }
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { app.people.forget(ids) }
            while ((screen as? Screen.Person)?.people?.any { it in ids } == true || (screen is Screen.Viewer && stack.size > 1)) back()
            reload()
            val who = when (names.size) { 0 -> if (ids.size == 1) "this person" else "${ids.size} people"; 1 -> names[0]; else -> "${names.size} people" }
            offer = UndoOffer("Deleted $n faces of $who", null)
        }
    }

    /** "Not them" for [person] on photo [p]. */
    fun notThem(p: Photo, person: Long) {
        val label = nameOf(person)?.let { "Removed from $it" } ?: "Removed from this person"
        viewModelScope.launch {
            val face = withContext(Dispatchers.IO) { app.people.faceOf(p.fingerprint, person)?.also { app.people.reject(it) } }
            reload()
            if (face != null) offer = UndoOffer(label) { edit { app.people.unreject(face, person) } }
        }
    }
}
