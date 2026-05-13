<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Changelog

All notable changes to SR-Forge Assistant are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com).

## [Unreleased]
### Added
- Pipeline Probe now supports **composite datasets** like `ConcatDataset`. The parser detects YAML lists of dataset-typed `_target:` entries (e.g. `params.datasets:`) and the Python probe runs exactly one branch per composite at a time, matching what `ConcatDataset.__getitem__` actually does at training time.
- Each composite dataset in the probe visualization now carries an **inline branch picker** — a small combo box embedded in the dataset's blue block listing every branch (`[0] PatchedDataset`, `[1] LazyDataset`, …). Switching it re-runs the probe with the new branch. Hovering items in the open popup live-previews each branch's path without selecting.
- **Animated path overlay** drawn on top of the YAML editor: orange Bezier spine grows from the user-selected dataset root down to the deepest leaf actually probed, diamond markers pop in at each ancestor key with an ease-out-back overshoot, and the leaf gets a continuous pulsing halo. Implemented as a `CustomHighlighterRenderer`. The overlay starts animating the moment the probe begins (the leaf is computable from the config), not on completion. Hovering branch options in a composite's dropdown temporarily redraws the trace through that hypothetical branch.
- **Stop / Clear button** in the probe tool window header. While a probe is running it cancels the Python process within ~100 ms (`ProbeExecutor` now polls the cancellation indicator instead of blocking on `waitFor(TIMEOUT)`). When idle it tears down all panel state — overlay, rendered blocks, `.npy` temp files, and the stored run config — to free memory before launching training.
### Changed
- `_target:` FQN resolution now mirrors sr-forge's runtime `ConfigResolver` exactly. Replaces the previous heuristic re-export matching (which could find classes sr-forge couldn't actually load) with a filesystem walk + true `from .x import Y` re-export following. Restores hover, go-to-definition, parameter completion, and inspections for project-local classes in projects that aren't pip-installed but live on PyCharm's content/source roots. Project-level result cache, invalidated on any PSI change.
- The probe script now receives a Kotlin-resolved whitelist of dataset paths so a YAML list-of-`_target:` entries is only treated as composite branches when those entries are actually Dataset subclasses; transforms (`transforms: [...]`) no longer get mistaken for branches.
- Alt+Enter quick-fixes and the parameter-stub intention now show an automatic preview of the resulting change (migrated to ModCommand APIs)
- Bumped IntelliJ Platform compile target from 2025.3.3 to 2026.1
- Migrated startup activities to the new `ProjectActivity` coroutine-based API
- Build supports reusing a locally installed IDE for `runIde` via the `platformLocalPath` Gradle property (skips the ~1 GB platform download)
### Fixed
- `PipelineProbeAction` now declares `ActionUpdateThread.BGT` instead of falling back to the deprecated `OLD_EDT` default
- Removed redundant `<applicationService>` registration for `SrForgeHighlightSettings` (already declared via `@Service` annotation)
### Removed
- Dropped support for PyCharm 2024.2 (raised `pluginSinceBuild` from 242 to 243). 2024.2 runs on JDK 17 and could not load the plugin's JDK 21 bytecode anyway
### Fixed (internal cleanup)
- Rewrote `ProbeToolWindowFactory` in Java to avoid Kotlin synthetic forwarder methods for `ToolWindowFactory`'s `@ApiStatus.Internal` defaults (eliminates 6 internal-API and 4 deprecated-API verifier warnings per IDE)
- Replaced experimental `WriteIntentReadAction.run` with `WriteAction.runAndWait` in `ProbeToolWindowPanel` (-4 experimental-API warnings)
- Cast `PyClass`/`PyNamedParameter` to `PsiNamedElement` for `.name` lookups so the bytecode references the stable interface instead of the experimental `PyAstClass`/`PyAstNamedParameter` methods
- Replaced the deprecated 4-arg `TextFieldWithBrowseButton.addBrowseFolderListener` with the modern `withTitle`/`withDescription` builder + 2-arg overload (eliminates the last scheduled-for-removal API usage)

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
