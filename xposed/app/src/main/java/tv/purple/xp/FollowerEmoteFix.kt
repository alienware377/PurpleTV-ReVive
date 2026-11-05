package tv.purple.xp

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.WeakHashMap

/**
 * Keeps a followed channel's follower emotes usable in the emote picker.
 *
 * The picker unlocks the channel's follower emotes only if the channel's follower set is in its
 * emote repository's list of owned sets. Only AvailableEmoteSets(channel) has that set; the
 * general UserEmotes list never does. The chat's repository fetches AvailableEmoteSets when the
 * channel connects, but the media picker sheet builds a fresh repository on every open and never
 * starts that fetch, so it only ever gets UserEmotes and every follower emote shows locked with a
 * Follow button. Its "frequently used" row also drops the channel's follower emotes for the same
 * reason. The chat's repository can lose the same way when UserEmotes lands after the channel list.
 *
 * So the channel's own sets are remembered from every AvailableEmoteSets answer, and merged into
 * any UserEmotes answer for a repository whose channel is known: from the channel fetch, or from
 * the picker asking for that channel's follower emotes. A picker that opened before the channel's
 * list arrived, or before the user followed, is refreshed once the list comes in.
 */
object FollowerEmoteFix {

    private val REPO get() = TwitchVersion.pick("qqf", "oyf")      // emote repository, same as FrequentEmotes
    private val WRITER get() = TwitchVersion.pick("mqf", "kyf")    // its doOnSuccess lambda: a=2 channel sets, a=3 UserEmotes
    private val FETCH get() = TwitchVersion.pick("jsf", "i0g")     // a=2 runs AvailableEmoteSets for Pair(enabled, Optional<Tuid>)
    private val LOADER get() = TwitchVersion.pick("lsf", "j0g")    // owns the repository in field c
    private val OWNED_SET get() = TwitchVersion.pick("tqf", "ryf") // OwnerEmoteSet: a = owner, b = set id
    private val OWNER get() = TwitchVersion.pick("xnf", "wvf")     // owner: a = channel id (int on 31.1.2, Tuid on 31.7.0)
    private const val CHANNEL_SETS = 2
    private const val USER_SETS = 3

    private val lock = Any()
    private val fetchChannel = WeakHashMap<Any, String>()   // repository -> channel its AvailableEmoteSets is for
    private val pickerChannel = WeakHashMap<Any, String>()  // repository -> channel it asked follower emotes for
    private val ownSets = HashMap<String, List<Any>>()      // channel -> its sets from the latest AvailableEmoteSets
    private val waiting = WeakHashMap<Any, Pair<Any, List<*>>>() // repository -> (writer, UserEmotes list) still locked

