package tv.purple.xp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A chat for one channel, drawn by us from DualIrc lines: a list and a message box.
 *
 * Twitch's own chat only ever shows the channel its player is on, so the two-stream screen draws
 * both of its chats itself, from one IRC connection. It aims to read like Twitch's chat: badges,
 * name colours made readable on the dark background, emotes in the text (Twitch's by id, 7TV, BTTV
 * and FFZ by word), grey system lines, and the PurpleTV chat settings (font size, timestamps, name
 * style, /me style, deleted messages, highlights, blacklist).
 *
 * Lines are kept as data and turned into text when a row shows them. A picture or a badge list
 * that arrives later then only costs rebuilding the rows on screen, and nothing keeps a callback
 * per row. Rows are plain TextViews with ImageSpans that hold the picture itself, so the module's
 * animation driver runs animated emotes in them and pauses them whenever the pane is hidden, the
 * same as in Twitch's chat. Shared pictures (BadgeRepo's are also on Twitch's own chat lines) are
 * drawn scaled through the span and never resized.
 *
 * Main thread only.
 */
class DualChatPane(ctx: Context, val login: String) : LinearLayout(ctx) {

    companion object {
        /** The tag on every chat row. The app-wide emote and link touch wiring leaves these rows
         *  alone: they handle their own taps, and the list keeps every other gesture. */
        const val ROW_TAG = "ptv_dual_row"

        const val MAX_LINES = 300
        /** New lines are added to the list at most this often: a busy chat sends dozens a second. */
        const val BATCH_MS = 150L
        const val MAX_LENGTH = 500
        /** How tall a GIF is drawn in a chat line. */
        const val GIF_DP = 72f
        /** Below this pane width only the channel points button stays next to the message box. */
        const val TIGHT_DP = 340f
        const val POINTS_MS = 15_000L
        const val BANNER_FIRST_MS = 2_000L
        const val BANNER_RETRY_MS = 3_000L
        const val BANNER_POLL_MS = 30_000L
        const val BANNER_HYPE_MS = 10_000L
        const val BANNER_TURN_MS = 7_000L
        const val KEY_SEND_TIP = "dual_send_tip_dismissed"
        private var tipPane: java.lang.ref.WeakReference<DualChatPane>? = null
        /** How long one of our own lines waits for Twitch's answer before none is matched to it. */
        const val ANSWER_MS = 10_000L
        /** Missing badges are asked for again this often. TwitchBadges repeats a request only after
         *  more than a minute, so this is a little over one. */
        const val BADGE_RETRY_MS = 61_000L

        const val BG = 0xFF18181B.toInt()
        const val TEXT = 0xFFEFEFF1.toInt()
        const val MUTED = 0xFFADADB8.toInt()
        const val HINT = 0xFF8A8A96.toInt()
        const val DELETED = 0xFF6E6E78.toInt()
        const val LINK = 0xFFBF94FF.toInt()
        const val PURPLE = 0xFF9147FF.toInt()
        const val LINE = 0xFF2F2F35.toInt()
        const val FIELD = 0xFF26262C.toInt()
        const val FIELD_EDGE = 0xFF3A3A3D.toInt()
        const val ALT_TINT = 0x14FFFFFF
        const val NOTICE_TINT = 0x1F9147FF
        const val HIGHLIGHT_TINT = 0x339147FF

        /** A one-character carrier for a badge picture; it never matches an emote name. */
        const val NBSP = "\u00A0"
        const val EXCL = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        /** Same order as the "Timestamp format" choice, which ChatLineStyle reads too. */
        val TIME_PATTERNS = arrayOf("", "h:mm", "h:mm:ss", "H:mm", "H:mm:ss", "hh:mm", "hh:mm:ss", "HH:mm", "HH:mm:ss")

        /** A bare domain must start and end on a word edge: "ok.cool" is not a link to ok.co. */
        val URL = Regex("(?i)(?:https?://\\S+|www\\.\\S+|(?<![\\p{L}\\p{N}_@-])[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\." +
            "(?:com|net|org|tv|gg|io|co|me|ly|be|app|dev|live|link|xyz|info|uk|de|fr|jp)(?![\\p{L}\\p{N}_-])(?:/\\S*)?)")
        /** Blacklist word separators, as Blacklist splits a message. */
        val SPLIT = Regex("[\\s,.!?-]+")
        const val WORD_CHAR = "[\\p{L}\\p{N}_]"
        /** The emote id in a Twitch picture address; other providers' emotes have no Twitch card. */
        val TWITCH_EMOTE = Regex("static-cdn\\.jtvnw\\.net/emoticons/v2/([^/]+)/")
    }

    /** One chat line as it arrived. [text] is the action's own words on a /me line. */
    private class Line(
        val seq: Long,
        val text: String,
        val system: Boolean = false,
        /** On our own lines, set once Twitch's USERSTATE confirms the line and names its id. */
        var id: String? = null,
        val userId: String? = null,
        val login: String? = null,
        val display: String? = null,
        val color: String? = null,
        val badges: List<Pair<String, String>> = emptyList(),
        val action: Boolean = false,
        val emotes: List<EmoteRange> = emptyList(),
        val ts: Long = System.currentTimeMillis(),
        val own: Boolean = false,
        val header: String? = null,
        val tint: Int = 0,
        val replyTo: String? = null,
        val replyBody: String? = null,
        val gifs: List<GifRange> = emptyList(),
    ) {
        var deleted = false
        var built: CharSequence? = null
        /** The pane's [allVer] and [picVer] when [built] was made. */
        var builtVer = -1
        var builtPic = -1
        /** Some picture was not here yet when [built] was made. */
        var waiting = false
        /** Row colour worked out while building: a mention or a highlighted word. */
        var mark = 0
    }

    // ---- state (main thread) ----

    private val main = Handler(Looper.getMainLooper())
    private val items = ArrayList<Line>()
    private val pending = ArrayList<Line>()
    /** Our own lines Twitch has not answered yet, oldest first, with when each was sent. */
    private val unconfirmed = ArrayDeque<Pair<Line, Long>>()
    /** The line under the finger when it last went down, for long-press copy. */
    private var pressed: Line? = null
    private val cardKey = "ptv_vc_${System.identityHashCode(this)}"
    /** The line the next message answers, while the reply bar is up. */
    private var replying: Line? = null
    private var seq = 0L
    private var started = false
    private var stoppedByDetach = false
    private var channelId: String? = null
    private var connected = false
    private var welcomed = false
    private var lost = false
    @Volatile private var ownId: String? = null

    private var flushQueued = false
    private var lastFlush = 0L
    /** Bumped when every line must be built again (settings, badge or emote lists arrived). */
    private var allVer = 0
    /** Bumped when a picture arrives: only lines still waiting for one are built again. */
    private var picVer = 0
    /** Set while building a line when a picture it needs is not here yet. */
    private var waitingNow = false

    // ---- settings, read again whenever Settings.rev moves ----

    private var seenRev = -1
    private var textPx = 0f
    private var emotePx = 0
    private var badgePx = 0
    private var gapPx = 0
    private var alt = false
    private var timeFormat: SimpleDateFormat? = null
    private var nameMode = 0
    private var meMode = 0
    private var delMode = 0
    private var mentionColor = ChatHighlight.MENTION_COLOR_DEFAULT
    private var highlights = Highlights.NONE
    private var hide = HideRules.NONE
    private var deletedText: String? = null
    private var mentionFor: String? = null
    private var mentionRe: Regex? = null

