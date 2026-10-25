package tv.purple.xp

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * The PurpleTV settings menu (task: "PurpleTV menu button inside the profile page's settings").
 *
 * The whole tree is declared here as plain data and rendered with plain framework widgets, so it
 * carries no dependency on Twitch's own menu system. That matters: Twitch moved the top-level
 * settings screen to Jetpack Compose in 28.x, and its historic menu model classes
 * (ToggleMenuModel / DropDownMenuModel / SettingsDestination) are fully obfuscated in current
 * builds. Rendering our own screens sidesteps all of that.
 *
 * Labels and summaries mirror the original PurpleTV menu verbatim so the layout is familiar.
 * Entries whose feature isn't implemented yet are marked `done = false`: they still render, but
 * greyed out and inert, so the menu doubles as a visible roadmap. Ad-blocking entries are
 * deliberately omitted entirely, and so is anything that gets around paying: the original's ad
 * proxy and VODHunter (sub-only and deleted VODs) were removed for that reason.
 */
object PurpleMenu {

    // ---------------------------------------------------------------- palette
    private const val BG = 0xFF18181B.toInt()
    private const val BG_ROW = 0xFF1F1F23.toInt()
    private const val PURPLE = 0xFF9146FF.toInt()
    private const val TEXT = 0xFFEFEFF1.toInt()
    private const val TEXT_DIM = 0xFFADADB8.toInt()

    // ---------------------------------------------------------------- model
    sealed class Item {
        abstract val title: String
        abstract val summary: String
        /** false = feature not implemented yet; row renders greyed out and inert. */
        abstract val done: Boolean
    }

    class Toggle(
        override val title: String,
        override val summary: String = "",
        val key: String,
        val def: Boolean = false,
        override val done: Boolean = false
    ) : Item()

    class Drop(
        override val title: String,
        override val summary: String = "",
        val key: String,
        val options: List<String>,
        val def: Int = 0,
        override val done: Boolean = false
    ) : Item()

    class Slide(
        override val title: String,
        override val summary: String = "",
        val key: String,
        val min: Int,
        val max: Int,
        val def: Int,
        val step: Int = 1,
        override val done: Boolean = false
    ) : Item()

    class Sub(
        override val title: String,
        override val summary: String = "",
        val items: List<Item>,
        override val done: Boolean = true
    ) : Item()

    class Link(
        override val title: String,
        override val summary: String = "",
        val url: String,
        override val done: Boolean = true
    ) : Item()

    /** A row that opens a screen built in code rather than declared here. Needed wherever the
     *  content is dynamic — the keyword list is edited at runtime and can't be a static tree. */
    class Custom(
        override val title: String,
        override val summary: String = "",
        val onClick: (Context) -> Unit,
        override val done: Boolean = true
    ) : Item()

    // ---------------------------------------------------------------- tree
    //
    // Keys reuse the module's existing SharedPreferences names where a feature already works, so
    // nobody's current settings are reset. Not-yet-built entries keep the original PurpleTV keys.

    private val EMOTES = listOf(
        Toggle("BTTV emotes", "Enable support for BetterTTV (BTTV) emotes in the chat",
            Settings.KEY_SRC_BTTV, def = true, done = true),
        Toggle("Support for BTTV WEBP emotes",
            "Enable compatibility for displaying BTTV emotes in WEBP format in the chat",
            EmoteQuality.KEY_BTTV_WEBP, done = true),
        Toggle("FFZ emotes", "Enable support for FrankerFaceZ (FFZ) emotes in the chat",
            Settings.KEY_SRC_FFZ, def = true, done = true),
        Toggle("7TV emotes", "Enable support for 7TV emotes in the chat",
            Settings.KEY_SRC_SEVENTV, def = true, done = true),
        Toggle("7TV global emotes", "Enable support for 7TV global emotes in the chat",
            EmoteRepo.KEY_STV_GLOBAL, def = true, done = true),
        Toggle("Homies emotes", "Enable support for Homies emotes in the chat",
            EmoteRepo.KEY_HOMIES, done = true),
        Toggle("Twitch emotes in suggestions",
            "Include your own Twitch and subscriber emotes in the autocomplete strip",
            Settings.KEY_SRC_TWITCH, def = true, done = true),
        Toggle("Favorite emotes",
            "Pin emotes to a bar in the emote picker. Long-press an emote to add it",
            PickerFavorites.KEY_ENABLED, def = true, done = true),
        Toggle("7TV, BTTV and FFZ in the emote picker",
            "Adds this channel's and the global 7TV, BTTV and FFZ emotes as sections at the bottom of Twitch's emote picker",
            PickerSections.KEY, def = true, done = true),
        Toggle("Favorites per streamer",
            "Keep a separate favorites bar for each channel. Your shared favorites come back when this is off",
            Favorites.KEY_PER_CHANNEL, done = true),
        Drop("Emotes quality",
            "Adjust the quality of displayed emotes in the chat, which may impact performance",
            EmoteQuality.KEY, listOf("Low", "Medium", "Large"),
            def = EmoteQuality.DEFAULT, done = true)
    )

