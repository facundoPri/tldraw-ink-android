# Changelog

## 0.3.1 — 2026-09-13

- Translate all app-owned interface text, accessibility labels, errors, and verification messages to English.
- Start the tldraw editor in English regardless of the device language or a previously saved locale.
- Update the README and regenerate documentation screenshots in English.

## 0.3.0 — 2026-09-13

- Introduce the Ink Share launcher name and adaptive pen-and-stroke icon, including a monochrome themed icon.
- Match added board controls to tldraw's neutral surfaces, blue accent, compact typography, and light/dark themes.
- Refine the Boards dialog and recent-connection rows, with accessible compact toolbar buttons.
- Add public build instructions, a local design sandbox, sample screenshots, and explicit test-board configuration.
- Publish a downloadable development APK as a testing prerelease, with checksum and source commit metadata.
- Prepare production license injection and external release signing. Production distribution remains pending an appropriate tldraw license.

## 0.2.2 — 2026-09-12

- Interpolate position and pressure before tldraw stroke compression to reduce small-curve changes at handoff.
- Preserve editable draw shapes and original sample vertices. Extra samples increase synchronized stroke size.
- On a synthetic small-curve test, preview/final pixel overlap improved from 0.780 to 0.928. This measures one controlled gesture, not physical pen latency or a universal rendering match.

## 0.2.1 — 2026-09-12

- Fix an invisible layout container blocking native Ink on the left side in landscape.
- Align preview width and low-zoom solid rendering with tldraw's stroke sizing.
- Verify native input in the left band and preview/final widths with injected stylus events.

## 0.2.0 — 2026-09-12

- Add live pressure-aware native preview, immersive system bars, and controls within tldraw's top panel.
- Add encrypted recent Share connections and current-board participant presence.
- Validate two-way shape and image sync and reconnection while the app remains running.
