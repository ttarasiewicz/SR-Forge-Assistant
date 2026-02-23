<div align="center">

# SR-Forge Assistant

**Intelligent IDE support for SR-Forge ML framework YAML configurations**

[![Build](https://img.shields.io/github/actions/workflow/status/ttarasiewicz/SR-Forge-Assistant/build.yml?style=flat-square)](https://github.com/ttarasiewicz/SR-Forge-Assistant/actions)
[![Version](https://img.shields.io/jetbrains/plugin/v/com.github.ttarasiewicz.srforgeassistant?style=flat-square)](https://plugins.jetbrains.com/plugin/com.github.ttarasiewicz.srforgeassistant)
[![Platform](https://img.shields.io/badge/platform-242%2B-blue?style=flat-square)](https://plugins.jetbrains.com/plugin/com.github.ttarasiewicz.srforgeassistant)

> **Screenshot:** `overview.png`
>
> *Show the plugin in action: a YAML config file with completion popup, scope highlighting, and folded interpolations visible*

</div>

---

[SR-Forge](https://gitlab.com/tarasiewicztomasz/sr-forge) is a Python framework for building super-resolution and image restoration pipelines through YAML configuration files. Instead of writing boilerplate training scripts, you declare datasets, transforms, models, and training loops as nested YAML nodes with `_target:` class references, `params:` mappings, and `${...}` / `%{...}` interpolations that wire everything together.

**SR-Forge Assistant** brings first-class IDE support to these configs. It resolves `_target:` FQNs to real Python classes, autocompletes parameter names from `__init__` signatures, validates references, folds interpolations to their resolved values, and lets you probe live dataset pipelines without leaving the editor.

---

## Highlights

| | Feature | What it does |
|---|---|---|
| :dart: | [**Target Intelligence**](#target-intelligence) | Autocomplete, navigate, document, and validate `_target:` classes and their parameters |
| :link: | [**Interpolation Support**](#interpolation-support) | Complete, fold, validate, and chain method calls on `${...}` / `%{...}` references |
| :art: | [**Visual Aids**](#visual-aids) | Scope highlighting and an editor toolbar for quick actions |
| :microscope: | [**Pipeline Probe**](#pipeline-probe) | Run a dataset pipeline on one sample and visualize Entry state at every transform step |

---

## Target Intelligence

Everything related to `_target:` FQN values and their `params:` mappings.

### `_target:` FQN Completion

Fuzzy-search Python classes and functions by fully qualified name. A dot (`.`) auto-triggers the popup for the next path segment &mdash; no need to press Ctrl+Space repeatedly.

> **Screenshot:** `target-completion.gif`
>
> *Show typing after `_target:`, dot popup appearing, selecting a class to insert the full FQN*

### Parameter Name Completion

Inside a `params:` block, get suggestions from the resolved class's `__init__` signature. Already-present parameters are automatically excluded.

> **Screenshot:** `param-completion.gif`
>
> *Show pressing Ctrl+Space inside `params:`, seeing parameter names from the target class*

### Go-to-Definition

**Ctrl+Click** (or **Ctrl+B**) on any `_target:` value to jump straight to the Python class or function definition.

> **Screenshot:** `goto-definition.gif`
>
> *Show Ctrl+Click on a `_target:` value, navigating to the Python source file*

### Hover Documentation

Hover over `_target:` values to see the class/function docstring and full signature. Hover over parameter keys inside `params:` to see their type, default value, and description.

> **Screenshot:** `hover-docs.png`
>
> *Show hover popup on a `_target:` value displaying docstring and signature, and a second hover on a param key showing type info*

### Parameter Stub Generation

One-click insertion of missing parameters. Available via:
- **Gutter icon** on `_target:` lines &mdash; click to insert all missing parameter stubs at once
- **Alt+Enter** intention &mdash; *"Generate parameter stubs for \_target"*

Default values are used where available, `null` otherwise.

> **Screenshot:** `param-stubs.gif`
>
> *Show clicking the gutter icon on a `_target:` line, then the params block filling in with stubs*

### Missing Required Parameters Inspection

Warning when required `__init__` parameters (those without defaults) are missing from `params:`. A quick-fix inserts them automatically.

> **Screenshot:** `missing-params-inspection.png`
>
> *Show yellow underline on a `_target:` value with the tooltip "Missing required parameter(s): root, split" and the quick-fix popup*

### Unknown Parameter Name Inspection

Catches typos in parameter keys. Flags unknown names and suggests the closest match by edit distance with a one-click rename quick-fix.

> **Screenshot:** `unknown-param-inspection.png`
>
> *Show yellow underline on a misspelled param key like `upsample_factr` with "Did you mean 'upsample_factor'?" tooltip*

---

## Interpolation Support

Completion, folding, validation, and chaining for `${...}`, `%{...}`, and `{ref: ...}` expressions.

### Path Completion

Segment-by-segment key path completion inside interpolation expressions. At each `.`, the popup shows direct children of the resolved node with value previews and type icons (mapping, sequence, scalar). Supports both dot notation (`list.0.key`) and bracket notation (`list[0].key`).

> **Screenshot:** `interpolation-completion.gif`
>
> *Show typing `${` to trigger popup, selecting segments one by one, value previews visible next to each suggestion*

### Code Folding

Interpolation expressions are resolved against the YAML document and displayed as fold placeholders. Folds collapse automatically on file open and can be toggled individually or all at once via the toolbar.

> **Screenshot:** `interpolation-folding.gif`
>
> *Show a file opening with interpolations auto-folded to resolved values, then clicking a fold to expand it, then using the toolbar toggle*

### Reference Validation

Unresolvable interpolation paths are flagged with an error annotation. Unknown resolver prefixes (e.g. `${ef:...}`) are also detected.

> **Screenshot:** `interpolation-validation.png`
>
> *Show a red/yellow annotation on an interpolation with an invalid path, and another on an unknown resolver prefix*

### Post-Interpolation Method Completion

After an interpolation reference like `${ref:model}`, type `.` to get method and attribute completions from the resolved Python class.

> **Screenshot:** `post-interpolation-methods.gif`
>
> *Show typing `.` after `${ref:model}` and seeing method suggestions from the resolved class*

---

## Visual Aids

### Scope Highlighting

The current `_target:` block gets a subtle background shading, and the parent key is rendered in bold &mdash; making it easy to see which block you're editing in deeply nested configs. Colors adapt to light and dark themes.

> **Screenshot:** `scope-highlighting.png`
>
> *Show a YAML file with the current block shaded and parent key bolded, contrasting with surrounding blocks*

### SR-Forge Editor Toolbar

A toolbar appears at the top of YAML files with quick-access buttons:

| Button | Action |
|---|---|
| **Toggle Interpolation Folds** | Collapse or expand all `${...}` / `%{...}` folds |
| **Run Pipeline Probe** | Launch the probe for the current file |
| **SR-Forge Settings** | Open the settings panel |

> **Screenshot:** `editor-toolbar.png`
>
> *Show the toolbar at the top of a YAML editor with the three action buttons visible*

---

## Pipeline Probe

Run the actual dataset pipeline on a single sample and visualize the Entry state at every transform step &mdash; field names, shapes, dtypes, value ranges, and memory sizes &mdash; without leaving the editor.

> **Screenshot:** `probe-overview.png`
>
> *Show the Pipeline Probe tool window with a full pipeline result: dataset header, transform step blocks, and color-coded field diffs*

### Usage

1. Open a YAML config file containing dataset definitions with `_target:` values
2. Click **Run Pipeline Probe** on the editor toolbar (or from the Pipeline Probe tool window via **Configure...**)
3. Select a dataset from the dialog. Override data root paths if needed
4. Click OK &mdash; the probe runs in the background with a progress indicator
5. Results appear in the **Pipeline Probe** tool window

> **Screenshot:** `probe-dialog.png`
>
> *Show the dataset selection dialog with detected datasets listed and path override fields*

### Results Visualization

Results display as a vertical flow diagram:

- **Blue header block** &mdash; dataset class name and `_target:` FQN
- **Step blocks** &mdash; one per transform, showing all Entry fields after that step
- **Color-coded diffs** &mdash; green (added), red (removed), yellow (modified), default (unchanged)
- **Field details** &mdash; click a step block to expand its field table (type, shape, dtype, min/max/mean/std, memory size, value preview)
- **Container drill-down** &mdash; dict and list fields are expandable to inspect nested contents

> **Screenshot:** `probe-results.gif`
>
> *Show expanding a step block to reveal the field table, then expanding a dict field to see nested contents*

> **Screenshot:** `probe-field-details.png`
>
> *Show a field table with columns: name, type, shape, dtype, min, max, mean, std, memory, preview*

### Nested Pipeline Support

For pipelines that wrap other datasets (e.g. `PatchedDataset` wrapping `LazyDataset`), the inner dataset is probed first, then the outer one. Inner results appear above, connected by a "Wrapped by ..." arrow.

> **Screenshot:** `probe-nested.png`
>
> *Show a nested pipeline result with inner dataset steps above and outer dataset steps below, connected by an arrow*

### Error Handling

If a transform fails mid-pipeline, all successful steps are shown normally, followed by a red error block with the error message and a collapsible traceback. If an inner dataset in a nested pipeline fails, the outer dataset is skipped with a notice.

> **Screenshot:** `probe-error.png`
>
> *Show a pipeline result where a transform failed: successful steps in normal colors, then a red error block with traceback*

---

## Configuration

Access via **Settings > Tools > SR-Forge Assistant**.

### Features

| Setting | Default | Description |
|---|:---:|---|
| Target completion | On | `_target:` FQN autocomplete |
| Target navigation | On | Go-to-definition on `_target:` values |
| Target documentation | On | Hover docs for `_target:` and params |
| Interpolation completion | On | Path completion inside `${...}` / `%{...}` |
| Interpolation folding | On | Code folding for interpolation expressions |
| Parameter stubs | On | Gutter icon and intention for stub generation |

### Scope Highlighting

| Setting | Default | Description |
|---|:---:|---|
| Block highlighting | On | Background shading for the current `_target:` block |
| Parent key highlighting | On | Highlight the parent key of the current block |
| Parent key font style | Bold | Font style for the highlighted parent key |
| Block color (light/dark) | `#F8FAFF` / `#2C2E33` | Block background color per theme |
| Parent key color (light/dark) | `#EDF2FC` / `#313438` | Parent key background color per theme |

### Interpolation Folding

| Setting | Default | Description |
|---|:---:|---|
| Fold on file open | On | Auto-collapse interpolations when a file is opened |
| Auto-collapse on caret exit | On | Re-fold an interpolation when the caret leaves it |
| Placeholder max length | 60 | Truncate long fold placeholders at this character count |

### Pipeline Probe

| Setting | Default | Description |
|---|:---:|---|
| Timeout | 120s | Maximum time for the probe script to run |

> **Screenshot:** `settings-panel.png`
>
> *Show the SR-Forge Assistant settings panel with all four sections visible*

---

## Installation

### JetBrains Marketplace

1. Open **Settings > Plugins > Marketplace**
2. Search for **SR-Forge Assistant**
3. Click **Install** and restart the IDE

### Manual Install

1. Download the latest `.zip` from [Releases](https://github.com/ttarasiewicz/SR-Forge-Assistant/releases)
2. Open **Settings > Plugins > :gear: > Install Plugin from Disk...**
3. Select the `.zip` file and restart the IDE

---

## Requirements

- **PyCharm Professional** or **IntelliJ IDEA** with the Python plugin
- **Build 242+** (2024.2 or newer)
- **Python SDK** configured in the project (required for `_target:` resolution)
- **SR-Forge** installed in the Python environment (required for Pipeline Probe)

---

## Development

```bash
# Run the plugin in a sandboxed IDE instance
./gradlew runIde

# Compile only (quick check)
./gradlew compileKotlin

# Build distributable plugin ZIP
./gradlew buildPlugin
```

Configure the target IDE platform and version in `gradle.properties` (`platformType=PY` for PyCharm, `platformType=IC` for IntelliJ IDEA).
