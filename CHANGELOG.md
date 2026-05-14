<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Changelog

All notable changes to SR-Forge Assistant are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com).

## [Unreleased]

## [0.5.1] - 2026-05-14

### Added

#### Pipeline Probe — dataset picker
- Click the probe button to enter an in-editor selection mode where every dataset's `_target:` value is highlighted as a clickable pill. Hover previews the path; click runs the probe from that dataset.
- Pick mode shows a header strip with the dataset count and `◀ Prev / Next ▶` jump links. Right-scrollbar gold ticks mark every dataset position. If you're scrolled away from any dataset when entering pick mode, the editor automatically jumps to the first one.

#### Pipeline Probe — composite datasets
- `ConcatDataset` and other YAML-list-style composites are now first-class: the probe runs one branch at a time, matching what `__getitem__` actually does.
- Each composite block in the visualization carries an inline branch picker — switch branches and re-run with one click. Scrolling the wheel over a composite cycles its active branch.
- An animated YAML overlay traces the active path through your config, with alternative branches dimmed. Long paths animate at the same per-segment speed as short ones, so the animation feels consistent.

#### Pipeline Probe — running experience
- A live "Running" strip at the bottom of the tool window shows which step is currently being computed.
- A Stop button cancels a running probe within ~100 ms. When idle, it clears all probe state (overlays, snapshots, temporary tensor files) to free memory before training.
- Every block has a **Go to YAML definition** button — click it to jump to the corresponding `_target:` mapping and watch it pulse highlighted for a second.

#### Tensor Visualizer
- Slider drag now updates the image **live** instead of only on release.
- Per-dimension and channel inputs are now spinners with ±1 step buttons, mouse-wheel and keyboard arrow stepping, and typed input — essential for scrubbing through large dimensions one index at a time.

#### Editor support
- Hover docs, go-to-definition, parameter completion, and missing-parameter inspections now work for project-local classes in projects that aren't pip-installed (matches what sr-forge's runtime resolver does).
- Alt+Enter quick-fixes show a preview of the change before you apply it.

### Changed
- Dataset picking is now via the in-editor marker mode. The old modal "Configure dataset" dialog is gone — paths are edited directly in the YAML.
- Long tensor previews in expanded field rows now truncate with an ellipsis and the full text is shown in a tooltip. The visible truncation updates live as you resize the window.
- The probe tool window content never exceeds the visible width — long content clips/wraps inside each block instead of pushing trailing controls off the right edge.
- Expanded-field body indent is a single subtle step (used to compound to ~36 px for modified-field rows).

### Fixed
- After a `ConcatDataset`, the probe no longer surfaces stale Entry fields from previous probe runs (e.g. a `mask` field disappearing).
- The dataset picker's clickable highlight no longer collides visually with YAML key syntax colours or warning underlines.
- Boolean / integer tensors no longer render with grayscale shading when the visualizer window is smaller than the tensor — they stay pixel-exact.
- Expanded-field body content now anchors to the left consistently, regardless of value length.
- The probe tool window no longer logs a platform error on first open.

### Removed
- PyCharm 2024.2 support (JDK 17 incompatibility — the plugin requires JDK 21).

### Developer notes

These describe the internal mechanics; users shouldn't need to read this. Source the corresponding commits / file references for the full picture.

