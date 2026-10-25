# Overlay textures

These are the default textures for the video filter. Each one is laid over the stream with a blend mode and an opacity slider. All of them were made from scratch with code, so there are no licence worries.

- **Tiled** textures are 512 x 512 and seamless, so they repeat across the video with no visible edges.
- **Full frame** textures (light leaks, bokeh, lens dirt, focus lines, VHS) are 1920 x 1080 and are stretched over the whole video once.

## How the blend modes treat them

Most textures are gray. The "neutral" colour is the colour that changes nothing:

- **Overlay / Soft Light** textures sit around mid gray (128). Lighter bits brighten, darker bits darken.
- **Multiply** textures are mostly white. Only the dark marks show.
- **Screen** textures are mostly black. Only the bright bits show, so they glow.

## What is in here

| File | What it is | Blend | Neutral |
|---|---|---|---|
| grunge_dirty.jpg | Dirty, blotchy surface with specks | Overlay | gray |
| grunge_scratched.jpg | Scratched metal | Overlay | gray |
| grunge_stained.jpg | Coffee rings and tea stains (warm tint) | Multiply | white |
| grunge_rust.jpg | Rusty metal (colour) | Overlay | gray |
| grunge_concrete.jpg | Concrete with pores and hairline cracks | Overlay | gray |
| paper_smooth.jpg | Clean paper with soft fibres | Multiply | white |
| paper_crumpled.jpg | Crumpled paper creases | Overlay | gray |
| paper_kraft.jpg | Brown kraft paper (colour) | Soft Light | none, it tints |
| paper_canvas.jpg | Woven art canvas | Overlay | gray |
| film_grain_fine.jpg | Fine film grain | Overlay | gray |
| film_grain_medium.jpg | Medium film grain | Overlay | gray |
| film_grain_coarse.jpg | Coarse film grain | Overlay | gray |
| film_dust_scratches.jpg | White dust, hairs and scratches | Screen | black |
| film_old_reel.jpg | Old film: vertical scratches and specks | Overlay | gray |
| light_leak_warm.jpg | Orange film light leak (colour) | Screen | black |
| light_leak_rose.jpg | Pink and purple light leak (colour) | Screen | black |
| light_leak_prism.jpg | Rainbow prism streak (colour) | Screen | black |
| light_bokeh.jpg | Soft pastel bokeh circles (colour) | Screen | black |
| manga_screentone_fine.png | Fine manga dot tone | Multiply | white |
| manga_screentone_medium.png | Medium manga dot tone | Multiply | white |
| manga_screentone_coarse.png | Big pop art dots | Multiply | white |
| manga_focus_lines.jpg | Manga focus lines bursting to the middle | Multiply | white |
| manga_action_lines.jpg | Horizontal manga speed lines | Multiply | white |
| manga_crosshatch.jpg | Pencil crosshatch shading | Multiply | white |
| retro_crt_scanlines.jpg | Old TV scanlines | Multiply | white |
| retro_vhs_noise.jpg | VHS static bands and tracking line | Screen | black |
| glass_rain.jpg | Raindrops on a window | Overlay | gray |
| glass_frosted.jpg | Frosted glass haze | Screen | black |
| glass_lens_dirt.jpg | Dusty, smudged camera lens | Screen | black |
| pattern_sparkle_glitter.jpg | Twinkly glitter sparkles (colour) | Screen | black |
| pattern_tiny_hearts.jpg | Tiny pastel hearts (colour) | Screen | black |
| pattern_tiny_stars.jpg | Tiny pastel stars (colour) | Screen | black |

## Adding or removing textures

- To remove one, just delete the file.
- To add your own, drop a JPG, PNG or WEBP image into this folder.
- `manifest.json` holds the nice name, category, suggested blend mode, opacity and tiling for each file. It is optional for files you add: anything not listed gets **Overlay, 60% opacity, tiled**. If you want different defaults, add an entry for your file in the same style.