    // ---- views ----

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].seq
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? RowView ?: RowView(context, { r, t -> onTap(r, t) }, { pressed = it })
            runCatching { bind(row, items[position]) }.onFailure { log("DC bind failed: ${it.javaClass.simpleName}") }
            return row
        }
    }

    private val list = ListView(ctx).apply {
        divider = null
        dividerHeight = 0
        selector = ColorDrawable(Color.TRANSPARENT)
        cacheColorHint = Color.TRANSPARENT
        // Follows new lines only while the newest one is in view; scrolled up, it stays put.
        transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL
        isStackFromBottom = true
        isVerticalScrollBarEnabled = false
        isFocusable = false
        itemsCanFocus = false
        setPadding(0, dp(4f), 0, dp(4f))
        clipToPadding = false
    }

    private val pill = TextView(ctx).apply {
        text = I18n.t("More messages below")
        contentDescription = I18n.t("More messages below")
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(16f).toFloat(); setColor(0xF03A3A3D.toInt()) }
        setPadding(dp(12f), dp(6f), dp(14f), dp(6f))
        compoundDrawablePadding = dp(6f)
        setCompoundDrawables(ArrowDown().apply { setBounds(0, 0, dp(12f), dp(12f)) }, null, null, null)
        elevation = dp(3f).toFloat()
        visibility = View.GONE
        setOnClickListener { jumpToEnd() }
    }

    private val fieldBg = GradientDrawable().apply {
        cornerRadius = dp(6f).toFloat()
        setColor(FIELD)
        setStroke(dp(1f), FIELD_EDGE)
    }

    private val sendIcon = SendIcon()

    private val input = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isSingleLine = true
        // No full-screen text box in landscape: the chats are what the box is for.
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        filters = arrayOf(InputFilter.LengthFilter(MAX_LENGTH))
        setTextColor(TEXT)
        setHintTextColor(HINT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = fieldBg
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        hint = I18n.t("Send a message")
        setOnEditorActionListener { _, id, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER
            if (id == EditorInfo.IME_ACTION_SEND || enter) {
                if (ev == null || ev.action == KeyEvent.ACTION_DOWN) send()
                true
            } else false
        }
        setOnFocusChangeListener { _, has -> fieldBg.setStroke(dp(1f), if (has) PURPLE else FIELD_EDGE) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { sendIcon.setActive(!s.isNullOrBlank()) }
        })
    }

    private val sendBtn = ImageView(ctx).apply {
        setImageDrawable(sendIcon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        contentDescription = I18n.t("Send message")
        isFocusable = false
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
        setOnClickListener { send() }
        setOnLongClickListener { showSendMenu(); true }
    }

    // ---- pinned messages, hype train and drops, in one thin line above the chat ----

    private var banners: List<DualBanners.Item> = emptyList()
    private val closedBanners = HashSet<String>()
    private var bannerAt = 0
    private var bannerOpen = false
    private var bannerGen = 0

    private val bannerTag = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(LINK)
        isSingleLine = true
        setPadding(0, 0, dp(6f), 0)
    }
    private val bannerText = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(TEXT)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val bannerBar = object : LinearLayout(ctx) {
        val line = android.graphics.Paint().apply { color = PURPLE }
        var ratio: Float? = null
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            ratio?.let { canvas.drawRect(0f, height - dp(2f).toFloat(), width * it, height.toFloat(), line) }
        }
    }.apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10f), dp(3f), dp(2f), dp(3f))
        setBackgroundColor(FIELD)
        visibility = View.GONE
        setOnClickListener { bannerOpen = !bannerOpen; showBanner() }
        addView(bannerTag, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bannerText, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(ctx).apply {
            setImageDrawable(DualIcons.Close())
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            contentDescription = I18n.t("Close")
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
            setOnClickListener { closeBanner() }
        }, LayoutParams(dp(24f), dp(24f)))
    }

    private fun visibleBanners() = banners.filter { it.id !in closedBanners }

    private fun showBanner() {
        val list = visibleBanners()
        if (list.isEmpty() || compact) { bannerBar.visibility = View.GONE; return }
        val b = list[bannerAt.mod(list.size)]
        bannerTag.text = when (b.kind) {
            DualBanners.Kind.HYPE -> I18n.t("Hype Train level ${b.level}")
            DualBanners.Kind.PINNED -> I18n.t("Pinned")
            DualBanners.Kind.DROPS -> I18n.t("Drops")
        }
        bannerText.text = b.text
        bannerText.maxLines = if (bannerOpen) 5 else 1
        bannerBar.ratio = b.progress
        bannerBar.visibility = View.VISIBLE
        bannerBar.invalidate()
    }

    private fun closeBanner() {
        val list = visibleBanners()
        if (list.isEmpty()) return
        closedBanners.add(list[bannerAt.mod(list.size)].id)
        bannerOpen = false
        showBanner()
    }

    /** More than one banner: they take turns, unless one is opened to be read. */
    private val bannerTurn: Runnable = object : Runnable {
        override fun run() {
            if (!started) return
            if (!bannerOpen && visibleBanners().size > 1) { bannerAt++; showBanner() }
            main.postDelayed(this, BANNER_TURN_MS)
        }
    }

    private val bannerPoll: Runnable = object : Runnable {
        override fun run() {
            if (!started) return
            val id = channelId
            if (id.isNullOrEmpty() || !isShown) { main.postDelayed(this, BANNER_RETRY_MS); return }
            val my = ++bannerGen
            val app = context.applicationContext ?: context
            Thread({
                val raw = runCatching { EmoteRepo.gqlPost(EmoteRepo.authToken(app), DualBanners.body(id)) }.getOrNull()
                val got = raw?.let { DualBanners.parse(it, System.currentTimeMillis()) }
                main.post {
                    if (!started || my != bannerGen) return@post
                    if (got != null) { banners = got; showBanner() }
                    val hype = got?.any { it.kind == DualBanners.Kind.HYPE } == true
                    main.postDelayed(this, if (hype) BANNER_HYPE_MS else BANNER_POLL_MS)
                }
            }, "ptv-dual-banners").apply { isDaemon = true }.start()
        }
    }

    /** Asks the screen for Twitch's emote picker ("emotes") or rewards panel ("points"). */
    var onSheet: ((String) -> Unit)? = null

    val box: EditText get() = input

    private fun iconButton(icon: Drawable, label: String, onTap: () -> Unit) = ImageView(context).apply {
        setImageDrawable(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
        contentDescription = I18n.t(label)
        isFocusable = false
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
        setOnClickListener { onTap() }
    }

    private val pointsBtn = TextView(ctx).apply {
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        compoundDrawablePadding = dp(3f)
        setCompoundDrawables(DualIcons.Points().apply { setBounds(0, 0, dp(24f), dp(24f)) }, null, null, null)
        setPadding(dp(4f), 0, dp(6f), 0)
        contentDescription = I18n.t("Channel points")
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
        setOnClickListener { onSheet?.invoke("points") }
    }
    private val emoteBtn = iconButton(DualIcons.Smile(), "Emotes") { onSheet?.invoke("emotes") }
    private val menuBtn = iconButton(DualIcons.More(), "Chat settings") { openChatMenu() }

    private val inputRow = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6f), dp(6f), dp(4f), dp(6f))
        addView(pointsBtn, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f)).apply { rightMargin = dp(2f) })
        addView(input, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(emoteBtn, LayoutParams(dp(36f), dp(36f)).apply { leftMargin = dp(2f) })
        addView(menuBtn, LayoutParams(dp(30f), dp(36f)))
        addView(sendBtn, LayoutParams(dp(38f), dp(40f)))
    }

    /** Too narrow for every button: emotes and the menu move to a long press on Send. */
    private var tight = false
    private var sendTip: View? = null

    private val pointsTick: Runnable = object : Runnable {
        override fun run() {
            ChannelPoints.balance(login)?.let { b ->
                val t = TwoStreamsRoot.count(b)
                if (pointsBtn.text?.toString() != t) pointsBtn.text = t
            }
            if (started) main.postDelayed(this, POINTS_MS)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val now = w in 1 until dp(TIGHT_DP)
        if (now == tight) return
        tight = now
        post {
            val v = if (tight) View.GONE else View.VISIBLE
            emoteBtn.visibility = v
            menuBtn.visibility = v
            if (tight) showSendTip() else removeSendTip()
        }
    }

    private fun showSendMenu() {
        removeSendTip(true)
        val pm = PopupMenu(ContextThemeWrapper(context, android.R.style.Theme_Material), sendBtn, Gravity.END)
        pm.menu.add(0, 1, 0, I18n.t("Emotes"))
        pm.menu.add(0, 2, 1, I18n.t("Chat settings"))
        pm.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> onSheet?.invoke("emotes"); 2 -> openChatMenu() }
            true
        }
        pm.show()
    }

    /** Once ever, on a device where the buttons really are tucked away. */
    private fun showSendTip() {
        // One bubble is enough: it is about both panes' Send buttons.
        if (Settings.get(KEY_SEND_TIP, false) || sendTip != null || compact || tipPane?.get()?.sendTip != null) return
        val frame = list.parent as? FrameLayout ?: return
        val bubble = DualIcons.DownBubble(0xF2241B31.toInt(), PURPLE, dp(12f).toFloat(), dp(1.5f).toFloat(), dp(8f).toFloat())
        val tip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bubble
            setPadding(dp(12f), dp(8f), dp(4f), dp(8f) + dp(8f))
            elevation = dp(6f).toFloat()
            addView(TextView(context).apply {
                text = I18n.t("Hold Send for emotes and the chat menu")
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "\u2715"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                contentDescription = I18n.t("Dismiss tip")
                setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
                setOnClickListener { removeSendTip(true) }
            })
        }
        frame.addView(tip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { leftMargin = dp(6f); rightMargin = dp(4f) })
        tip.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val a = IntArray(2).also { sendBtn.getLocationInWindow(it) }
            val b = IntArray(2).also { v.getLocationInWindow(it) }
            val x = (a[0] - b[0] + sendBtn.width / 2).toFloat()
            if (bubble.arrowX != x) { bubble.arrowX = x; bubble.invalidateSelf() }
        }
        sendTip = tip
        tipPane = java.lang.ref.WeakReference(this)
    }

    private fun removeSendTip(forGood: Boolean = false) {
        if (forGood && !Settings.get(KEY_SEND_TIP, false)) Settings.set(KEY_SEND_TIP, true)
        sendTip?.let { (it.parent as? ViewGroup)?.removeView(it) }
        sendTip = null
    }

    private fun openChatMenu() {
        val act = host() ?: return
        val id = channelId
        if (id.isNullOrEmpty()) { toast(context, "Not connected to chat yet"); return }
        if (!DualCards.chatSettings(act, id, isMod())) toast(context, "Could not open this")
    }

    fun hideKeys() = hideKeyboard()

    fun focusBox() {
        input.requestFocus()
        runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** The screen this pane is on; the pane's own context may be a scaled wrapper around it. */
    private fun host(): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private val inputLine = View(ctx).apply { setBackgroundColor(LINE) }

    private val replyText = TextView(ctx).apply {
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private val replyBar = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12f), 0, dp(4f), 0)
        setBackgroundColor(FIELD)
        visibility = View.GONE
        addView(replyText, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ImageView(ctx).apply {
            setImageDrawable(DualIcons.Close())
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            contentDescription = I18n.t("Cancel reply")
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
            setOnClickListener { cancelReply() }
        }, LayoutParams(dp(32f), dp(32f)))
    }

    /** Hide the message box: picture-in-picture, or a pane too small to type in. */
    var compact: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            val vis = if (v) View.GONE else View.VISIBLE
            inputRow.visibility = vis
            inputLine.visibility = vis
            if (v) { cancelReply(); hideKeyboard(); showPill(false) }
            showBanner()
        }

    init {
        orientation = VERTICAL
        setBackgroundColor(BG)
        tag = "ptv_dual_chat"
        readStyle()
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (pill.visibility == View.VISIBLE && atBottom()) showPill(false)
            }
        })
        // Not by position: a flush during the hold can drop old lines and shift every position,
        // while the list keeps the one it saw at touch-down.
        list.setOnItemLongClickListener { _, row, _, _ ->
            val l = pressed ?: return@setOnItemLongClickListener false
            showLineMenu(row, l)
            true
        }
        val frame = FrameLayout(ctx).apply {
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8f) })
        }
        addView(bannerBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(frame, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(inputLine, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)))
        addView(replyBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(inputRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        EmoteAutocomplete.attachBox(input) { word, redraw -> SideEmotes.suggest(channelId.orEmpty(), word, 30, redraw) }
    }

    // ---- lifecycle ----

    /** Join the channel's chat. Lines start arriving once the server confirms the join. */
    fun start() {
        if (started) return
        started = true
        stoppedByDetach = false
        readStyle()
        DualIrc.join(context, login, irc)
        // The global lists are worth having before the channel id is known: most badges and many
        // emotes are global.
        val id = channelId.orEmpty()
        SideEmotes.pin(id)
        TwitchBadges.load(context, id, onLists)
        SideEmotes.load(id, login, onLists)
        main.removeCallbacks(badgeRetry)
        main.postDelayed(badgeRetry, BADGE_RETRY_MS)
        if (ownId == null) {
            val app = context.applicationContext ?: context
            Thread({ ownId = runCatching { EmoteRepo.ownUserId(app) }.getOrDefault("") }, "ptv-dual-self")
                .apply { isDaemon = true }.start()
        }
        updateHint()
        main.removeCallbacks(pointsTick)
        main.post(pointsTick)
        main.removeCallbacks(bannerPoll)
        main.removeCallbacks(bannerTurn)
        main.postDelayed(bannerPoll, BANNER_FIRST_MS)
        main.postDelayed(bannerTurn, BANNER_TURN_MS)
        log("DC chat started for $login")
    }

    /** Leave the chat. Lines already shown stay; pictures still downloading no longer call back. */
    fun stop() {
        if (!started) return
        started = false
        SideEmotes.unpin(channelId.orEmpty())
        DualCards.forget(cardKey)
        DualIrc.leave(login, irc)
        SideEmotes.drop(onPicture)
        main.removeCallbacks(flush)
        main.removeCallbacks(badgeRetry)
        main.removeCallbacks(pointsTick)
        main.removeCallbacks(bannerPoll)
        main.removeCallbacks(bannerTurn)
        bannerGen++
        removeSendTip()
        flushQueued = false
        pending.clear()
        unconfirmed.clear()
        connected = false
        hideKeyboard()
        log("DC chat stopped for $login")
    }

    /** The channel's numeric id, from the player or the chat's own ROOMSTATE. Loads its badges
     *  and emotes; lines already shown get them as soon as they are here. */
    fun setChannelId(id: String) {
        if (id.isBlank() || id == channelId) return
        if (started) { SideEmotes.unpin(channelId.orEmpty()); SideEmotes.pin(id) }
        channelId = id
        allVer++
        SideEmotes.load(id, login, onLists)
        TwitchBadges.load(context, id, onLists)
        if (started) {
            main.removeCallbacks(badgeRetry)
            main.postDelayed(badgeRetry, BADGE_RETRY_MS)
        }
    }

    /**
     * Asks for badges that did not come in again, every minute while the chat runs, so a failed
     * request does not leave the chat without them. Just over the minute TwitchBadges waits
     * before it repeats a request.
     */
    private val badgeRetry: Runnable = object : Runnable {
        override fun run() {
            val id = channelId.orEmpty()
            if (!started || !TwitchBadges.missing(id)) return
            TwitchBadges.load(context, id, onLists)
            main.postDelayed(this, BADGE_RETRY_MS)
        }
    }

    private val detachCheck = Runnable {
        // Taken off the screen for good without stop(): leave the chat rather than keep its lines
        // coming (and this pane alive) for nothing. Put back later, it joins again.
        if (!isAttachedToWindow && started) {
            log("DC chat for $login left the screen while running; stopping it")
            stop()
            stoppedByDetach = true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        main.removeCallbacks(detachCheck)
        if (stoppedByDetach) start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A moment's grace: the screen moves panes between layouts by taking them off and back on.
        if (started) main.postDelayed(detachCheck, 2000)
    }

    // ---- incoming lines ----

    private val irc = object : DualIrc.Listener {
        override fun onMessage(m: IrcMsg) {
            // Never the text itself in the log: it is someone else's chat message.
            runCatching { onLine(m) }.onFailure { log("DC ${m.command} line failed: ${it.javaClass.simpleName}") }
        }
        override fun onConnected(on: Boolean) {
            runCatching { connectedChanged(on) }.onFailure { log("DC connection change failed: $it") }
        }
    }

    private val onPicture: () -> Unit = {
        if (started) { picVer++; scheduleFlush() }
    }

    private val onLists: () -> Unit = {
        main.post { allVer++; if (started) scheduleFlush() }
    }

    private fun onLine(m: IrcMsg) {
        when (m.command) {
            "PRIVMSG" -> chat(m, null, if (m.tag("msg-id") == "highlighted-message") HIGHLIGHT_TINT else 0)
            "USERNOTICE" -> userNotice(m)
            // Sent after each of our lines Twitch accepts, with that line's id. The one sent on
            // joining has no id.
            "USERSTATE" -> m.tag("id")?.let { id -> nextUnconfirmed()?.id = id }
            "NOTICE" -> {
                // A msg_ notice turns down a line we sent (slow mode, followers only, a timeout,
                // a duplicate...): it never reached the chat, so it goes, and the notice says why.
                if (m.tag("msg-id")?.startsWith("msg_") == true) nextUnconfirmed()?.let { removeOwn(it) }
                m.text?.takeIf { it.isNotBlank() }?.let { addSystem(it) }
            }
            "CLEARMSG" -> m.tag("target-msg-id")?.let { deleteMessage(it) }
            "CLEARCHAT" -> clearChat(m)
            "ROOMSTATE" -> m.tag("room-id")?.let { setChannelId(it) }
        }
    }

    /** A chatter's line. False when there was nothing to show, or the blacklist hides it. */
    private fun chat(m: IrcMsg, header: String?, tint: Int): Boolean {
        val raw = m.text ?: return false
        val who = (m.nick ?: m.tag("login"))?.lowercase() ?: return false
        val body = IrcParse.action(raw)
        val text = body ?: raw
        if (hidden(who, text)) return false
        add(Line(++seq, text,
            id = m.tag("id"),
            userId = m.tag("user-id"),
            login = who,
            display = m.tag("display-name")?.trim(),
            color = m.tag("color"),
            badges = IrcParse.badges(m.tag("badges")),
            action = body != null,
            emotes = IrcParse.emoteRanges(m.tag("emotes"), text),
            ts = m.tag("tmi-sent-ts")?.toLongOrNull() ?: System.currentTimeMillis(),
            header = header,
            tint = tint,
            replyTo = m.tag("reply-parent-display-name") ?: m.tag("reply-parent-user-login"),
            replyBody = m.tag("reply-parent-msg-body"),
            gifs = if (body == null) IrcParse.gifRanges(m.tag("gifs"), text) else emptyList()))
        return true
    }

    /** Subs, gift subs, raids, announcements: Twitch's own sentence in grey, then the chatter's
     *  words if they added any. */
    private fun userNotice(m: IrcMsg) {
        val header = m.tag("system-msg")
            ?: if (m.tag("msg-id") == "announcement") I18n.t("Announcement") else null
        if (!m.text.isNullOrEmpty() && chat(m, header, NOTICE_TINT)) return
        header?.let { addSystem(it, NOTICE_TINT) }
    }

    /** A line from the two-streams screen itself, such as a raid moving this pane. */
    fun notice(text: String) = addSystem(text, NOTICE_TINT)

    private fun addSystem(text: String, tint: Int = 0) {
        add(Line(++seq, text, system = true, tint = tint))
    }

    private fun add(l: Line) {
        pending.add(l)
        if (pending.size > MAX_LINES) pending.subList(0, pending.size - MAX_LINES).clear()
        scheduleFlush()
    }

    /** The own line Twitch's next answer is about. Answers come back in the order the lines went
     *  out; a line that waited too long is passed over, so one lost answer cannot pair every
     *  later answer with the wrong line. */
    private fun nextUnconfirmed(): Line? {
        val now = SystemClock.uptimeMillis()
        while (true) {
            val (l, at) = unconfirmed.removeFirstOrNull() ?: return null
            if (now - at <= ANSWER_MS) return l
        }
    }

    private fun removeOwn(l: Line) {
        if (pending.remove(l)) return
        if (items.remove(l)) adapter.notifyDataSetChanged()
    }

    private fun deleteMessage(id: String) {
        val l = items.lastOrNull { it.id == id } ?: pending.lastOrNull { it.id == id } ?: return
        markDeleted(l)
        scheduleFlush()
    }

    private fun markDeleted(l: Line) {
        if (l.deleted) return
        l.deleted = true
        l.built = null
    }

    /** A timeout or ban (with a target), or the whole chat cleared (without one). */
    private fun clearChat(m: IrcMsg) {
        val target = m.text?.lowercase()?.takeIf { it.isNotBlank() }
        val uid = m.tag("target-user-id")
        if (target == null && uid == null) { addSystem(I18n.t("Chat was cleared by a moderator")); return }
        var name: String? = null
        for (l in items.asSequence() + pending.asSequence()) {
            if (l.system) continue
            if ((uid != null && l.userId == uid) || (target != null && l.login == target)) {
                markDeleted(l)
                name = l.display ?: name
            }
        }
        val who = name ?: target ?: return
        val secs = m.tag("ban-duration")
        addSystem(if (secs != null) I18n.t("$who was timed out for ${length(secs)}") else I18n.t("$who was banned"))
    }

    /** "600" as "10 minutes", in the app's language. */
    private fun length(secs: String): String {
        val n = secs.toLongOrNull() ?: return secs
        return when {
            n >= 86400 && n % 86400 == 0L -> (n / 86400).let { if (it == 1L) I18n.t("1 day") else I18n.t("$it days") }
            n >= 3600 && n % 3600 == 0L -> (n / 3600).let { if (it == 1L) I18n.t("1 hour") else I18n.t("$it hours") }
            n >= 60 && n % 60 == 0L -> (n / 60).let { if (it == 1L) I18n.t("1 minute") else I18n.t("$it minutes") }
            else -> if (n == 1L) I18n.t("1 second") else I18n.t("$n seconds")
        }
    }

    private fun connectedChanged(on: Boolean) {
        connected = on
        // Answers to lines sent on the old connection will not come.
        if (!on) unconfirmed.clear()
        if (on) {
            if (!welcomed) { welcomed = true; addSystem(I18n.t("Welcome to the chat room!")) }
            else if (lost) {
                addSystem(I18n.t("Reconnected to chat"))
                // The network is back: badges that failed while it was gone can come now.
                val id = channelId.orEmpty()
                if (TwitchBadges.missing(id)) TwitchBadges.load(context, id, onLists)
            }
            lost = false
        } else if (welcomed && !lost && started) {
            lost = true
            addSystem(I18n.t("Lost connection to chat, reconnecting"))
        }
        updateHint()
    }

    // ---- adding lines to the list ----

    private fun scheduleFlush() {
        if (flushQueued) return
        flushQueued = true
        val wait = (BATCH_MS - (SystemClock.uptimeMillis() - lastFlush)).coerceIn(0L, BATCH_MS)
        main.postDelayed(flush, wait)
    }

    private val flush = Runnable {
        flushQueued = false
        lastFlush = SystemClock.uptimeMillis()
        runCatching { applyPending() }.onFailure { log("DC flush failed: ${it.javaClass.simpleName}") }
    }

    private fun applyPending() {
        if (Settings.rev != seenRev) readStyle()
        updateHint()
        // A hidden list (picture-in-picture) is not laid out, so its rows say nothing about where
        // it is scrolled to. It is left to the list, which follows the newest line again when it
        // is shown if it did before it was hidden.
        val follow = !list.isShown || list.height == 0 || atBottom()
        val first = list.firstVisiblePosition
        val firstTop = (list.getChildAt(0)?.top ?: list.paddingTop) - list.paddingTop
        val added = pending.size
        var dropped = 0
        try {
            if (added > 0) { items.addAll(pending); pending.clear() }
            dropped = (items.size - MAX_LINES).coerceAtLeast(0)
            if (dropped > 0) items.subList(0, dropped).clear()
        } finally {
            // The list must hear about every change in the same turn, or it throws at its next layout.
            adapter.notifyDataSetChanged()
        }
        if (follow) return
        // Scrolled up to read: the oldest lines going must not slide what is being read.
        if (dropped > 0) list.setSelectionFromTop((first - dropped).coerceAtLeast(0), firstTop)
        if (added > 0) showPill(true)
    }

    /** The newest line is fully in view, so new ones are followed. The same test the list uses. */
    private fun atBottom(): Boolean {
        val n = list.count
        val c = list.childCount
        if (n == 0 || c == 0) return true
        if (list.firstVisiblePosition + c < n) return false
        return list.getChildAt(c - 1).bottom <= list.height - list.paddingBottom
    }

    private fun showPill(on: Boolean) {
        val vis = if (on && !compact) View.VISIBLE else View.GONE
        if (pill.visibility != vis) pill.visibility = vis
    }

    private fun jumpToEnd() {
        showPill(false)
        val n = adapter.count
        if (n > 0) list.setSelection(n - 1)
    }

    // ---- building a row ----

    private fun bind(row: RowView, l: Line) {
        if (l.built == null || l.builtVer != allVer || (l.waiting && l.builtPic != picVer)) build(l)
        if (row.textSize != textPx) row.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx)
        val bg = when {
            l.mark != 0 -> l.mark
            l.tint != 0 -> l.tint
            alt && l.seq % 2L == 1L -> ALT_TINT
            else -> 0
        }
        if (row.bgNow != bg) { row.bgNow = bg; row.setBackgroundColor(bg) }
        row.line = l
        val cs = l.built
        if (row.shown !== cs) {
            row.shown = cs
            row.text = cs
            // The app-wide setText hook tells the animation driver about these pictures as well,
            // when it could be placed on this Twitch build. Telling it twice changes nothing, and
            // without that hook the emotes here would otherwise never move.
            AnimPause.onSetText(row, cs)
        }
    }

    private fun build(l: Line) {
        waitingNow = false
        l.mark = 0
        l.built = runCatching { if (l.system) systemText(l) else chatText(l) }
            .getOrElse { log("DC build failed: ${it.javaClass.simpleName}"); l.text }
        l.waiting = waitingNow
        l.builtVer = allVer
        l.builtPic = picVer
    }

    private fun systemText(l: Line): CharSequence {
        val sb = SpannableStringBuilder()
        time(sb, l.ts)
        val s = sb.length
        sb.append(l.text)
        sb.setSpan(ForegroundColorSpan(MUTED), s, sb.length, EXCL)
        return sb
    }

    private fun chatText(l: Line): CharSequence {
        val sb = SpannableStringBuilder()
        l.header?.let { h ->
            val s = sb.length
            sb.append(h)
            sb.setSpan(ForegroundColorSpan(MUTED), s, sb.length, EXCL)
            sb.append('\n')
        }
        l.replyTo?.let { to ->
            val s = sb.length
            sb.append(I18n.t("Replying to @$to"))
            l.replyBody?.let { b -> sb.append(": ").append(if (b.length > 80) b.take(80).trimEnd() + "\u2026" else b) }
            sb.setSpan(ForegroundColorSpan(MUTED), s, sb.length, EXCL)
            sb.setSpan(RelativeSizeSpan(0.85f), s, sb.length, EXCL)
            sb.append('\n')
        }
        time(sb, l.ts)
        badges(sb, l)
        val color = name(sb, l)
        sb.append(if (l.action) " " else ": ")
        body(sb, l, color)
        if (!l.own) l.mark = highlight(l)
        return sb
    }

    private fun time(sb: SpannableStringBuilder, ts: Long) {
        val f = timeFormat ?: return
        val s = sb.length
        sb.append(f.format(Date(ts)))
        sb.setSpan(ForegroundColorSpan(MUTED), s, sb.length, EXCL)
        sb.append(' ')
    }

    private fun badges(sb: SpannableStringBuilder, l: Line) {
        val cid = channelId.orEmpty()
        for ((set, version) in l.badges) {
            val (url, title) = TwitchBadges.of(cid, set, version) ?: continue
            val d = SideEmotes.picture(url, onPicture)
            if (d == null) { waitingNow = true; continue }
            image(sb, BadgeSpan(d, badgePx, gapPx, title, "twitch", url))
        }
        val uid = l.userId
        if (!uid.isNullOrEmpty() && BadgeRepo.anyEnabled())
            for ((b, d) in BadgeRepo.forUser(uid)) image(sb, BadgeSpan(d, badgePx, gapPx, b.title, b.src, b.url))
    }

    private fun image(sb: SpannableStringBuilder, span: ImageSpan) {
        val s = sb.length
        sb.append(NBSP)
        sb.setSpan(span, s, sb.length, EXCL)
    }

    /** The name in its colour, then a pronoun tag. Returns the colour, for /me lines. */
    private fun name(sb: SpannableStringBuilder, l: Line): Int {
        val who = l.login.orEmpty()
        val display = l.display.orEmpty()
        val shown = when (nameMode) {
            1 -> display.ifEmpty { who }
            2 -> who.ifEmpty { display }
            // Twitch's own way: "Name (login)" only when the display name is not just the login.
            else -> if (display.isNotEmpty() && who.isNotEmpty() && !display.equals(who, ignoreCase = true))
                "$display ($who)" else display.ifEmpty { who }
        }
        val color = ChatColors.nameColor(l.color, who, BG)
        val s = sb.length
        sb.append(shown)
        sb.setSpan(ForegroundColorSpan(color), s, sb.length, EXCL)
        sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, EXCL)
        if (who.isNotEmpty()) sb.setSpan(NameMark(who), s, sb.length, EXCL)
        if (who.isNotEmpty()) Pronouns.forLogin(who)?.let { p ->
            val ps = sb.length
            sb.append(' ').append(p)
            sb.setSpan(RelativeSizeSpan(0.8f), ps, sb.length, EXCL)
            sb.setSpan(ForegroundColorSpan(MUTED), ps, sb.length, EXCL)
        }
        return color
    }

    private fun body(sb: SpannableStringBuilder, l: Line, nameColor: Int) {
        val start = sb.length
        if (l.deleted && delMode == 0) {
            sb.append(placeholder())
            sb.setSpan(ForegroundColorSpan(MUTED), start, sb.length, EXCL)
            return
        }
        val text = l.text
        sb.append(text)
        val end = sb.length
        // Twitch's emotes come with their ids and places.
        for (r in l.emotes) {
            if (r.start < 0 || r.end > text.length || r.start >= r.end) continue
            val d = SideEmotes.picture(SideEmotes.twitchUrl(r.id), onPicture)
            if (d == null) { waitingNow = true; continue }
            sb.setSpan(EmoteSpan(d, emotePx, id = r.id, word = text.substring(r.start, r.end)), start + r.start, start + r.end, EXCL)
        }
        // A GIF stands in for its title in the text, and until it is here the title shows.
        for (g in l.gifs) {
            if (g.end > text.length || g.start >= g.end) continue
            val tall = dp(GIF_DP)
            val d = SideEmotes.gif(g.url, tall, onPicture)
            if (d == null) { waitingNow = true; continue }
            val b = d.bounds
            val room = max(dp(120f), list.width - dp(24f))
            val h = if (b.height() > 0 && b.width().toLong() * tall / b.height() > room)
                (room.toLong() * b.height() / b.width()).toInt().coerceAtLeast(1) else tall
            sb.setSpan(EmoteSpan(d, h, gif = g), start + g.start, start + g.end, EXCL)
        }
        // 7TV, BTTV and FFZ go by word; on our own lines also the account's Twitch emotes, which
        // IRC never sends back to us with their ids.
        val cid = channelId.orEmpty()
        var i = 0
        var ri = 0
        while (i < text.length) {
            while (i < text.length && text[i] == ' ') i++
            if (i >= text.length) break
            var j = i
            while (j < text.length && text[j] != ' ') j++
            while (ri < l.emotes.size && l.emotes[ri].end <= i) ri++
            val onTwitchEmote = ri < l.emotes.size && l.emotes[ri].start < j
            val onGif = l.gifs.any { it.start < j && it.end > i }
            if (!onTwitchEmote && !onGif && j - i <= 100) {
                val w = text.substring(i, j)
                val url = (if (l.own) EmoteRepo.twitch[w] else null) ?: SideEmotes.urlOf(cid, w)
                if (url != null) {
                    val d = SideEmotes.picture(url, onPicture)
                    if (d == null) waitingNow = true
                    else sb.setSpan(EmoteSpan(d, emotePx, id = TWITCH_EMOTE.find(url)?.groupValues?.get(1), word = w), start + i, start + j, EXCL)
                }
            }
            i = j
        }
        links(sb, text, start)
        if (l.action) {
            sb.setSpan(ForegroundColorSpan(nameColor), start, end, EXCL)
            if (meMode == 2 || meMode == 3) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, EXCL)
        }
        if (l.deleted) when (delMode) {
            2 -> sb.setSpan(StrikethroughSpan(), start, end, EXCL)
            3 -> sb.setSpan(ForegroundColorSpan(DELETED), start, end, EXCL)
        }
    }

    private fun links(sb: SpannableStringBuilder, text: String, start: Int) {
        if (text.indexOf('.') < 0) return
        for (m in URL.findAll(text)) {
            val s = m.range.first
            var e = m.range.last + 1
            while (e > s && text[e - 1] in ".,!?:;)'\"") e--
            if (e - s < 4) continue
            if (sb.getSpans(start + s, start + e, ImageSpan::class.java).isNotEmpty()) continue
            val raw = text.substring(s, e)
            val href = if (raw.regionMatches(0, "http", 0, 4, ignoreCase = true)) raw else "https://$raw"
            sb.setSpan(URLSpan(href), start + s, start + e, EXCL)
        }
    }

    /** "<message deleted>" in the app's own words, so it reads like Twitch's chat. */
    private fun placeholder(): String = deletedText ?: run {
        val id = Res.id(context, "chat_message_deleted", "string")
        val own = if (id != 0) runCatching { context.resources.getString(id) }.getOrNull() else null
        val word = own ?: I18n.t("message deleted")
        "<$word>".also { deletedText = it }
    }

    // ---- highlights and the blacklist ----

    /** The row colour for a line that mentions us or holds a highlighted word, else 0. */
    private fun highlight(l: Line): Int {
        val self = DualIrc.selfLogin()
        if (!self.isNullOrEmpty()) {
            if (mentionFor != self) {
                mentionFor = self
                mentionRe = Regex("(?<!$WORD_CHAR)@?${Regex.escape(self)}(?!$WORD_CHAR)", RegexOption.IGNORE_CASE)
            }
            if (mentionRe?.containsMatchIn(l.text) == true) return mentionColor
        }
        return highlights.match(l.login.orEmpty(), l.text)
    }

    /** ChatHighlight's keywords, matched the way it matches them on Twitch's chat lines. */
    private class Highlights(val users: Map<String, Int>, val exact: Map<String, Int>,
                             val anyCase: Map<String, Int>, val phrases: List<Pair<Regex, Int>>) {
        fun match(login: String, text: String): Int {
            if (users.isEmpty() && exact.isEmpty() && anyCase.isEmpty() && phrases.isEmpty()) return 0
            users[login]?.let { return it }
            if (exact.isNotEmpty() || anyCase.isNotEmpty()) for (raw in text.split(' ', '\t', '\n')) {
                if (raw.isBlank()) continue
                // "word!" and "(word)" match "word" too.
                val bare = raw.trim { !it.isLetterOrDigit() && it != '_' }
                for (w in if (bare.isNotEmpty() && bare != raw) listOf(raw, bare) else listOf(raw)) {
                    exact[w]?.let { return it }
                    anyCase[w.lowercase()]?.let { return it }
                }
            }
            for ((re, c) in phrases) if (re.containsMatchIn(text)) return c
            return 0
        }

        companion object {
            val NONE = Highlights(emptyMap(), emptyMap(), emptyMap(), emptyList())

            fun from(words: List<ChatHighlight.Keyword>): Highlights {
                if (words.isEmpty()) return NONE
                val users = HashMap<String, Int>(); val exact = HashMap<String, Int>(); val anyCase = HashMap<String, Int>()
                val phrases = ArrayList<Pair<Regex, Int>>()
                for (k in words) {
                    val w = k.word.trim()
                    if (w.isEmpty()) continue
                    when {
                        k.type == ChatHighlight.Type.USERNAME -> users.putIfAbsent(w.lowercase(), k.color)
                        w.contains(' ') -> runCatching {
                            val body = w.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
                            val opts = if (k.type == ChatHighlight.Type.SENSITIVE) emptySet() else setOf(RegexOption.IGNORE_CASE)
                            phrases += Regex("(?<!$WORD_CHAR)$body(?!$WORD_CHAR)", opts) to k.color
                        }
                        k.type == ChatHighlight.Type.SENSITIVE -> exact.putIfAbsent(w, k.color)
                        else -> anyCase.putIfAbsent(w.lowercase(), k.color)
                    }
                }
                return Highlights(users, exact, anyCase, phrases)
            }
        }
    }

    private fun hidden(who: String, text: String): Boolean = hide.matches(who, text)

    /** Blacklist terms, matched the way Blacklist matches them on Twitch's chat lines. */
    private class HideRules(val users: Set<String>, val exact: Set<String>, val anyCase: Set<String>, val phrases: List<Regex>) {
        fun matches(login: String, text: String): Boolean {
            if (users.isEmpty() && exact.isEmpty() && anyCase.isEmpty() && phrases.isEmpty()) return false
            if (login in users) return true
            if (exact.isNotEmpty() || anyCase.isNotEmpty()) for (w in SPLIT.split(text)) {
                if (w.isEmpty()) continue
                if (w in exact || w.lowercase() in anyCase) return true
            }
            for (re in phrases) if (re.containsMatchIn(text)) return true
            return false
        }

        companion object {
            val NONE = HideRules(emptySet(), emptySet(), emptySet(), emptyList())

            fun from(terms: List<Blacklist.Term>): HideRules {
                if (terms.isEmpty()) return NONE
                val users = HashSet<String>(); val exact = HashSet<String>(); val anyCase = HashSet<String>()
                val sensitive = ArrayList<String>(); val insensitive = ArrayList<String>()
                for (t in terms) {
                    val w = t.word
                    if (w.isBlank()) continue
                    when {
                        t.type == Blacklist.Type.USERNAME -> users += w.trim().lowercase()
                        // A term with separators in it is looked for in the whole message.
                        SPLIT.containsMatchIn(w) -> if (t.type == Blacklist.Type.SENSITIVE) sensitive += w else insensitive += w
                        t.type == Blacklist.Type.SENSITIVE -> exact += w
                        else -> anyCase += w.lowercase()
                    }
                }
                val phrases = listOfNotNull(phrase(sensitive, false), phrase(insensitive, true))
                return HideRules(users, exact, anyCase, phrases)
            }

            private fun phrase(words: List<String>, anyCase: Boolean): Regex? {
                val useful = words.filter { w -> w.split(SPLIT).any { it.isNotEmpty() } }
                if (useful.isEmpty()) return null
                return runCatching {
                    Regex("(?<!$WORD_CHAR)(?:${useful.joinToString("|") { Regex.escape(it) }})(?!$WORD_CHAR)",
                        if (anyCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                }.getOrNull()
            }
        }
    }

    // ---- settings ----

    private fun readStyle() {
        seenRev = Settings.rev
        val size = Settings.getInt(ChatAppearance.KEY_FONT_SIZE, 0)
        textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (size > 0) size.toFloat() else 13f,
            resources.displayMetrics)
        // Twitch draws emotes at twice its text size and badges a little larger than the text.
        emotePx = (textPx * 2f).roundToInt().coerceAtLeast(1)
        badgePx = (textPx * 1.3f).roundToInt().coerceAtLeast(1)
        gapPx = (textPx * 0.25f).roundToInt()
        alt = Settings.get(ChatAppearance.KEY_ALT_BG, false)
        val pattern = TIME_PATTERNS.getOrNull(Settings.getInt(ChatLineStyle.KEY_TIMESTAMP, 0)).orEmpty()
        timeFormat = if (pattern.isEmpty()) null else runCatching { SimpleDateFormat(pattern, Locale.getDefault()) }.getOrNull()
        nameMode = Settings.getInt(ChatLineStyle.KEY_NAME_STYLE, 0)
        meMode = Settings.getInt(ChatLineStyle.KEY_ME_STYLE, 0)
        delMode = Settings.getInt(ChatLineStyle.KEY_DELETED, 0)
        // Twitch tints mentions in its own chat whatever this switch says; the switch only picks
        // our colour over Twitch's.
        mentionColor = if (Settings.get(ChatHighlight.KEY_MENTION_ENABLED, false))
            Settings.getInt(ChatHighlight.KEY_MENTION_COLOR, ChatHighlight.MENTION_COLOR_DEFAULT)
        else ChatHighlight.MENTION_COLOR_DEFAULT
        highlights = runCatching { Highlights.from(ChatHighlight.keywords()) }.getOrDefault(Highlights.NONE)
        hide = runCatching { HideRules.from(Blacklist.terms()) }.getOrDefault(HideRules.NONE)
        // Every line was built with the old values.
        allVer++
    }

    // ---- the message box ----

    private fun updateHint() {
        val can = DualIrc.canSend()
        val h = when {
            can -> I18n.t("Send a message")
            !connected -> I18n.t("Connecting to chat")
            else -> I18n.t("Log in to chat")
        }
        if (input.hint?.toString() != h) input.hint = h
        // Read-only (signed out, or Twitch refused the sign-in): nothing to type into.
        val usable = can || !connected
        if (input.isEnabled != usable) input.isEnabled = usable
    }

    private fun send() {
        val text = input.text?.toString()?.trim().orEmpty()
        val isMe = text == "/me" || text.startsWith("/me ")
        // "/me" with nothing after it would send nothing at all.
        if (text.isEmpty() || (isMe && text.length <= 4)) return
        if (!DualIrc.canSend()) {
            toast(context, if (connected) "Log in to Twitch to chat" else "Not connected to chat yet")
            return
        }
        val parent = replying?.takeIf { !text.startsWith("/") }
        if (!DualIrc.send(login, text, parent?.id)) {
            toast(context, "Message not sent, try again in a moment")
            return
        }
        input.setText("")
        cancelReply()
        // Any other command gets its answer from Twitch as a notice line.
        if (text.startsWith("/") && !isMe) return
        addOwn(if (isMe) text.substring(3).trim() else text, isMe, parent)
    }

    private fun startReply(l: Line) {
        if (compact || l.id == null || !input.isEnabled) return
        replying = l
        val who = l.display?.takeIf { it.isNotEmpty() } ?: l.login.orEmpty()
        replyText.text = I18n.t("Replying to @$who") + ": " + l.text
        replyBar.visibility = View.VISIBLE
        input.requestFocus()
        runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun cancelReply() {
        replying = null
        replyBar.visibility = View.GONE
    }

    /** Long press on a line. */
    private fun showLineMenu(anchor: View, l: Line) {
        val pm = PopupMenu(ContextThemeWrapper(context, android.R.style.Theme_Material), anchor, Gravity.END)
        if (!l.system && l.id != null && !compact && input.isEnabled) pm.menu.add(0, 1, 0, I18n.t("Reply"))
        pm.menu.add(0, 2, 1, I18n.t("Copy message"))
        if (!l.system && l.login != null && l.userId != null) pm.menu.add(0, 3, 2, I18n.t("View profile"))
        pm.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> startReply(l); 2 -> copyLine(l); 3 -> openUser(l.login.orEmpty(), l) }
            true
        }
        pm.show()
    }

    /** Twitch does not send our own lines back, so they are added here, dressed from USERSTATE. */
    private fun addOwn(text: String, action: Boolean, parent: Line? = null) {
        if (text.isEmpty()) return
        val st = DualIrc.userState(login)
        val self = DualIrc.selfLogin().orEmpty()
        val l = Line(++seq, text,
            userId = ownId?.takeIf { it.isNotEmpty() },
            login = self,
            display = st?.tag("display-name")?.trim() ?: self,
            color = st?.tag("color"),
            badges = IrcParse.badges(st?.tag("badges")),
            action = action,
            own = true,
            replyTo = parent?.let { it.display?.takeIf { d -> d.isNotEmpty() } ?: it.login },
            replyBody = parent?.text)
        add(l)
        unconfirmed.addLast(l to SystemClock.uptimeMillis())
        if (unconfirmed.size > 10) unconfirmed.removeFirst()
    }

    private fun mention(who: String) {
        if (compact || who.isEmpty() || !input.isEnabled) return
        val tag = "@$who "
        val cur = input.text?.toString().orEmpty()
        if (cur.indexOf(tag) < 0) input.append(if (cur.isEmpty() || cur.endsWith(" ")) tag else " $tag")
        input.requestFocus()
        input.setSelection(input.text?.length ?: 0)
        runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Keyboard down. Focus stays in the pane, so the back key still reaches the screen it is on. */
    private fun hideKeyboard() {
        runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    private fun copyLine(l: Line) {
        val text = if (l.system) l.text else "${l.display ?: l.login.orEmpty()}: ${l.text}"
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Chat message", text))
            toast(context, "Copied message")
        }
    }

    private fun isMod(): Boolean {
        val st = DualIrc.userState(login) ?: return false
        return st.tag("mod") == "1" || IrcParse.badges(st.tag("badges")).any { it.first == "moderator" || it.first == "broadcaster" }
    }

    /** Twitch's viewer card for [who]; a mention when it can not be opened. */
    private fun openUser(who: String, l: Line?) {
        val act = host()
        val cid = channelId
        val uid = l?.userId
        if (act == null || cid.isNullOrEmpty() || uid.isNullOrEmpty()) { mention(who); return }
        val me = ownId.orEmpty()
        val ok = DualCards.viewer(act, cardKey, cid, uid, who, me, isMod(), me == cid, l.id.orEmpty(), l.text) { mention(it) }
        if (!ok) mention(who)
    }

    /** Twitch's emote or GIF card, or our own preview for an emote Twitch has no card for. */
    private fun openPicture(row: RowView, span: EmoteSpan) {
        val act = host() ?: return
        val l = row.line
        span.gif?.let { g ->
            val title = l?.text?.let { t -> t.substring(g.start.coerceAtMost(t.length), g.end.coerceAtMost(t.length)) }.orEmpty()
                .trim('[', ']', ' ')
            DualCards.gif(act, g.id, g.url, title, l?.id, l?.userId, l?.login, l?.display, channelId)
            return
        }
        val id = span.id
        if (id != null && DualCards.emote(act, id, channelId.orEmpty(), login, l?.id, l?.userId)) return
        span.word?.let { w -> runCatching { EmoteHooks.showEmotePreview(rootView, w) } }
    }

    /** Test hook: opens a real card as a tap on the newest chatter's line would. */
    fun devCard(kind: String) {
        val act = host() ?: return
        val l = items.lastOrNull { !it.system && it.login != null && it.userId != null }
        when (kind) {
            "viewer" -> l?.let { openUser(it.login.orEmpty(), it) }
            "emote" -> log("DC emote card: ${DualCards.emote(act, "25", channelId.orEmpty(), login, l?.id, l?.userId)}")
            "chat" -> log("DC chat settings: ${DualCards.chatSettings(act, channelId.orEmpty(), isMod())}")
            "picker" -> onSheet?.invoke("emotes")
            "points" -> onSheet?.invoke("points")
        }
    }

    private fun onTap(row: RowView, target: Any) {
        when (target) {
            is BadgeSpan -> BadgeInfo.show(row, BadgeInfo.Hit(target.title.ifBlank { null }, target.src, target.url))
            is EmoteSpan -> openPicture(row, target)
            is NameMark -> openUser(target.login, row.line)
            is URLSpan -> runCatching { target.onClick(row) }.onFailure { toast(context, "Could not open the link") }
        }
    }

    // ---- rows and spans ----

    /** Marks a chatter's name on a line, for tap-to-mention. */
    private class NameMark(val login: String)

    /**
     * A chat row. Taps on a badge, a name or a link are its own; anything else is left to the
     * list, so scrolling and long-press work as usual.
     */
    private class RowView(ctx: Context, private val tap: (RowView, Any) -> Unit,
                          private val down: (Line?) -> Unit) : TextView(ctx) {
        var shown: CharSequence? = null
        var bgNow = 0
        var line: Line? = null
        private var target: Any? = null
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        init {
            setTextColor(TEXT)
            setLinkTextColor(LINK)
            val d = resources.displayMetrics.density
            setPadding((12 * d).toInt(), (3 * d).toInt(), (12 * d).toInt(), (3 * d).toInt())
            setLineSpacing(2 * d, 1f)
            isFocusable = false
            tag = ROW_TAG
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down(line)
                    downX = ev.x; downY = ev.y
                    target = targetAt(ev.x, ev.y)
                    if (target != null) return true
                }
                MotionEvent.ACTION_MOVE -> if (target != null) {
                    if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) target = null
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val t = target
                    target = null
                    if (t != null) {
                        if (targetAt(ev.x, ev.y) === t) runCatching { tap(this, t) }.onFailure { log("DC tap failed: $it") }
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> target = null
            }
            return super.onTouchEvent(ev)
        }

        /** The badge, name or link under (x, y), checked against where it is actually drawn:
         *  the nearest character alone can be a neighbour's. */
        private fun targetAt(x: Float, y: Float): Any? {
            val layout = layout ?: return null
            val cs = text as? Spanned ?: return null
            val lx = x - totalPaddingLeft + scrollX
            val ly = (y - totalPaddingTop + scrollY).toInt()
            if (ly < 0 || ly > layout.height) return null
            val line = layout.getLineForVertical(ly)
            if (lx < layout.getLineLeft(line) || lx > layout.getLineRight(line)) return null
            val off = layout.getOffsetForHorizontal(line, lx)
            val from = (off - 1).coerceAtLeast(0)
            val to = (off + 1).coerceAtMost(cs.length)
            fun within(sp: Any): Boolean {
                val st = cs.getSpanStart(sp); val en = cs.getSpanEnd(sp)
                if (st < 0 || en <= st) return false
                if (layout.getLineForOffset(st) > line || layout.getLineForOffset(en) < line) return false
                val a = if (layout.getLineForOffset(st) == line) layout.getPrimaryHorizontal(st) else layout.getLineLeft(line)
                val b = if (layout.getLineForOffset(en) == line) layout.getPrimaryHorizontal(en) else layout.getLineRight(line)
                return lx >= min(a, b) && lx <= max(a, b)
            }
            if (BadgeInfo.enabled()) cs.getSpans(from, to, BadgeSpan::class.java).firstOrNull { within(it) }?.let { return it }
            cs.getSpans(from, to, EmoteSpan::class.java).firstOrNull { within(it) }?.let { return it }
            cs.getSpans(from, to, NameMark::class.java).firstOrNull { within(it) }?.let { return it }
            cs.getSpans(from, to, URLSpan::class.java).firstOrNull { within(it) }?.let { return it }
            return null
        }
    }

    /**
     * Draws its picture [height] px tall, centred on the text, by scaling the canvas. The picture's
     * bounds are never touched: the same drawable may be on Twitch's own chat lines at its own
     * size. Still an ImageSpan holding the picture itself, which is how the animation driver finds
     * animated ones.
     */
    private open class PicSpan(d: Drawable, private val height: Int, private val gap: Int) : ImageSpan(d) {

        private fun width(): Int {
            val b = drawable.bounds
            return if (b.height() > 0) (b.width().toLong() * height / b.height()).toInt().coerceAtLeast(1) else height
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                val p = paint.fontMetricsInt
                val top = (p.ascent + p.descent) / 2 - height / 2
                val bottom = top + height
                fm.ascent = min(p.ascent, top)
                fm.top = min(p.top, top)
                fm.descent = max(p.descent, bottom)
                fm.bottom = max(p.bottom, bottom)
                fm.leading = p.leading
            }
            return width() + gap
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float,
                          top: Int, y: Int, bottom: Int, paint: Paint) {
            val d = drawable
            val b = d.bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val p = paint.fontMetricsInt
            val dTop = y + (p.ascent + p.descent) / 2 - height / 2
            canvas.save()
            canvas.translate(x, dTop.toFloat())
            canvas.scale(width().toFloat() / b.width(), height.toFloat() / b.height())
            canvas.translate(-b.left.toFloat(), -b.top.toFloat())
            d.draw(canvas)
            canvas.restore()
        }
    }

    private class EmoteSpan(d: Drawable, height: Int, val id: String? = null, val word: String? = null,
                            val gif: GifRange? = null) : PicSpan(d, height, 0)

    /** A badge: Twitch's, or a third-party one from BadgeRepo. Tapping it opens the badge sheet. */
    private class BadgeSpan(d: Drawable, height: Int, gap: Int, val title: String, val src: String, val url: String) :
        PicSpan(d, height, gap)

    /** A paper plane: the send button. Purple once there is something to send. */
    private class SendIcon : FilterIcons.Icon(HINT) {
        fun setActive(on: Boolean) {
            val c = if (on) PURPLE else HINT
            if (c != color) { color = c; invalidateSelf() }
        }
        override fun paint(c: Canvas, s: Float) {
            val plane = Path().apply {
                moveTo(s * 0.16f, s * 0.20f)
                lineTo(s * 0.86f, s * 0.50f)
                lineTo(s * 0.16f, s * 0.80f)
                lineTo(s * 0.26f, s * 0.50f)
                close()
            }
            c.drawPath(plane, fill)
        }
    }

    /** A small chevron pointing down, on the "more messages" pill. */
    private class ArrowDown : FilterIcons.Icon(TEXT) {
        override fun paint(c: Canvas, s: Float) {
            stroke.strokeWidth = s * 0.14f
            c.drawLine(s * 0.22f, s * 0.36f, s * 0.5f, s * 0.64f, stroke)
            c.drawLine(s * 0.5f, s * 0.64f, s * 0.78f, s * 0.36f, stroke)
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