    fun install(lp: LoadPackageParam) {
        val cl = lp.classLoader
        val repo = XposedHelpers.findClass(REPO, cl)
        val writer = XposedHelpers.findClass(WRITER, cl)
        val fetch = XposedHelpers.findClass(FETCH, cl)
        val ownedSet = XposedHelpers.findClass(OWNED_SET, cl)
        val caseF = writer.getDeclaredField("a").apply { isAccessible = true }
        val repoF = writer.declaredFields.single { it.type == repo }.apply { isAccessible = true }
        val fetchCaseF = fetch.getDeclaredField("a").apply { isAccessible = true }
        val fetchLoaderF = fetch.getDeclaredField("b").apply { isAccessible = true }
        val loaderRepoF = XposedHelpers.findClass(LOADER, cl).getDeclaredField("c").apply { isAccessible = true }
        val ownerF = ownedSet.getDeclaredField("a").apply { isAccessible = true }
        val ownerIdF = XposedHelpers.findClass(OWNER, cl).getDeclaredField("a").apply { isAccessible = true }
        val optValF = XposedHelpers.findClass("tv.twitch.android.util.Optional", cl).getDeclaredField("a").apply { isAccessible = true }

        fun ownedBy(set: Any?, channel: String) = set != null && set.javaClass === ownedSet &&
            ownerF.get(set)?.let { ownerIdF.get(it)?.toString() } == channel

        XposedHelpers.findAndHookMethod(fetch, "invoke", Any::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (fetchCaseF.getInt(p.thisObject) != CHANNEL_SETS) return
                // Twitch's own kotlin.Pair (R8 names its fields a and b), not the module's copy.
                val pair = p.args[0] ?: return
                val channel = runCatching { pair.javaClass.getDeclaredField("b").apply { isAccessible = true }.get(pair) }
                    .getOrNull()?.let { optValF.get(it) }?.toString() ?: return
                val owner = fetchLoaderF.get(p.thisObject)?.let { loaderRepoF.get(it) } ?: return
                synchronized(lock) { fetchChannel[owner] = channel }
            }
        })

        XposedHelpers.findAndHookMethod(repo, "d", XposedHelpers.findClass("tv.twitch.android.models.Tuid", cl), object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val channel = p.args[0]?.toString() ?: return
                synchronized(lock) { pickerChannel[p.thisObject] = channel }
            }
        })

        XposedHelpers.findAndHookMethod(writer, "invoke", Any::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val case = caseF.getInt(p.thisObject)
                if (case != CHANNEL_SETS && case != USER_SETS) return
                val list = p.args[0] as? List<*> ?: return
                val owner = repoF.get(p.thisObject) ?: return
                if (case == CHANNEL_SETS) {
                    val channel = synchronized(lock) { fetchChannel[owner] } ?: return
                    val own = list.filter { ownedBy(it, channel) }.map { it!! }
                    val refresh = synchronized(lock) {
                        waiting.remove(owner)  // its own channel list replaces the UserEmotes one
                        ownSets[channel] = own
                        if (own.isEmpty()) emptyList()
                        else waiting.entries.filter { channelOf(it.key) == channel }.map { it.value }
                            .also { done -> waiting.values.removeAll(done.toSet()) }
                    }
                    // Push the channel's sets into pickers that opened before they were known.
                    for ((w, userSets) in refresh) {
                        runCatching { XposedBridge.invokeOriginalMethod(p.method, w, arrayOf(merged(userSets, own))) }
                            .onSuccess { log("FX refreshed an open picker with ${own.size} channel sets") }
                            .onFailure { log("FX picker refresh failed: $it") }
                    }
                    return
                }
                synchronized(lock) {
                    val channel = channelOf(owner) ?: return
                    val own = ownSets[channel]
                    if (own.isNullOrEmpty()) { waiting[owner] = p.thisObject to list; return }
                    waiting.remove(owner)
                    val have = list.filter { ownedBy(it, channel) }.mapNotNullTo(HashSet()) { setId(it) }
                    val extra = own.filter { setId(it) !in have }
                    if (extra.isEmpty()) return
                    // In place when possible: the picker's "frequently used" row reads this same list.
                    @Suppress("UNCHECKED_CAST")
                    val inPlace = runCatching { (list as MutableList<Any?>).addAll(extra) }.isSuccess
                    if (!inPlace) p.args[0] = merged(list, extra)
                    log("FX added ${extra.size} channel emote sets to UserEmotes for $channel")
                }
            }
        })
        log("FX follower emote fix installed")
    }

    private fun channelOf(repo: Any): String? = fetchChannel[repo] ?: pickerChannel[repo]

    private fun merged(list: List<*>, extra: List<Any>): ArrayList<Any?> {
        val ids = list.mapNotNullTo(HashSet()) { setId(it) }
        return ArrayList<Any?>(list.size + extra.size).apply { addAll(list); addAll(extra.filter { setId(it) !in ids }) }
    }

    /** An emote set's id: its b() getter in both Twitch versions. */
    private fun setId(set: Any?): String? = set?.let {
        runCatching { it.javaClass.getMethod("b").invoke(it) as? String }.getOrNull()
    }
}