    private val BADGES = listOf(
        Toggle("FFZ badges", "Enable support for FrankerFaceZ (FFZ) badges in the chat",
            BadgeRepo.KEY_FFZ, def = true, done = true),
        Toggle("Chatterino badges", "Enable support for Chatterino badges in the chat",
            BadgeRepo.KEY_CHA, def = true, done = true),
        Toggle("Homies badges", "Enable support for Homies badges in the chat",
            BadgeRepo.KEY_HOMIES, done = true),
        Toggle("BTTV Badges", "Enable support for BetterTTV (BTTV) badges in the chat",
            BadgeRepo.KEY_BTTV, def = true, done = true),
        Toggle("DankChat badges", "Enable support for DankChat badges in the chat",
            BadgeRepo.KEY_DANK, def = true, done = true),
        Toggle("Chatsen badges", "Enable support for Chatsen badges in the chat",
            BadgeRepo.KEY_CHATSEN, def = true, done = true)
    )

    private val THIRD_PARTY = listOf(
        Sub("Emotes", items = EMOTES),
        Sub("Badges", items = BADGES),
        Toggle("Pronouns", "https://pronouns.alejo.io/", Pronouns.KEY, def = true, done = true)
    )

    private val CHAT = listOf(
        Toggle("Fast emote autocomplete",
            "Suggest emotes as you type, without needing a leading colon",
            Settings.KEY_AUTOCOMPLETE, def = true, done = true),
        Slide("Emote suggestion timeout",
            "How long the suggestion list stays up after you stop typing, in seconds",
            EmoteAutocomplete.KEY_TIMEOUT, min = 2, max = 30,
            def = EmoteAutocomplete.TIMEOUT_DEFAULT, done = true),
        Toggle("Vertical emote suggestions",
            "Show emote suggestions as a scrollable list above the keyboard instead of one row",
            EmoteAutocomplete.KEY_VERTICAL, def = true, done = true),
        Toggle("Pinned suggestions per streamer",
            "Keep separate pinned suggestions for each channel. Your shared pins come back when this is off",
            Pins.KEY_PER_CHANNEL, done = true),
        Drop("Holding an emote in suggestions",
            "What a long press on a suggested emote does",
            Pins.KEY_HOLD, listOf("Pins it to the top of suggestions",
                "Adds it to the favorites bar", "Both: 2 s pins, 5 s adds to favorites"),
            def = Pins.HOLD_DEFAULT, done = true),
        Toggle("Auto-claim channel points",
            "Claim bonus chests automatically, whether or not the chest is on screen",
            Settings.KEY_AUTO_POINTS, def = true, done = true),
        Slide("Landscape chat opacity", "Adjust the opacity of the chat window in landscape mode",
            Settings.KEY_CHAT_OPACITY, min = 0, max = 100, def = 100, done = true),
        Toggle("Alternating Background", "Display chat lines with alternating background colors",
            ChatAppearance.KEY_ALT_BG, done = true),
        Toggle("Vibrate when mentioned",
            "Enable this option to receive a vibration notification when you are mentioned in the chat",
            Mentions.KEY_ENABLED, done = true),
        Toggle("Do not clear chat when commanded to",
            "When a moderator clears chat, keep the messages on your screen. Timeouts and bans of single users still work",
            ChatGuards.KEY_NO_CLEAR, done = true),
        Toggle("Enable Mod Logs",
            "Only for channels where you are a moderator (or your own channel): a line in chat saying who timed out, banned, unbanned or deleted a message from whom, with the reason",
            ModLogs.KEY, def = true, done = true),
        Toggle("Disable Browser link disclaimer",
            "Turn off the display of twitch browser link disclaimers", LinkDisclaimer.KEY_ENABLED,
            done = true),
        Toggle("Hide chat header",
            "Enable this option to hide the chat header at the top of the chat",
            "hide_chat_header", done = true),
        Toggle("Hide message input", "Hide the message input field in the chat",
            "hide_message_input", done = true),
        Toggle("Hide message input on landscape",
            "Enable this option to hide the message input field when the device is in landscape mode",
            "auto_hide_message_input", done = true),
        Toggle("Hide bits button", "Hide the bits button within the message input field",
            "hide_bits_button", done = true),
        Toggle("Hide leaderboards",
            "Enable this option to hide the leaderboard panel at the top of the chat",
            "hide_leaderboards", done = true),
        Toggle("Disable HypeTrain", "Turn off the HypeTrain feature in the chat",
            "disable_hype_train", done = true),
        Toggle("One chat lurker",
            "Activating this option hides the UI for message input and sending in the \"One Chat\" mode",
            "one_chat_lurker", done = true),
        Drop("Timestamp Format", "Choose the preferred format for displaying timestamps",
            ChatLineStyle.KEY_TIMESTAMP, listOf(
                "Default", "12 Hour", "12 Hour with Seconds", "24 Hour", "24 Hour with Seconds",
                "Padded 12 Hour", "Padded 12 Hour with Seconds", "Padded 24 Hour",
                "Padded 24 Hour with Seconds"
            ), done = true),
        Drop("Username display style",
            "Change how usernames are displayed in chat when users have an international display name set",
            ChatLineStyle.KEY_NAME_STYLE,
            listOf("International Name (Username)", "International Name", "Username"),
            done = true),
        Drop("/me Style", "", ChatLineStyle.KEY_ME_STYLE,
            listOf("Disabled", "Colored", "Italic", "Italic + Colored"), done = true),
        // Unlike its neighbours this one is applied at the TextView rather than on the chat-line
        // model: the placeholder does not exist yet when the line is assembled. See
        // ChatLineStyle.reviveDeleted.
        Drop("Deleted messages", "Choose how deleted messages are handled in the chat",
            ChatLineStyle.KEY_DELETED,
            listOf("Default", "Mod", "Strikethrough", "Grey"), done = true),
        Drop("Pinned messages", "Choose the behavior of pinned messages in the chat",
            PinnedChat.KEY, listOf("Default", "Disabled", "30 sec."), done = true),
        Drop("Emote picker backspace", "What the backspace button in the emote picker deletes",
            PickerBackspace.KEY, listOf("Twitch's own", "Whole word or emote", "One letter"), def = PickerBackspace.MODE_WORD, done = true),
        Toggle("Raid card when you arrive with a raid",
            "Twitch moves raiders over a few seconds late, so they miss the raid card in the new chat. This adds it back",
            RaidCard.KEY, def = true, done = true),
        Toggle("Hide Drops notices", "Dismisses the \"Twitch Drops\" banner at the top of chat, like pressing its own Dismiss button",
            PinnedChat.KEY_DROPS, done = true),
        Slide("Chat font size", "Adjust the font size for chat messages",
            ChatAppearance.KEY_FONT_SIZE, min = 8, max = 24,
            def = ChatAppearance.FONT_SIZE_DEFAULT, done = true),
        Slide("Vibration duration",
            "Set the duration of the vibration in milliseconds for chat notifications",
            Mentions.KEY_DURATION, min = 10, max = 1000,
            def = Mentions.DURATION_DEFAULT, step = 10, done = true),
        Slide("Landscape chat width", "Width of the chat beside the video in landscape, in % of the screen",
            ChatTransparency.KEY_CHAT_WIDTH, min = 10, max = 50,
            def = ChatTransparency.CHAT_WIDTH_DEFAULT, done = true),
        Slide("Landscape chat width, expanded",
            "Width of the chat after you swipe it wider (swipe the chat left to expand, right to shrink it again), in %",
            ChatTransparency.KEY_CHAT_WIDTH_WIDE, min = ChatTransparency.CHAT_WIDTH_WIDE_MIN, max = ChatTransparency.CHAT_WIDTH_WIDE_MAX,
            def = ChatTransparency.CHAT_WIDTH_WIDE_DEFAULT, done = true),
        Toggle("Highlight mentions of me",
            "Tint the whole chat row when someone @mentions your account",
            ChatHighlight.KEY_MENTION_ENABLED, done = true),
        Custom("Highlighter",
            "Tint chat rows containing a word, or sent by a given user",
            { HighlightUi.showKeywords(it) }),
        Custom("Blacklist",
            "Hide chat messages containing a word, or sent by a given user",
            { BlacklistUi.show(it) }),
        Custom("Change @mention color",
            "Pick the tint used when you are mentioned",
            { HighlightUi.showMentionColor(it) })
    )

