---
name: MegaStream TV Pro
description: Optional TV Pro template only; not a replacement for Classic, Modern, or Studio.
colors:
  canvas: "#101318"
  surface: "#1a2028"
  surface-raised: "#28313d"
  border-web: "#3e4b59"
  text: "#f1f4f7"
  text-muted: "#b7c7d7"
  focus: "#56cfd7"
  action: "#ff5268"
  native-on-primary: "#071416"
  native-on-secondary: "#22070d"
  native-tertiary: "#e8c575"
  native-error: "#ff8591"
typography:
  web-heading:
    fontFamily: '"Cairo", "Segoe UI", "Helvetica Neue", Arial, sans-serif'
    fontSize: "22px"
    letterSpacing: "0"
  web-body:
    fontFamily: '"Cairo", "Segoe UI", "Helvetica Neue", Arial, sans-serif'
    fontSize: "16px"
    lineHeight: 1.45
    letterSpacing: "0"
  web-poster-label:
    fontFamily: '"Cairo", "Segoe UI", "Helvetica Neue", Arial, sans-serif'
    fontSize: "13px"
    letterSpacing: "0"
rounded:
  web-row: "2px"
  web-control: "4px"
spacing:
  web-tight: "4px"
  web-small: "8px"
  web-grid: "12px"
  web-section: "16px"
  web-wide: "20px"
  web-page: "32px"
components:
  web-play:
    backgroundColor: "{colors.action}"
    textColor: "{colors.canvas}"
    rounded: "{rounded.web-control}"
    padding: "10px 20px"
    typography: "{typography.web-body}"
  web-category:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.text}"
    rounded: "{rounded.web-row}"
    padding: "8px 10px"
  web-category-selected:
    backgroundColor: "{colors.surface-raised}"
    textColor: "{colors.focus}"
    rounded: "{rounded.web-row}"
    padding: "8px 10px"
  web-poster:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.web-control}"
  web-transport-button:
    rounded: "{rounded.web-control}"
    width: "44px"
    height: "44px"
---

# Design System: MegaStream TV Pro

## Overview

**Creative North Star: "MegaStream TV Pro"**

This document applies **only** when TV Pro is selected: web root `data-design="tvpro"` or Android `AppUiStyle.TV_PRO`. Classic, Modern, and Studio retain their own styling and behavior. This is an additive template, not a global redesign.

The approved IBPlayer Pro reference informs arrangement only: three-pane live browsing, category-led poster catalogs, season-first episode rows, and compact controls over full-bleed playback. Identity remains MegaStream, using existing content artwork and branding; do not copy the reference application's trademarks, logos, or bundled assets.

**Key Characteristics:**
- Content-first density with quiet dark surfaces.
- Cyan focus and coral actions, with explicit platform differences.
- Arabic RTL and remote navigation are first-class constraints.

Authority is the current source snapshot, not a deployment claim. Existing review captures are `.impeccable/review/desktop.png`, `.impeccable/review/mobile.png`, and `.impeccable/review/tv-android-movies.png`; they were not regenerated or visually validated in this documentation pass. Final visual review is separate and underway.

Sources (repository-relative paths; symbol/selector anchors avoid unstable line numbers):
- `PRODUCT.md`, Current Approved Work and Capabilities and Constraints: scope and identity.
- `webos/src/components/TvPro.css`, TV Pro root and scoped selectors: web palette, geometry, focus, responsive layout.
- `webos/src/index.css`, `body` and `.focusable`: inherited web font, line height, transition.
- `app/src/main/java/com/streamvault/app/ui/theme/Theme.kt`, `TvProColorScheme` and `MegaStreamTheme`: native palette and shape defaults.
- `app/src/main/java/com/streamvault/app/ui/design/AppTypography.kt`, `appTypographyFor` and `createTypography`: native type.
- `app/src/main/java/com/streamvault/app/ui/screens/vod/TvProCatalogLayouts.kt`, `CatalogStyleLayout` and `TvProCategoryButton`: rail/grid and category focus.
- `app/src/main/java/com/streamvault/app/ui/screens/vod/TvProPosterCard.kt`, `TvProPosterCard`: native poster states.
- `app/src/main/java/com/streamvault/app/ui/screens/series/TvProSeriesDetail.kt`, `TvProSeriesDetail` and `TvProEpisodeRow`: seasons, numbering, progress, directional focus.
- `app/src/main/java/com/streamvault/app/ui/screens/player/overlay/PlayerControlsChrome.kt`, TV Pro branches in `PlayerTopBar` / `PlayerBottomBar`, and `TvProTransport`: native playback chrome.

## Colors

### Primary
- **Cyan focus:** `focus` marks remote focus, selected web categories, active tabs, and ratings. Android calls this `primary`, with `native-on-primary` foreground.
- **Coral action:** `action` supplies web Play and episode progress. Android calls this `secondary`, used for selected category fill, metadata, favorites, and episode progress, with `native-on-secondary` foreground.

### Tertiary
- **Native gold:** `native-tertiary` is present in the Android TV Pro theme; do not infer a matching web role or additional use.

### Neutral
- **Canvas / surface / raised surface:** base screen, category rail, and denser rows or poster labels respectively.
- **Text / muted text:** primary titles and secondary metadata. Web dividers use `border-web`.
- **Native error:** `native-error` is Android's explicit error token, not a new web override.

**The Template Boundary Rule.** These tokens are normative for TV Pro only; never replace another template's palette or map Android role names blindly onto web variables.

## Typography

