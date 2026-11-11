# Design system

Tokens live in `ui/theme/Theme.kt`; components in `ui/components/Components.kt`.

## Brand

- Logo: `brand/muxlume-logo.png` (supplied artwork) and its vector trace `brand/muxlume-logo.svg`
  (97% pixel overlap). Android uses the same geometry: `drawable/brand_logo.xml`,
  adaptive icon foreground and monochrome themed icon, splash icon.
- Wordmark: `brand/muxlume-wordmark.png` (supplied artwork), used in the README and store graphics.
- Store art: `fastlane/metadata/android/en-US/images/icon.png` (512 px) and `featureGraphic.png`
  (1024×500), generated from the two PNGs above.

## Colour

| Token | Value | Use |
|---|---|---|
| Black | `#000000` | Background |
| Surface | `#111114` | Cards, sheets |
| Raised | `#1B1B20` | Pills, inputs, bottom bar |
| Outline | `#2A2A31` | Borders, inactive tracks |
| White | `#FFFFFF` | Primary text |
| Cream | `#FFF7E8` | Primary buttons, selected pills, accents on text |
| Orange | `#E65A00` | Live dot, progress, focus accents (never body text on black at small sizes) |
| Text secondary | `#A4A4AC` | Secondary text (≈ 8:1 on black) |
| Logo orange | `#FD5902` | Brand artwork only |

Primary actions are cream pills with black text (≈ 19:1). Orange is an accent, not a fill for text.

## Type, shape, spacing

- System sans; display and headline styles in Black/ExtraBold with tight tracking; section labels
  are 13 sp bold uppercase with wide tracking.
- Corners: cards 20 dp, hero 28 dp, pills fully rounded. Screen padding 20 dp. Touch targets ≥ 48 dp.

## Motion

- One easing curve (`0.2, 0, 0, 1`), 180 ms for small state changes, 260 ms for navigation.
- Card press: scale to 97%. Bottom bar: one sliding indicator. Player controls fade in 180 ms, out
  260 ms, auto-hide after 3.5 s. Buffering spinner only after 400 ms.
- Reduced motion (app setting or system "remove animations") turns all of it into instant changes.

## Artwork

Provider artwork only. When missing or loading, a flat placeholder shows the title's initials and a
small orange bar, so layouts never jump and no stock posters are bundled.

## Theming

Colour roles come from `LocalMux` (a `MuxPalette`), so screens read `MuxTheme.<role>` and never a
fixed colour. `MuxlumeTheme` builds a dark or light palette from the user's choice (System/Light/Dark),
the accent (five presets or Material You on Android 12+) and the AMOLED toggle, and also drives the
Material 3 `colorScheme` and the status-bar icon colour. Content that sits on artwork (the player,
the home hero) wraps its subtree in `LocalMux provides current.onMedia()` so it stays light-on-dark
in either theme.

## Planned

Layout density, shared-element transitions from cards to details, Android TV focus styling.