    private val PLAYER = listOf(
        Toggle("Hide \"Mature content\"",
            "Enabling this option removes the UI elements associated with the mature content warning",
            ViewHider.KEY_MATURE, done = true),
        Toggle("Use the classic stream screen",
            "Twitch 31.5 and newer open live streams on phones in a new screen that PurpleTV cannot reach (no 7TV, BTTV or FFZ emotes, badges or chat tweaks there). This keeps the classic one. Tablets always use it",
            ClassicTheatre.KEY, def = true, done = true),
        Toggle("Disable low latency",
            "Live streams play a few seconds further behind, but stutter and rebuffer less on a weak connection. Applies from the next stream you open.",
            PlayerStats.KEY_NO_LOW_LATENCY, done = true),
        Toggle("Turn off autoplay", "When a video or clip ends, don't play the next one by itself. The Up next card stays so you can still tap it",
            ChatGuards.KEY_NO_AUTOPLAY, done = true),
        Toggle("Show stats button",
            "A button in the player controls that shows quality, bitrate, frame rate, delay behind live, buffer and dropped frames over the video",
            PlayerStats.KEY_STATS, done = true),
        Toggle("Show refresh button",
            "Enable the display of a refresh button, allowing you to manually refresh the stream",
            RefreshButton.KEY, def = true, done = true),
        Toggle("Hide Unfollow button", "", "hide_unfollow_button", done = true),
        Toggle("Hide \"Follow/Subscribe\" button", "", "hide_fsb", done = true),
        Toggle("Hide \"Create clip button\"", "", "hide_player_create_clip_button", done = true),
        Toggle("Hide \"Share button\" for Live streams", "", "hide_player_live_share_button",
            done = true),
        Toggle("Disable chromecast", "", "disable_cast", done = true),
        Toggle("Show video filters button",
            "A button in the player controls with brightness, contrast, clarity, texture, saturation and vibrance for lives and VODs",
            VideoFilters.KEY_BUTTON, def = true, done = true),
        Slide("Player forward seek", "How far the VOD forward button jumps, in seconds", VodSeek.KEY_FWD,
            min = 5, max = 120, def = 30, step = 5, done = true),
        Slide("Player backward seek", "How far the VOD back button jumps, in seconds", VodSeek.KEY_BACK,
            min = 5, max = 120, def = 10, step = 5, done = true)
    )

