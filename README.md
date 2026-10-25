# PurpleTV ReVive - Continuation of the great work

**An Xposed / LSPosed module that brings third-party emotes, badges, pronouns and a pile of chat quality-of-life tweaks to the official Twitch app for Android.**

If you have ever wanted BetterTTV, FrankerFaceZ and 7TV emotes to actually render in Twitch mobile chat instead of showing up as plain text, this module does that, without replacing the Twitch app and without touching your login.

[![Latest release](https://img.shields.io/github/v/release/alienware377/purpletv-revive?label=download&style=flat-square)](https://github.com/alienware377/purpletv-revive/releases/latest)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square)](#requirements)
[![Xposed](https://img.shields.io/badge/Xposed-LSPosed%20%7C%20LSPatch-9146ff?style=flat-square)](#installation)
[![Website](https://img.shields.io/badge/website-purpletv--revive-7c3aed?style=flat-square)](https://alienware377.github.io/purpletv-revive/)

---

## Looking for PurpleTV or bttv-android?

Both brought 7TV, BetterTTV and FrankerFaceZ emotes to Twitch on Android, and bttv-android has stopped updating. PurpleTV ReVive picks up their chat features for today's Twitch app. It works as a module for the official app, so your Twitch login stays as it is, and there is a ready-to-install patched APK if you do not use LSPosed.

## What this module will never do

**There is no ad blocking, and there are no money-related mods of any kind.** That is a deliberate design decision, not a missing feature, and pull requests adding either will be declined.

- **No ad blocking.** Ads are how Twitch and the streamers on it get paid.
- **No Bits, subscription, Turbo or payment workarounds.** Nothing here touches money, anywhere.
- **No view-botting, auto-chatting, or faked engagement.**

We fully support Twitch and the people who make a living on it. This module is about the *chat reading* experience (emotes, badges, and readability), and it is not interested in taking anything away from the platform or from creators.

## Features

| Feature | What it does |
| --- | --- |
| **7TV emotes** | Global and per-channel 7TV emotes render inline in chat, including animated WEBP. |
| **BetterTTV (BTTV) emotes** | Global and channel BTTV emotes, animated GIF support. |
| **FrankerFaceZ (FFZ) emotes** | Global and channel FFZ emote sets. |
| **Homies emotes** | Global and channel Chatterino Homies emotes. Off by default. |
| **BTTV WEBP** | Optionally load BTTV emotes in WEBP format instead of GIF or PNG. |
| **Emote autocomplete** | Start typing an emote name, no leading colon needed, and pick it from a scrolling list just above the input. 7TV, BTTV and FFZ emotes come first, then your sub emotes, then other Twitch emotes. Hold one to pin it to the top whenever it matches, or to add it to the favorites bar. Pins can be kept per streamer. |
| **Favorites bar** | Long-press an emote in the picker, in chat or in the suggestions to add it to a bar at the top of the picker. 7TV, BTTV and FFZ favorites use each chat's own picture and are greyed out where that chat does not have them. Can be kept per streamer. |
| **7TV, BTTV and FFZ in the emote picker** | This channel's and the global 7TV, BTTV, FFZ and Homies emotes appear as their own sections at the bottom of Twitch's emote picker. |
| **Frequently Used** | Emotes you send from the favorites bar or the suggestion list count towards Twitch's Frequently Used row, like ones picked in Twitch's own picker. |
| **Emote picker backspace** | The backspace button in the emote picker deletes the whole last word or emote at once, or one letter, or keeps Twitch's own behaviour. |
| **Auto-claim channel points** | Bonus chests are claimed automatically in the background, whether or not the chest is on screen. |
| **Third-party badges** | FFZ, BTTV, Chatterino, DankChat, Chatsen and Homies badges shown next to usernames. |
| **Pronouns** | Pronoun tags from pronouns.alejo.io shown next to usernames. |
| **Timestamps** | Nine timestamp formats, including seconds, which Twitch itself cannot show. Shown even if Twitch's own chat timestamps are off. |
| **Deleted messages** | Show what a deleted message actually said, struck through, greyed, or plain. Works as an ordinary viewer, not only as a moderator, including messages already deleted before you joined. |
| **Highlighter** | Tint a whole chat row when it contains a word, or comes from a given user. Separate colour for `@mentions` of you. |
| **Blacklist** | Hide matching chat messages completely, with no gap left behind. |
| **`/me` styling** | Italic, coloured, or both. |
| **Username display style** | For people whose display name differs from their login: show both, the international name only, or the login only. |
| **Vibrate on mention** | Buzz when someone `@mentions` you, with an adjustable duration. |
| **Push notifications on patched builds** | A re-signed Twitch normally cannot register for push notifications. This lets it, so go-live alerts work on the patched APK too. |
| **Raid card for raiders** | When you arrive in a channel with a raid, the "is raiding with a party of" card shows in chat, which Twitch otherwise misses because raiders join a few seconds late. |
| **Skip link warning** | Open chat links straight away, without Twitch's "this link will take you to" confirmation. |
| **Pinned messages and Drops** | Hide pinned chat messages, or hide them 30 seconds after they appear, and dismiss the Twitch Drops notice, by pressing Twitch's own hide buttons. Hype trains, polls and predictions stay. |
| **Video filters** | A filters button in the player for lives and VODs, with over 35 filters: exposure, brightness, contrast, highlights, shadows, gamma, fade, clarity, sharpen, soft focus, temperature, tint, hue, saturation, vibrance, monotone, gradient map, posterize, invert, noise, vignette, colour fringe, pixelate, an overlay layer, moving bokeh lights and 11 animated glitch effects (slice shift, RGB split, VHS tracking, block corruption, wave, shake, flicker, static, rolling scanlines, channel swap, colour bleed). Most can be limited to the shadows, midtones or highlights. Every filter has a plain-words explanation. |
| **Overlays** | Lay a texture, your own picture or a solid colour over the video with any of Photoshop's 27 blend modes and an opacity slider. Comes with 32 built-in textures: grunge, paper, film grain, light leaks, bokeh, manga screentones and speed lines, retro scanlines, rain and more. |
| **Audio filters** | An Audio tab next to the video filters: volume boost, auto volume, compressor, limiter, voice clarity, rumble cut, and Bass and Treble or a full 16-band equaliser (30 Hz to 16k). Made for talk streams: even out the volume, tame loud moments and make voices clearer. Android 9 or newer. |
| **Player gestures** | In the landscape player, swipe up or down on the left half for brightness and on the right half for volume. |
| **Sleep timer** | A clock button in the player controls that pauses the stream after 15 minutes to 2 hours. |
| **VOD seek amounts** | Choose how far the VOD forward and back buttons jump, from 5 seconds to 2 minutes. |
| **Live refresh button** | A reload button in the live player controls for a stream that froze or fell behind (on by default). |
| **Player stats** | A button in the player controls that shows quality, bitrate, frame rate, how far behind live you are, the buffer, the network estimate and dropped frames over the video. |
| **Disable low latency** | Plays live streams a few seconds further behind, for fewer stutters and rebuffers on a weak connection. |
| **Chat appearance** | Font size, alternating row backgrounds, landscape chat width and opacity. |
| **Home tab tidy-up** | Home opens on Following by default. Optionally remove the Live and Clips tabs, and hide the Browse (search) tab or the Create (plus) button, in portrait and landscape. |
| **Following page tidy-up** | Hide the Categories, Continue Watching or Offline Channels sections of the Following page. Hide Twitch Stories on Home. |
| **Hide clutter** | Individually hide the chat header, leaderboards, message input, Bits button, Follow/Subscribe, the unfollow button, cast, the hype train banner, the create button, and the mature content label and warning. |
| **Update checker** | Tells you when a new PurpleTV ReVive version is out, with what changed, a Skip this version button, and an Update button that downloads the right file. Can be switched off under Updates. |

Everything is opt-in and individually toggleable, and the whole module can be switched off in one tap, see [Turning it off](#turning-it-off).

## Screenshots

<!-- Add screenshots to docs/ and reference them here -->

## Requirements

- Android 8.0 (API 26) or newer
- A rooted device with **[LSPosed](https://github.com/LSPosed/LSPosed)**, **or** a non-rooted device using **[LSPatch](https://github.com/LSPosed/LSPatch)**
- The official Twitch app installed. Each release is built for one Twitch version, named in its file names and release notes (currently **Twitch 31.1.2**).

## Installation

### Rooted: LSPosed

Easiest: in LSPosed, open the **Repository** tab, search for **PurpleTV ReVive** and install it from there. Updates then show up in LSPosed by themselves.

Or by hand:

1. Download the module file from the [latest release](https://github.com/alienware377/purpletv-revive/releases/latest): the one named `purpletv-revive-<version>-module-twitch<version>.apk` (older releases call it `purpletv-xp.apk`).
2. Install it like a normal app.
3. Open LSPosed → **Modules** → enable **PurpleTV ReVive**.
4. In the module's scope list, tick **Twitch**.
5. Force-stop and reopen Twitch.

### Non-rooted: LSPatch

1. Download the module file (`purpletv-revive-<version>-module-twitch<version>.apk`) from the [latest release](https://github.com/alienware377/purpletv-revive/releases/latest).
2. In LSPatch, select your installed Twitch app, choose **Integrated** patch mode, and add the module file as an embedded module.
3. Install the patched Twitch APK that LSPatch produces.

> Your Twitch login is preserved across reinstalls as long as the signing key stays the same.

## Usage

Open Twitch and start watching any channel. Third-party emotes load automatically for that channel.

- **Emote autocomplete:** just start typing an emote name in the chat box.
- **Add a favorite:** long-press any Twitch or subscriber emote in the native emote picker.
- **Remove a favorite:** long-press it in the favorites bar.
- **Settings:** open Twitch's own **Settings** screen; **PurpleTV** is the first row.

## Turning it off

The first row of the PurpleTV settings screen is **PurpleTV enabled**. Turn it off and restart the Twitch app, and you get the stock Twitch app back, with no hooks installed at all.

The settings row itself stays put while the module is switched off, showing just that one switch, so you can always turn it back on. Your other settings are remembered while it is off. **A restart of the Twitch app is required either way**, because the module installs its hooks once, when the app starts.

## Building from source

```bash
git clone https://github.com/alienware377/purpletv-revive.git
cd purpletv-revive/xposed
./gradlew :app:assembleRelease
```

The unsigned APK lands in `app/build/outputs/apk/release/`. Sign it with any key before installing.

## How it works

The Twitch Android app is obfuscated with R8, and class and method names change on every release, which is why most Twitch mods break within weeks of an update. This module deliberately avoids naming obfuscated classes. Instead it anchors on things R8 cannot rename:

- **Android framework classes** (`Activity`, `EditText`, `ViewGroup`) hooked directly.
- **Resource entry names** (`getResourceEntryName`): resource IDs survive obfuscation and are package-independent.
- **Kept public model packages** such as `tv.twitch.android.shared.chat.pub.messages.data`, which R8 preserves.
- **Twitch's own GraphQL gateway** for channel points, rather than the UI layer.

The result is a module that keeps working across Twitch updates far longer than smali-patch-based mods.

## Compatibility

Built and tested for **Twitch 31.1.2**, the version named in each release. **Twitch 31.5 and newer** moved chat, the emote menu and the message box to a new system, so the chat features (emotes, autocomplete, favorites, badges and so on) will most likely stop working there until the module is updated. Player and hide options may keep working. Because the module avoids obfuscated identifiers, it generally survives app updates. If a feature stops working after a Twitch update, please [open an issue](https://github.com/alienware377/purpletv-revive/issues) with your Twitch version number.

## FAQ

**Does this get my account banned?**
The module reads third-party emote APIs and uses Twitch's own GraphQL endpoints with your existing session. It does not automate chat messages or viewing. That said, any client modification is against Twitch's Terms of Service, so use it at your own risk.

**Does it block ads?**
No, and it never will. See [What this module will never do](#what-this-module-will-never-do). Ads pay Twitch and the streamers; blocking them takes money out of creators' pockets.

**Does it unlock subs, Bits, Turbo or anything paid?**
No. There are no money-related mods of any kind, deliberately.

**Why doesn't my passkey work?**
A passkey is tied to the app's signing certificate, and a patched build is re-signed with a different key, so the saved credential no longer matches. Sign in with your password, or with the Google, Amazon or Apple buttons. This affects any patched build and cannot be fixed on our side.

**How do I get the normal Twitch app back?**
Turn off **PurpleTV enabled** at the top of the module's settings and restart Twitch. See [Turning it off](#turning-it-off).

**Does it work without root?**
Yes, via LSPatch. See [Installation](#installation).

**Why do some favorites show as text instead of an image?**
The emote image is cached per channel. Favorites from a channel you are not currently watching fall back to their name until the image is fetched.

## Contributing

Questions, ideas and show-and-tell go in [Discussions](https://github.com/alienware377/purpletv-revive/discussions). Bug reports go in [Issues](https://github.com/alienware377/purpletv-revive/issues), and pull requests are welcome. When reporting a bug, include your Android version, Twitch app version, and whether you are using LSPosed or LSPatch.

## Credits

Emote data is provided by the [7TV](https://7tv.app), [BetterTTV](https://betterttv.com) and [FrankerFaceZ](https://frankerfacez.com) public APIs. Not affiliated with, endorsed by, or connected to Twitch Interactive, Inc.

## License

Apache License 2.0. See [LICENSE](LICENSE).

---

<sub>**Keywords:** twitch android mod, 7tv android, bttv android, ffz android, twitch xposed module, lsposed twitch, lspatch twitch, third party emotes twitch mobile, twitch emotes android app, betterttv android, frankerfacez android, twitch channel points auto claim, twitch chat transparency, purpletv, twitch mobile emotes</sub>