- **Marker-mode dispatchers** use `IdeEventQueue.EventDispatcher` instead of the 253-only `NonLockedEventDispatcher` so the plugin loads on 2024.3 / 2025.1 / 2025.2 (`NoSuchClassError` otherwise). Suppressed deprecation warnings inline.
- **`_target:` resolution** replaced the heuristic re-export matcher with a filesystem walk + `from .x import Y` re-export follower that mirrors sr-forge's `ConfigResolver` semantics. Project-level cache invalidated on PSI change.
- **Probe script `_disable_instance_cache`** now recursively descends into `list` / `tuple` / `dict` attributes — previously missed `ConcatDataset._datasets` (a list), so inner `PatchedDataset` / `LazyDataset` kept their pickle caches enabled and surfaced stale `Entry` shapes from prior live runs.
- **`ProbeChrome.<clinit>`** no longer reads from `SrForgeHighlightSettings.getInstance()`. The platform rejects service access during class init; the `current` field now defaults to `LegacyProbeChrome` and the setting is consulted lazily via `refresh()`.
- **Pipeline path overlay** moved from a `RangeHighlighter` + `CustomHighlighterRenderer` to a `JComponent` overlay added as a child of `editor.contentComponent`. Swing's paint order (`paintComponent → paintChildren`) puts the overlay strictly above the editor's indent-guide pass. Bezier routing pulls control points to `min(parent.x, child.x) − 24` so the trace never crosses text between markers; partial-curve animation uses De Casteljau subdivision; per-edge timing uses `pathTraceDurationMs × maxDepth` as total wall-clock.
- **Polished display-mode chrome** (`ProbeChrome` sealed class) was implemented end-to-end with a hot-swap path that records render actions and replays under a new chrome. The polished aesthetic (timeline + numbered nodes) didn't survive review, so `forCurrentMode()` short-circuits to `LegacyProbeChrome` and the dropdown is hidden — code preserved for future iteration.
- **Tensor Visualizer dtype-aware interpolation**: source `dtype.kind` is captured before the float64 cast in `viz_script.py`; resize uses `cv2.INTER_NEAREST` for `'b'`/`'i'`/`'u'` and `cv2.INTER_AREA` for `'f'`. Kotlin's `ImageViewPanel` mirrors this via an `isDiscreteDtype` field that forces `VALUE_INTERPOLATION_NEAREST_NEIGHBOR` regardless of zoom.
- **Tensor Visualizer slider live drag**: removed `valueIsAdjusting` guards; `requestVisualization()` now coalesces concurrent requests via a `vizPending` flag (latest control state wins, no backlog of stale renders). `JTextField` replaced with `JSpinner` for ±1 stepping; spinner maxima kept in sync with slider maxima at every adjustment site.
- **`FieldDetailPanel` BoxLayout fixes**: every Y_AXIS-laid-out container now has all children at `alignmentX = LEFT_ALIGNMENT`. `Box.createVerticalStrut` returns `Filler` with `CENTER` (0.5) by default, which shifted BoxLayout's baseline rightward and pulled LEFT-aligned siblings off-anchor by a content-width-dependent amount. Replaced via a `vstrut(h)` helper.
- **Dynamic value-row ellipsis**: detail rows changed from `FlowLayout` to `BorderLayout` with the value in `CENTER`. `JLabel.paintComponent` ellipsizes via `SwingUtilities.layoutCompoundLabel` when bounds are narrower than preferred — only triggers if the layout doesn't honour `preferredSize`, which `BorderLayout.CENTER` doesn't.
- **Tool-window horizontal clipping**: `contentAnchor` implements `Scrollable.getScrollableTracksViewportWidth = true`. Vertical anchoring (the previous `BorderLayout.NORTH` trick) is preserved.
- **Build / Verifier**: bumped IntelliJ Platform compile target to 2026.1; raised `pluginSinceBuild` to 243; full verifier matrix green for PC/PY 243-253. Migrated startup activities to `ProjectActivity`. `ProbeToolWindowFactory` rewritten in Java to avoid Kotlin synthetic forwarders. Various deprecated/experimental API replacements (full list in commit history).

## [0.4.4] - 2026-02-25
### Changed
- Restricted IDE compatibility to PyCharm and DataSpell via `com.intellij.modules.python` module dependency
- Expanded Plugin Verifier to cover all PyCharm versions from 2024.2 to 2025.3

## [0.4.3] - 2026-02-25
### Changed
- Automated release pipeline: build, test, sign, publish, and create GitHub Release in a single workflow
- Marketplace "What's New" section now auto-populated from CHANGELOG.md