    private val GESTURES = listOf(
        Toggle("Volume gesture control",
            "Swipe up or down on the right half of the landscape player to change the volume",
            PlayerGestures.KEY_VOLUME, done = true),
        Toggle("Brightness gesture control",
            "Swipe up or down on the left half of the landscape player to change the brightness",
            PlayerGestures.KEY_BRIGHTNESS, done = true)
    )

    private val VIEW = listOf(
        Toggle("Twitch Stories", "Show the row of Twitch Stories at the top of Home. Turn off to hide it",
            ViewHider.KEY_STORIES, def = true, done = true),
        Toggle("Hide Browse (search) tab", "Removes \"Browse\", the magnifying glass, from the navigation bar",
            ViewHider.KEY_BROWSE, done = true),
        Toggle("Open Home on Following",
            "Home starts on the Following tab instead of Live",
            HomeTabs.KEY_DEFAULT_FOLLOWING, def = true, done = true),
        Toggle("Hide Live & Clips tabs on Home",
            "Removes the Live and Clips tabs and keeps you on Following",
            HomeTabs.KEY, done = true),
        Toggle("Hide \"Categories\" on Following", "Removes the followed categories row from the Following page",
            FollowingSections.KEY_GAMES, done = true),
        Toggle("Hide \"Continue Watching\" on Following", "Removes the videos you started from the Following page",
            FollowingSections.KEY_RESUME, done = true),
        Toggle("Hide \"Offline Channels\" on Following", "Removes your offline channels from the Following page",
            FollowingSections.KEY_OFFLINE, done = true),
        Toggle("Show timer button", "A sleep timer in the player controls that pauses the stream after a chosen time",
            SleepTimer.KEY, done = true),
        Toggle("Hide \"Create\" (plus) button", "Removes the plus button from the navigation bar, in portrait and landscape",
            "hide_create_button", done = true)
    )