Web inherits Cairo with the frontmatter fallback stack. The documented web body role is used on detail copy and action controls, not a new global body override. TV Pro resets letter spacing to zero. Compact poster captions reduce to 12px at the narrow breakpoint; channel names are 15px normally and 18px at the wide breakpoint. Detail titles reach 28px, not a marketing-scale hero.

Android uses bundled Inter for English and locale-safe `FontFamily.SansSerif` otherwise, including Arabic. Relevant inherited roles are titleLarge (18sp/24sp, 600), titleMedium (16sp/22sp, 600), bodyMedium (14sp/18sp, 400), labelLarge (13sp/16sp, 500), labelMedium (12sp/14sp, 500), and labelSmall (11sp/13sp, 500). These are native units, not CSS pixels. Do not promote the platform fallback into a new display-font identity.

Preserve wrapping and ellipsis where implemented: Android category and episode titles allow two lines; poster labels allow one. Web detail copy wraps long words. The inherited web viewport-scaled base font is an implementation dependency, not a recommended new TV Pro type token.

## Layout

- **Live:** category rail, channel list, and preview/details pane. Web rail is 23% (maximum 300px; inherited minimum 190px); channel pane is 29%, rising to 35% at widths up to 1000px; preview takes the remainder. At widths up to 700px the category/list pair wraps above a full-width preview.
- **Catalog:** category rail beside poster grid. Web rail is 25%, bounded by 190px and 340px. Grid uses five columns by default, six at 1600px and above, three at 1000px and below, and two at 700px and below. Posters use 2:3 artwork. Grid gap is the frontmatter `web-grid`, reducing to `web-small` on narrow screens.
- **Android catalog:** rail is 25% of available width, clamped to 148-240dp. At 800dp and above use five fixed columns; below that the source uses `GridCells.Adaptive(100.dp)`. Grid padding is 12dp horizontally and 8dp vertically, with 10dp horizontal / 12dp vertical gaps. Do not equate these thresholds with web pixels.
- **Series:** season-first navigation beside numbered episode rows. Android splits panes 27% / 73%, with 132dp-wide 16:9 thumbnails. Web episode rows are 128px high, reducing to 104px at the narrow breakpoint; thumbnails occupy 30% normally and 36% narrowly. Season selection remains separate from episode focus.
- **Playback:** video remains full-bleed. Web top chrome uses a translucent canvas and bottom transport spans the viewport. Android TV Pro bottom chrome uses full width, transparent square-corner inner surface, and 24dp horizontal / 12dp vertical outer padding.

Use logical start/end placement for Arabic. Android category-to-content and season-to-episode focus explicitly respond to layout direction; retain focus restoration and Back navigation. Source wiring is evidence of intent, not proof of device behavior.

## Elevation & Depth

Catalog depth comes from tonal surfaces and borders, not floating panels. Web TV Pro focus removes inherited shadows and transforms and uses a 2px cyan outline inset by 2px. The seek input instead offsets its outline outward by 2px. Android poster/category/episode focus scale is 1, with a 2dp border and color changes.

Playback has a justified scrim exception: web top `#101318cc` and transport `#101318ed`; Android top fades from black at 0.8 alpha to transparent, while the bottom fades from transparent to black at 0.84 alpha. Do not prohibit these video-legibility gradients or transplant decorative legacy shadows into catalog focus.

## Shapes

Web rows use `web-row`; posters, actions, and tool buttons use `web-control`. Native category/episode rows are 2dp and poster cards 4dp. Native theme defaults are xSmall 4dp, small/pill 6dp, and medium/large/xLarge 8dp; explicit component shapes take precedence. TV Pro player panels are unframed rather than inheriting legacy rounded playback containers.

## Components

- **Category rail:** web selected state has cyan text and border on raised surface; focused state restores normal text while keeping the cyan outline. Android selection is coral with dark text; focus is cyan with dark text and an on-surface border. Selection and focus are distinct states.
- **Poster:** retain real content artwork, caption strip, rating badge, favorite and lock semantics. Focus changes the caption to cyan/dark text without scaling. Android caption height is 30dp; web caption minimum is 40px. Do not infer identical card sizing across platforms.
- **Episode row:** thumbnail, visible episode number, title, season/episode metadata, and conditional watch-progress strip. Android progress is 3dp; web is 5px. Preserve source progress conditions rather than adding artificial progress to unwatched episodes.
- **Play action:** coral web fill with dark text, bold weight, and 44px minimum height. Secondary favorite action remains raised-surface. Sidecar specimens are isolated web style excerpts, not production components or device screenshots.
- **Transport:** web tool targets are 44px square. Android play/pause is 48dp with a 28dp icon, and seek buttons are 44dp with 32dp spacing. Android seek track is cyan; web seek accent is coral. Android only shows the seek slider when duration is positive; keep live-state handling intact.
- **Motion and accessibility:** web focus inherits 0.12s ease transitions while TV Pro disables transform/shadow focus effects. Keep accessible names on icon-only controls, disabled state, selected state, keyboard/remote access, and directional focus. Do not treat hover as the only focus signal.

## Do's and Don'ts

### Do:
- Do scope every TV Pro visual rule to the selected optional template.
- Do preserve MegaStream identity, real content artwork, Arabic RTL, Back navigation, and remote focus.
- Do retain platform-specific selection, sizing, and seek colors documented from source.

### Don't:
- Don't restyle Classic, Modern, or Studio using this document.
- Don't copy another application's trademarks, logos, or bundled artwork.
- Don't claim device, playback, screenshot, or deployment verification from source inspection alone.