## [0.4.2] - 2026-02-25
### Added
- JetBrains Marketplace description (`MARKETPLACE.md`) with full HTML and absolute image URLs
### Changed
- Updated `plugin.xml` description with all current features (Tensor Visualizer, Debugger Integration)

## [0.4.1] - 2026-02-25
### Fixed
- Eliminated `TypeEvalContext.Companion` internal API warnings by compiling against 2025.3 stable SDK
- Added Java shim (`TypeEvalContextCompat`) to avoid Kotlin Companion bytecode generation
### Changed
- Added screenshots and GIFs to README for all features

## [0.4.0] - 2026-02-24
### Added
- Tensor Visualizer &mdash; interactive dialog with per-dimension role assignment (H, W, C, Index, Mean, Max, Min, Sum), display modes (Min-Max, Histogram Eq, CLAHE, Custom Range), colormaps, channel modes, histogram, zoom/pan, pixel inspection, and PNG export
- Debugger integration &mdash; right-click a PyTorch Tensor or NumPy ndarray in the Threads & Variables panel to open the Tensor Visualizer
- "Visualize" button on tensor/ndarray fields in Pipeline Probe results
- Adjustable split between image and controls in the Tensor Visualizer
### Changed
- Overhauled README with banner, colored badges, collapsible sections, and full documentation for all features
- Added comprehensive CHANGELOG covering all releases

## [0.3.1] - 2026-02-24
### Fixed
- Resource leaks in probe script execution
- Disposed-editor crashes when closing files during probe runs
- Wrapper dataset diff computation for nested pipelines

## [0.3.0] - 2026-02-24
### Changed
- Broadened compatibility to PyCharm Community and IntelliJ IDEA Community (alongside Professional editions)

## [0.2.3] - 2026-02-24
### Fixed
- Platform compatibility with IntelliJ 2024.2 builds
### Changed
- Added plugin verifier to CI
- Removed verifyPlugin from CI to avoid disk space exhaustion

## [0.2.2] - 2026-02-24
### Fixed
- Deprecated `addBrowseFolderListener` API usage

## [0.2.1] - 2026-02-23
### Added
- Apache 2.0 license
- Introduction paragraph referencing SR-Forge framework in README

## [0.2.0] - 2026-02-23
### Added
- `${ref:...}` interpolation syntax support
- Post-interpolation method completion (type `.` after `${ref:model}` for class method suggestions)
- Interpolation reference validation (flags unresolvable paths and unknown resolver prefixes)
- Cache node visualization in Pipeline Probe
### Changed
- Pipeline Probe results now stream incrementally instead of waiting for completion
### Fixed
- Interpolation handling edge cases
- Transform reference resolution in Pipeline Probe

## [0.1.0] - 2026-02-20
### Added
- `_target:` FQN completion with dot-triggered popup
- Go-to-definition on `_target:` values (Ctrl+Click)
- Hover documentation for `_target:` classes and `params:` keys
- Parameter name completion inside `params:` blocks
- Parameter stub generation via gutter icon and Alt+Enter intention
- Missing required parameters inspection with quick-fix
- Unknown parameter name inspection with did-you-mean suggestions
- `${...}` interpolation path completion
- Interpolation code folding with auto-collapse on file open
- Scope highlighting for `_target:` blocks with configurable colors
- SR-Forge Editor Toolbar (toggle folds, run probe, settings)
- Pipeline Probe &mdash; run dataset pipeline on one sample and visualize Entry state at every transform step
- Nested pipeline support (inner datasets probed first)
- Pipeline error handling with traceback display
- Settings page with per-feature toggles, highlight colors, folding options, and probe timeout

[Unreleased]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.4.4...HEAD
[0.4.4]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.4.3...v0.4.4
[0.4.3]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.4.2...v0.4.3
[0.4.2]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.2.3...v0.3.0
[0.2.3]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/ttarasiewicz/SR-Forge-Assistant/commits/v0.1.0