    private val DEV = listOf(
        Toggle("Fix push notifications",
            "Lets a patched Twitch register for push notifications, which it otherwise cannot because it is signed with a different key",
            PushFix.KEY, def = true, done = true)
    )

    private val INFO = listOf(
        Link("Source code", "github.com/alienware377/purpletv-revive",
            "https://github.com/alienware377/purpletv-revive"),
        Link("Report an issue", "Bug reports and feature requests",
            "https://github.com/alienware377/purpletv-revive/issues"),
        Link("7TV", "7tv.app", "https://7tv.app"),
        Link("BetterTTV", "betterttv.com", "https://betterttv.com"),
        Link("FrankerFaceZ", "frankerfacez.com", "https://frankerfacez.com")
    )

    private val UPDATES = listOf(
        Toggle("Check for updates",
            "Look for a new PurpleTV ReVive version on GitHub when Twitch starts, a few times a day at most, " +
                "and show what changed",
            UpdateChecker.KEY, def = true, done = true),
        Custom("Check for updates now",
            "You have PurpleTV ReVive " + BuildConfig.VERSION_NAME.substringBefore("-twitch") +
                " on Twitch " + TwitchVersion.name + ". It is made for Twitch " + TwitchVersion.SUPPORTED + ".",
            { UpdateChecker.checkNow(it) })
    )

    /** The master switch, and the only row shown while it is off. */
    private val MASTER = Toggle("PurpleTV enabled",
        "Turn this off to get the stock Twitch app back. Everything else stays as you set it. " +
            "Restart the Twitch app for the change to take effect.",
        Settings.KEY_ENABLED, def = true, done = true)

    private val ROOT = listOf(
        MASTER,
        Sub("Third Party Services", items = THIRD_PARTY),
        Sub("Chat", items = CHAT),
        Sub("Player", items = PLAYER),
        Sub("Gestures", items = GESTURES),
        Sub("View", items = VIEW),
        Sub("Dev", items = DEV),
        Custom("Show tips again",
            "Bring back the one-time tips you closed, like the video filters and pinned suggestions tips",
            { ctx ->
                for (k in TIP_KEYS) Settings.set(k, false)
                toast(ctx, "Tips will show again")
                ViewHider.reapply()
            }),
        Sub("Updates", items = UPDATES),
        Sub("Info", items = INFO)
    )

    /** Every "closed for good" tip, so they can all be brought back at once. */
    private val TIP_KEYS = listOf(VideoFilters.KEY_TIP, Pins.KEY_TIP_DISMISSED, EmoteHooks.KEY_LINK_TIP)

    // ---------------------------------------------------------------- entry point

    /**
     * While the master switch is off, none of the features are wired, so showing their rows would
     * offer settings that do nothing. The screen collapses to the switch itself -- which is also
     * the only way back, since every other entry point into this menu belongs to a feature that is
     * currently not running.
     */
    fun show(ctx: Context) = showScreen(
        ctx, "PurpleTV Settings",
        if (Settings.get(Settings.KEY_ENABLED, true)) ROOT else listOf(MASTER)
    )

