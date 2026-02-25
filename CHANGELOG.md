<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Changelog

All notable changes to SR-Forge Assistant are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com).

## [Unreleased]

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

[Unreleased]: https://github.com/ttarasiewicz/SR-Forge-Assistant/compare/v0.4.2...HEAD
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
