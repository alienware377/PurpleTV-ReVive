package tv.purple.xp

import android.content.Context
import android.net.Uri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * Auto-claim Twitch channel-points bonus chests (task #7).
 *
 * Done the way standalone point-miners do it — at the GraphQL layer, NOT via the UI. The
 * floating "Claim Bonus" chest is a custom view that only exists while the player is
 * foregrounded and is awkward to anchor; instead we poll the CURRENT channel's available
 * claim through Twitch's own gql.twitch.tv gateway and POST the ClaimCommunityPoints mutation
 * when one is ready. Works regardless of orientation / whether the chest is on screen.
 *
 * Uses the persisted-query hashes the official web/mobile client uses, so the gateway accepts
 * them and returns the canonical response shape. Auth: the host's own OAuth token (read locally
 * via [EmoteRepo.authToken], sent ONLY to gql.twitch.tv, never logged). Multi-user / public-
 * release safe — nothing is hardcoded per user. Toggle: [Settings.KEY_AUTO_POINTS].
 */
object ChannelPoints {

    // Long-standing persisted-query hashes used by the official client (same ones the
    // community channel-points miners rely on).
    private const val H_CONTEXT = "1530a003a7d374b0380b79db0be0534f30ff46e61cffa2bc0e2468a909fbc024"
    private const val H_CLAIM = "46aaeebe02c99afdf4fc97c7c0cba964124bf6b0af229395f1f6d1feed05b3d0"

    /** Poll cadence. Bonus chests appear ~every 15 min and linger ~a few min; 30s catches them. */
    private const val POLL_MS = 30_000L

    @Volatile private var channelId: String? = null
    @Volatile private var channelLogin: String? = null
    @Volatile private var started = false
    /** Last claim id we successfully submitted, to avoid duplicate claim spam within a poll window. */
    @Volatile private var lastClaimId: String? = null
    /** One-shot: log the first successful context parse to confirm the GQL path end-to-end. */
    @Volatile private var probedOk = false

    /**
     * Channel each IVS player last loaded, and the channel of each player that is playing right now.
     * Filled by hooks on the player's own threads, so the miner thread never calls into the native
     * player (that could race Twitch releasing it and crash the app).
     */
    private val loaded = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val playing = Collections.synchronizedMap(WeakHashMap<Any, String>())
    /** Set once a known channel is seen playing, so hooks that never fire cannot switch mining off. */
    @Volatile private var tracking = false

    /** Called by [Channels] whenever chat connects to a channel. */
    fun onChannel(id: String?, login: String?) {
        if (!id.isNullOrBlank()) channelId = id
        if (!login.isNullOrBlank()) channelLogin = login
    }

    fun install(ctx: Context) {
        if (started) return
        started = true
        runCatching { trackPlayers(ctx.classLoader) }.onFailure { log("CP player tracking failed: $it") }
        Thread({
            while (true) {
                runCatching {
                    if (Settings.get(Settings.KEY_AUTO_POINTS)) tick(ctx)
                }.onFailure { log("CP tick error: $it") }
                runCatching { Thread.sleep(POLL_MS) }
            }
        }, "ptv-channel-points").apply { isDaemon = true }.start()
        log("CP auto-claim miner started (GQL poll every ${POLL_MS / 1000}s)")
    }

