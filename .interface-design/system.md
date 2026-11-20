# EmuSync design system (short form)

The authority is docs/superpowers/specs/2026-11-20-ui-design.md. The mockups are in design/mockups/.

- **Direction:** console system UI, neutral and dense. Quiet when all is well. Amber means waiting; red means needs you.
- **Depth:**
  - No shadows and no nested containers. Hairline rows (`--rule`) and one vertical pane divider (`--rule2`).
  - The cursor is an inverted row with a 3 px radius.
- **Spacing:** 4 px base.
  - Web: 40 px side padding, 46 px rows, 72 px top bar, 56 px hint bar.
  - App: 28 dp side padding, 40 dp rows, 52 dp top bar, 44 dp hint bar.
- **Type:**
  - Barlow Semi Condensed 600 for titles and tabs.
  - Barlow for UI: 600 primary, 400 secondary.
  - JetBrains Mono 13 px for data.
- **Tokens:** `--bg --ink --ink2 --ink3 --rule --rule2 --focus-bg --focus-ink --wait --fault --glyph` (values in the spec §3).
- **Buttons:** 40 px tall, 3 px radius.
  - Secondary: a 1.5 px `--rule2` outline.
  - Primary: `--ink` fill, at most one per pane.
  - Face-glyph disc: 20 px.
  - Focus: a 2 px `--ink` outline, offset 3 px.
- **Controls:**
  - L1/R1 for page tabs, L2/R2 for sub-tabs, A open, B back, X/Y the screen's two secondary actions.
  - Web glyphs appear only while a gamepad is connected.
