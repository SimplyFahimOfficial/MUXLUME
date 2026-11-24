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

- Springs from the Material 3 Standard motion scheme (`Motion` in `Theme.kt`): *spatial* springs for things that move
  or resize (a little give, they keep their velocity when the target changes), *effects* springs for colour and
  opacity (no overshoot). Fast, default and slow speeds. Material3 1.4.0 keeps `MotionScheme` internal, so the six
  springs are copied from its `StandardMotionTokens`; use `MaterialTheme.motionScheme` once the BOM carries 1.5.
- Card press: scale to 97%. Bottom bar: one sliding indicator, positioned while drawing. Player controls fade in
  fast and out at the default speed, auto-hide after 3.5 s. Buffering spinner only after 400 ms.
- A tapped poster in Movies or Series grows into the detail page's header and back (shared bounds). Anything
  else that opens a page (Home, Search, Library) uses the plain slide and fade, as does a poster that has
  scrolled out of the grid.
- The watchlist icon draws its plus into a tick (animated vector, original artwork in `res/drawable`).
- Reduced motion (app setting, or the system animator scale at 0, followed live) turns all of it into
  instant changes: springs become snaps, no shared element, the icon is a plain drawing, artwork appears
  without a fade.

## Artwork

Provider artwork only. When missing or loading, a flat placeholder shows the title's initials and a
small orange bar, so layouts never jump and no stock posters are bundled.

## Theming

Screens read colour roles from `MuxTheme` (`Background`, `Surface`, `Raised`, `Outline`,
`TextPrimary`, `TextSecondary`, `Primary`/`OnPrimary`, `Accent`/`OnAccent`, `Error`, `Scrim`), never
fixed colours. `MuxlumeTheme` builds the palette from Appearance settings (System/Light/Dark, accent
or Material You, AMOLED) and also sets the Material 3 colour scheme and the system-bar icon colour.

- Each accent has a dark-theme and a darker light-theme value so accent text and labels on accent
  fills reach WCAG AA; `SupportTest` checks every accent in both themes.
- UI drawn over video or artwork (player, home hero) is wrapped in `OnMedia { }`, which switches to
  the dark palette with the same accent. White icons on a `Scrim` may use `Color.White` directly.
- The logo has a dark-framed copy (`brand_logo_light`) because the cream frame vanishes on the light
  background.

## Planned

Layout density, shared-element transitions from cards to details, Android TV focus styling.