    /**
     * Watches the IVS player (its class keeps its real name in both Twitch versions) to know which
     * channel is actually playing. Native state codes: 2 = buffering, 3 = playing; ready (paused),
     * idle and ended all mean nothing is being watched on that player.
     */
    private fun trackPlayers(cl: ClassLoader) {
        val c = cl.loadClass("com.amazonaws.ivs.player.CorePlayerImpl")
        // Public load and loadSource only: the private native overloads of the same names stay unhooked.
        // loadSource is how a preloaded stream actually starts (the vertical theatre plays that way),
        // so without it the player on screen kept no login or a stale one. preload is left out on
        // purpose: it only fetches a stream ahead of time, on Twitch's separate preloader player that
        // never plays, and labelling a player from it could overwrite the channel it really plays.
        val onLoad = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                runCatching {
                    val login = loginOf(p.args.getOrNull(0))
                    if (login != null) loaded[p.thisObject] = login else loaded.remove(p.thisObject)
                }
            }
        }
        for (m in c.declaredMethods)
            if ((m.name == "load" || m.name == "loadSource") && Modifier.isPublic(m.modifiers)) XposedBridge.hookMethod(m, onLoad)
        XposedBridge.hookMethod(c.getMethod("handleStateChange", Int::class.javaPrimitiveType), object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                runCatching {
                    val state = p.args[0] as Int
                    val login = loaded[p.thisObject]
                    if ((state == 2 || state == 3) && login != null) { playing[p.thisObject] = login; tracking = true }
                    else playing.remove(p.thisObject)
                }
            }
        })
        // After the native release, so no late state callback from that player can add it back.
        XposedBridge.hookMethod(c.getMethod("release"), object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                runCatching { playing.remove(p.thisObject); loaded.remove(p.thisObject) }
            }
        })
    }

    /** Same pattern as RefreshButton: the vertical theatre and Picture-by-Picture use the "v2" address. */
    private val HLS = Regex("/api/(?:v2/)?channel/hls/([^/]+)\\.m3u8")

    /**
     * The live channel login from a stream address (".../api/channel/hls/<login>.m3u8", or the
     * ".../api/v2/..." form), or null. The argument is a Uri, LoadOptions or Source; the last two
     * both have a public getUri(). A released Source answers an empty Uri, which gives null here.
     */
    private fun loginOf(a0: Any?): String? {
        val uri = a0 as? Uri ?: runCatching { a0?.javaClass?.getMethod("getUri")?.invoke(a0) as? Uri }.getOrNull() ?: return null
        val path = uri.path ?: return null
        return HLS.find(path)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun isPlaying(login: String): Boolean =
        synchronized(playing) { playing.values.any { it.equals(login, ignoreCase = true) } }

    private fun tick(ctx: Context) {
        val login = channelLogin ?: return
        val id = channelId ?: return
        // channelLogin is never cleared, so without this the old channel kept being polled every
        // 30 s after its stream was closed. Background audio still counts as playing.
        if (tracking && !isPlaying(login)) return
        val token = EmoteRepo.authToken(ctx)
        if (token.isBlank()) return
        val claimId = fetchAvailableClaim(token, login) ?: return
        if (claimId == lastClaimId) return  // already handled this exact bonus
        log("CP bonus available on $login (claim=$claimId) — claiming")
        if (claim(token, id, claimId)) {
            lastClaimId = claimId
            log("CP ✓ claimed channel points on $login")
        } else {
            log("CP claim failed on $login")
        }
    }

    /** Returns the available claim id for [login], or null if none / error. */
    private fun fetchAvailableClaim(token: String, login: String): String? {
        val body = JSONObject()
            .put("operationName", "ChannelPointsContext")
            .put("variables", JSONObject().put("channelLogin", login))
            .put("extensions", JSONObject().put("persistedQuery",
                JSONObject().put("version", 1).put("sha256Hash", H_CONTEXT)))
            .toString()
        val resp = EmoteRepo.gqlPost(token, body) ?: return null
        val json = runCatching { JSONObject(resp) }.getOrNull() ?: return null
        json.optJSONArray("errors")?.let {
            if (it.length() > 0) {
                log("CP context error: ${it.optJSONObject(0)?.optString("message")}")
                return null
            }
        }
        val cp = json.optJSONObject("data")
            ?.optJSONObject("community")
            ?.optJSONObject("channel")
            ?.optJSONObject("self")
            ?.optJSONObject("communityPoints")
        if (cp == null) {
            log("CP context: communityPoints null for $login (data=${json.optJSONObject("data") != null}) — query shape mismatch?")
            return null
        }
        if (!probedOk) { probedOk = true; log("CP context OK for $login: balance=${cp.optInt("balance", -1)} (GQL path verified)") }
        val claim = cp.optJSONObject("availableClaim") ?: return null
        return claim.optString("id").ifBlank { null }
    }

    /** POST the claim mutation. Returns true on success. */
    private fun claim(token: String, channelId: String, claimId: String): Boolean {
        val input = JSONObject().put("channelID", channelId).put("claimID", claimId)
        val body = JSONObject()
            .put("operationName", "ClaimCommunityPoints")
            .put("variables", JSONObject().put("input", input))
            .put("extensions", JSONObject().put("persistedQuery",
                JSONObject().put("version", 1).put("sha256Hash", H_CLAIM)))
            .toString()
        val resp = EmoteRepo.gqlPost(token, body) ?: return false
        val json = runCatching { JSONObject(resp) }.getOrNull() ?: return false
        json.optJSONArray("errors")?.let {
            if (it.length() > 0) { log("CP claim error: ${it.optJSONObject(0)?.optString("message")}"); return false }
        }
        // Success = data.claimCommunityPoints present with no nested error.
        val node = json.optJSONObject("data")?.optJSONObject("claimCommunityPoints")
        val err = node?.optJSONObject("error")
        return err == null
    }
}