    private fun showScreen(ctx: Context, title: String, items: List<Item>) {
        val dlg = Dialog(ctx, android.R.style.Theme_Material_NoActionBar)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        // Newer Twitch draws edge to edge, so keep the page clear of the status and navigation bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else @Suppress("DEPRECATION") {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.addView(header(ctx, title) { dlg.dismiss() })

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (item in items) list.addView(row(ctx, item))
        if (items.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = "Not implemented yet"
                setTextColor(TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(ctx, 16f), dp(ctx, 24f), dp(ctx, 16f), dp(ctx, 24f))
            })
        }
        root.addView(ScrollView(ctx).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dlg.setContentView(root)
        dlg.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.show()
    }

    private fun header(ctx: Context, title: String, onBack: () -> Unit): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BG_ROW)
            setPadding(dp(ctx, 8f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f))
            addView(TextView(ctx).apply {
                text = "←"
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(dp(ctx, 8f), 0, dp(ctx, 12f), 0)
                setOnClickListener { onBack() }
            })
            addView(TextView(ctx).apply {
                text = title
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
        }

    /** Build one settings row. Rows for unimplemented features are dimmed and inert. */
    private fun row(ctx: Context, item: Item): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16f), dp(ctx, 12f), dp(ctx, 16f), dp(ctx, 12f))
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(ctx).apply {
            text = item.title
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        titleRow.addView(titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        var valueView: TextView? = null
        when (item) {
            is Toggle -> titleRow.addView(android.widget.Switch(ctx).apply {
                isChecked = Settings.get(item.key, item.def)
                isEnabled = item.done
                setOnCheckedChangeListener { _, v ->
                    Settings.set(item.key, v)
                    if (item.key == Settings.KEY_ENABLED) {
                        // Hooks are installed once, at startup, so this cannot take effect until
                        // the app is started again. Say so rather than letting it look broken.
                        toast(ctx, if (v) "PurpleTV on — restart the Twitch app"
                                   else "PurpleTV off — restart the Twitch app")
                        return@setOnCheckedChangeListener
                    }
                    // The format is baked into each BTTV url at fetch time, like quality is.
                    if (item.key == EmoteQuality.KEY_BTTV_WEBP || item.key == EmoteRepo.KEY_HOMIES)
                        EmoteRepo.refetch()
                    // Both take effect on the live chat behind the menu.
                    ViewHider.reapply()
                    ChatAppearance.reapply()
                    HomeTabs.reapply()
                    // Switching a badge source on for the first time has to fetch its manifest;
                    // switching one off is free (forUser filters on the toggle every render).
                    if (v) {
                        if (item.key == Pronouns.KEY) Pronouns.loadAsync() else BadgeRepo.refresh()
                    }
                }
            })
            is Sub -> titleRow.addView(TextView(ctx).apply {
                text = "›"
                setTextColor(TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            })
            is Link -> titleRow.addView(TextView(ctx).apply {
                text = "↗"
                setTextColor(TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            is Drop -> valueView = TextView(ctx).apply {
                text = item.options.getOrElse(Settings.getInt(item.key, item.def)) { "" }
                setTextColor(if (item.done) PURPLE else TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }.also { titleRow.addView(it) }
            is Slide -> valueView = TextView(ctx).apply {
                text = Settings.getInt(item.key, item.def).toString()
                setTextColor(if (item.done) PURPLE else TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }.also { titleRow.addView(it) }
            is Custom -> titleRow.addView(TextView(ctx).apply {
                text = "›"
                setTextColor(TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            })
        }
        box.addView(titleRow)

        if (item.summary.isNotBlank()) {
            box.addView(TextView(ctx).apply {
                text = item.summary
                setTextColor(TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(ctx, 3f), dp(ctx, 40f), 0)
            })
        }

        // A slider needs its track inline, under the label.
        if (item is Slide) {
            box.addView(SeekBar(ctx).apply {
                max = (item.max - item.min) / item.step
                progress = (Settings.getInt(item.key, item.def) - item.min) / item.step
                isEnabled = item.done
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        val v = item.min + p * item.step
                        valueView?.text = v.toString()
                        if (fromUser) {
                            Settings.setInt(item.key, v)
                            // Live-preview the one slider that has a visible effect behind the menu.
                            if (item.key == Settings.KEY_CHAT_OPACITY ||
                                item.key == ChatTransparency.KEY_CHAT_WIDTH
                            ) ChatTransparency.reapply()
                            if (item.key == ChatAppearance.KEY_FONT_SIZE) ChatAppearance.reapply()
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            })
        }

        if (!item.done) {
            box.alpha = 0.38f
            box.setOnClickListener { toast(ctx, "\"${item.title}\" isn't implemented yet") }
        } else when (item) {
            is Sub -> box.setOnClickListener { showScreen(ctx, item.title, item.items) }
            is Link -> box.setOnClickListener {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { toast(ctx, "Couldn't open link") }
            }
            is Drop -> box.setOnClickListener { pickOption(ctx, item, valueView) }
            is Custom -> box.setOnClickListener { item.onClick(ctx) }
            else -> {}
        }
        return box
    }

    private fun pickOption(ctx: Context, item: Drop, valueView: TextView?) {
        val cur = Settings.getInt(item.key, item.def)
        android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(item.title)
            .setSingleChoiceItems(item.options.toTypedArray(), cur) { d, which ->
                Settings.setInt(item.key, which)
                valueView?.text = item.options[which]
                // Quality is baked into each CDN url at fetch time, so re-download at the new size.
                if (item.key == EmoteQuality.KEY && which != cur) {
                    EmoteRepo.refetch()
                    toast(ctx, "Re-downloading emotes at ${item.options[which].lowercase()} quality")
                }
                d.dismiss()
            }
            .show()
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
