package app.glimpse.people

import app.glimpse.faces.Vec
import java.util.stream.IntStream

/** One stored face. [image] is the photo's content fingerprint, so copies of a photo count once. */
class FaceRow(val id: Long, val image: String, val emb: FloatArray, val person: Long?, val score: Float, val px: Float)

class ClusterInput(
    val faces: List<FaceRow>,
    val kept: Set<Long>, // people the user named, merged or corrected: never dissolved
    val unkept: Set<Long>,
    val rejects: Set<Pair<Long, Long>>, // (face id, person id) pairs from "Not them"
)

class ClusterResult(val assign: Map<Long, Long?>, val covers: Map<Long, Long>)

/** Faces -> people. A straight port of people.People._cluster on the desktop, with the same thresholds. */
object Clusterer {
    // ArcFace (w600k_r50) similarities. On LFW same-person pairs score >= 0.41, different people <= 0.27.
    const val ASSIGN_SIM = 0.40f // attach a face to an existing person's average face
    const val LINK_SIM = 0.50f // link two unassigned faces when forming new people (stricter: avoids chaining)
    const val WEAK_SIM = 0.50f // blurry/small faces only join a person when they are this close
    const val MERGE_SIM = 0.50f // two groups whose average faces are this alike are one person split in two
    const val SUGGEST_SIM = 0.35f // ...and from here up we ask the user ("Same person?")
    const val GOOD_SCORE = 0.65f
    const val GOOD_PX = 32f // detector confidence / face size (px) for a "clear" face
    const val SHOW_SCORE = 0.75f // unassigned faces are only shown when certain

    fun cluster(input: ClusterInput, newPerson: () -> Long): ClusterResult {
        val rows = input.faces
        val n = rows.size
        if (n == 0) return ClusterResult(emptyMap(), emptyMap())
        val e = rows.map { it.emb }
        val old = LongArray(n) { rows[it].person ?: -1 }
        val clear = BooleanArray(n) { rows[it].score >= GOOD_SCORE && rows[it].px >= GOOD_PX }
        val assign = LongArray(n) { if (old[it] in input.kept) old[it] else -1 }

        fun members(p: Long) = (0 until n).filter { assign[it] == p }
        fun centroid(p: Long): FloatArray? {
            val m = members(p)
            if (m.isEmpty()) return null
            val c = FloatArray(e[0].size)
            for (i in m) for (k in c.indices) c[k] += e[i][k]
            for (k in c.indices) c[k] /= m.size
            return Vec.normalize(c)
        }

        fun attach(centroids: Map<Long, FloatArray>) {
            if (centroids.isEmpty()) return
            val pids = centroids.keys.toList()
            val free = (0 until n).filter { assign[it] == -1L }
            for (i in free) {
                val sims = FloatArray(pids.size) { Vec.dot(e[i], centroids.getValue(pids[it])) }
                val need = if (clear[i]) ASSIGN_SIM else WEAK_SIM
                for (j in sims.indices.sortedByDescending { sims[it] }) {
                    if (sims[j] < need) break
                    if ((rows[i].id to pids[j]) !in input.rejects) { assign[i] = pids[j]; break }
                }
            }
        }

        // 1) faces that look like an existing (kept) person join them
        attach(input.kept.mapNotNull { p -> centroid(p)?.let { p to it } }.toMap())

        // 2) group the remaining clear faces: link pairs above LINK_SIM, take connected components
        val free = (0 until n).filter { assign[it] == -1L && clear[it] }.toIntArray()
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var a = x
            while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }
            return a
        }
        val links = arrayOfNulls<IntArray>(free.size)
        IntStream.range(0, free.size).parallel().forEach { ai ->
            val a = e[free[ai]]
            val hit = ArrayList<Int>()
            for (bi in ai + 1 until free.size) if (Vec.dot(a, e[free[bi]]) >= LINK_SIM) hit += free[bi]
            links[ai] = hit.toIntArray()
        }
        for (ai in free.indices) for (b in links[ai]!!) parent[find(free[ai])] = find(b)
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in free) groups.getOrPut(find(i)) { ArrayList() } += i
        groups.values.removeAll { g -> g.map { rows[it].image }.toSet().size < 2 } // seen in 2+ photos

        // Reuse the previous id of each group when most of its faces had one (stable links in the UI).
        val used = LinkedHashSet<Long>()
        val newIds = LinkedHashMap<Int, Long>()
        for ((root, mem) in groups.entries.sortedByDescending { it.value.size }) {
            val prev = mem.map { old[it] }.filter { it in input.unkept && it !in used }
            val pid = prev.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: newPerson()
            used += pid
            newIds[root] = pid
            for (m in mem) assign[m] = pid
        }

        // 3) one person often ends up as several groups: merge groups whose average faces clearly agree.
        val groupIds = newIds.values.toList()
        if (groupIds.isNotEmpty()) {
            val cents = groupIds.associateWith { centroid(it)!! }
            val keptC = input.kept.mapNotNull { p -> centroid(p)?.let { p to it } }.toMap()
            val rejected = HashMap<Long, MutableSet<Long>>()
            for ((f, p) in input.rejects) rejected.getOrPut(p) { HashSet() } += f
            val into = groupIds.associateWith { it }.toMutableMap()
            fun root(x: Long): Long { var p = x; while (into.getValue(p) != p) p = into.getValue(p); return p }

            for (p in groupIds.sortedByDescending { members(it).size }) {
                val best = keptC.maxByOrNull { Vec.dot(cents.getValue(p), it.value) } ?: continue
                val mem = members(p).map { rows[it].id }.toSet()
                if (Vec.dot(cents.getValue(p), best.value) >= MERGE_SIM && mem.none { it in (rejected[best.key] ?: emptySet()) }) {
                    for (i in 0 until n) if (assign[i] == p) assign[i] = best.key // joins a person the user named/fixed
                    used -= p
                }
            }
            val live = groupIds.filter { p -> (0 until n).any { assign[it] == p } }
            for (i in live.indices) for (j in i + 1 until live.size) {
                val ra = root(live[i]); val rb = root(live[j])
                if (ra != rb && Vec.dot(cents.getValue(live[i]), cents.getValue(live[j])) >= MERGE_SIM) into[rb] = ra
            }
            for (p in live) {
                val r = root(p)
                if (r != p) { for (i in 0 until n) if (assign[i] == p) assign[i] = r; used -= p }
            }
            val alive = (0 until n).map { assign[it] }.toSet()
            newIds.entries.removeAll { root(it.value) !in alive }
            for (k in newIds.keys) newIds[k] = root(newIds.getValue(k))
        }

        // 4) blurry/small leftovers join whichever new person they clearly resemble
        attach(newIds.values.toSet().mapNotNull { p -> centroid(p)?.let { p to it } }.toMap())

        val covers = HashMap<Long, Long>()
        for (pid in input.kept + used) {
            val m = members(pid)
            if (m.isEmpty()) continue
            val best = m.maxBy { rows[it].score * minOf(rows[it].px, 200f) }
            covers[pid] = rows[best].id
        }
        return ClusterResult((0 until n).associate { rows[it].id to assign[it].takeIf { a -> a != -1L } }, covers)
    }
}
