# SR-Forge Assistant

A PyCharm / IntelliJ plugin that brings full IDE support to [SR-Forge](https://github.com/ttarasiewicz/sr-forge) YAML configuration files. It understands `_target:` class references, `params:` mappings, `%{...}` / `${...}` interpolations, and the dataset/transform pipeline structure, giving you autocomplete, navigation, validation, and runtime pipeline inspection without leaving the editor.

## Features at a Glance

- **`_target:` autocomplete** &mdash; fuzzy-search Python classes and functions by FQN
- **Parameter autocomplete** &mdash; suggest parameter names from the resolved class signature
- **Go-to-definition** &mdash; Ctrl+Click on `_target:` values to jump to Python source
- **Hover documentation** &mdash; see docstrings and type info for `_target:` values and individual parameters
- **Interpolation completion** &mdash; segment-by-segment key path completion inside `${...}` and `%{...}`
- **Interpolation code folding** &mdash; fold interpolations to show their resolved values inline
- **Parameter stub generation** &mdash; one-click insertion of missing parameters via gutter icon or Alt+Enter
- **Missing required parameters inspection** &mdash; warning when required `__init__` parameters are not provided
- **Unknown parameter name inspection** &mdash; warning on typos in parameter keys, with "did you mean?" suggestions
- **Pipeline Probe** &mdash; run the actual dataset pipeline on one sample and visualize Entry state at every transform step

---

## Detailed Feature Guide

### `_target:` Autocomplete

**Problem:** SR-Forge configs reference Python classes by fully qualified name (e.g. `srforge.transform.entry.ChooseImages`). Typing these manually is slow and error-prone.

**How it works:** When the cursor is on a `_target:` value, the plugin indexes all Python classes and functions in the project and its dependencies, then offers them as autocomplete suggestions filtered by what you have typed so far.

**Usage:**
1. Type after `_target: ` and press **Ctrl+Space**, or just start typing.
2. A dot (`.`) automatically re-triggers the popup for the next path segment &mdash; no need to press Ctrl+Space again.
3. Select a suggestion to insert the full FQN.

---

### Parameter Autocomplete

**Problem:** Each `_target:` class accepts specific `__init__` parameters. Remembering their exact names across dozens of classes is impractical.

**How it works:** The plugin resolves the `_target:` FQN to a Python class, reads its `__init__` signature, and suggests parameter names when you type inside the `params:` mapping.

**Usage:**
1. Inside a `params:` block belonging to a `_target:`, press **Ctrl+Space**.
2. Parameters from the target class appear in the menu. Already-present parameters are excluded.

---

### Go-to-Definition

**Problem:** You see `_target: srforge.dataset.LazyDataset` in YAML and want to quickly look at what that class does.

**How it works:** The plugin registers a PSI reference on every `_target:` value that points to the resolved Python class or function.

**Usage:**
- **Ctrl+Click** (or **Ctrl+B**) on a `_target:` value to jump to the Python source file at the class definition.

---

### Hover Documentation

**Problem:** You want to check what a class does or what a specific parameter expects without navigating away from the YAML file.

**How it works:** The plugin provides documentation targets for both `_target:` values and parameter keys inside `params:`. Hovering shows the Python docstring, signature, type annotations, and default values.

**Usage:**
- **Hover** over a `_target:` value to see the class/function docstring and full signature.
- **Hover** over a parameter key inside `params:` to see its type, default value, and description.

---

### Interpolation Completion

**Problem:** SR-Forge configs use `%{path.to.key}` and `${path.to.key}` to reference other parts of the YAML tree. Typing these paths manually requires knowing the exact structure.

**How it works:** The plugin parses the current YAML document and offers segment-by-segment completion. At each `.`, it shows direct children of the resolved node, along with value previews and type icons (mapping, sequence, scalar).

**Usage:**
1. Type `${` or `%{` &mdash; the popup opens automatically.
2. Select a key segment. If it has children, a `.` is appended and the popup re-triggers.
3. For list items, both dot notation (`list.0.key`) and bracket notation (`list[0].key`) are supported.

Value previews appear next to each suggestion so you can verify you are selecting the right node.

---

### Interpolation Code Folding

**Problem:** Raw interpolation syntax like `%{preprocessing.training}` clutters the editor and hides what the value actually resolves to.

**How it works:** The plugin resolves each `${...}` and `%{...}` expression against the YAML document and displays the resolved value as a fold placeholder. Folds are collapsed by default when you open a file.

**Usage:**
- Interpolations fold automatically on file open.
- Click the fold gutter indicator (or press **Ctrl+.** at caret) to expand a single fold.
- Use the **Toggle Interpolation Folds** button on the SR-Forge editor toolbar to collapse/expand all folds at once.

---

### Parameter Stub Generation

**Problem:** A new `_target:` entry needs a `params:` block with the correct parameter keys. Manually looking up the class signature and typing each key is tedious.

**How it works:** The plugin compares the target class's `__init__` parameters against the existing `params:` keys and offers to insert stubs for any that are missing.

**Usage (gutter icon):**
- A gutter icon appears on `_target:` lines that have missing parameters.
- Click it to insert all missing parameter stubs at once into the `params:` block.

**Usage (intention action):**
- Place the cursor anywhere inside a block that has a `_target:` key.
- Press **Alt+Enter** and select **Generate parameter stubs for _target**.
- Missing parameter keys are inserted with placeholder values (default values where available, `null` otherwise).

---

### Missing Required Parameters Inspection

**Problem:** Forgetting a required parameter (one without a default value) causes a runtime error that can be hard to trace back to the config.

**How it works:** For every `_target:` value, the plugin resolves the class and checks whether all required `__init__` parameters are present in `params:`. If any are missing, a warning is shown.

**Usage:**
- A yellow underline appears on the `_target:` value with a message like: *"Missing required parameter(s): root, split"*.
- A quick-fix is available to insert the missing parameters automatically.

---

### Unknown Parameter Name Inspection

**Problem:** A typo like `upsample_factr` instead of `upsample_factor` silently passes through YAML parsing and only fails at runtime (or worse, is silently ignored).

**How it works:** The plugin checks every key inside `params:` against the target class's accepted parameters. Unknown keys are flagged, and the closest match (by edit distance) is suggested.

**Usage:**
- A yellow underline appears on the unrecognized parameter key with a message like: *"Unknown parameter 'upsample_factr'. Did you mean 'upsample_factor'?"*.
- A quick-fix renames the key to the suggested name.

---

### Pipeline Probe

**Problem:** SR-Forge pipelines chain datasets and transforms together: a dataset loads raw data, then transforms modify the Entry step by step. There is no way to see what fields an Entry holds, their shapes, dtypes, or value ranges at each stage without running the full training loop and adding print statements.

**How it works:** The plugin generates a self-contained Python script that:
1. Loads the YAML config using OmegaConf.
2. Uses SR-Forge's `ConfigParser` to instantiate the dataset **without** transforms.
3. Fetches one data sample (`dataset[0]`).
4. Applies each transform one by one, capturing a full snapshot of the Entry after each step (field names, Python types, tensor shapes, dtypes, min/max/mean/std, memory size, value previews).
5. For nested pipelines (e.g. `PatchedDataset` wrapping `LazyDataset`), probes the inner dataset first, then the outer one.

Results are displayed in a dedicated tool window as a vertical flow diagram with color-coded diffs between consecutive steps.

**Usage:**
1. Open a YAML config file that contains dataset definitions with `_target:` values.
2. Click **Run Pipeline Probe** on the SR-Forge editor toolbar (or from the Pipeline Probe tool window via **Configure...**).
3. A dialog appears listing all detected datasets. Select one.
   - If any data root paths don't exist on disk, override them via the file browser.
   - Optionally check **Save path changes to YAML file** to persist overrides.
4. Click OK. The probe runs in the background with a progress indicator.
5. The **Pipeline Probe** tool window opens showing the results:
   - **Blue header block** &mdash; the dataset class name and `_target:` FQN.
   - **Step blocks** &mdash; one per transform, showing all Entry fields after that step.
   - **Color-coded diffs** &mdash; green for added fields, red for removed, yellow for modified, default for unchanged.
   - **Field details** &mdash; click a step block header to expand/collapse its field table. Each field row shows type, shape, dtype, min/max/mean/std, memory size, and a value preview.
   - **Container drill-down** &mdash; dict and list fields are expandable to inspect nested contents.
   - **Nested pipelines** &mdash; inner datasets appear first, connected by a "Wrapped by ..." arrow to the outer dataset.
6. Use **Re-run** to repeat the probe with the same settings, or **Configure...** to pick a different dataset.

**Error handling:**
- If a transform fails mid-pipeline, all successful steps are shown normally, followed by a red error block indicating which transform failed, with the error message and a collapsible traceback.
- If the inner dataset in a nested pipeline fails, the outer dataset is skipped with a "Skipped &mdash; inner dataset pipeline failed" notice.

---

## SR-Forge Editor Toolbar

When you open a YAML file, a toolbar appears at the top of the editor with quick-access buttons:

- **Toggle Interpolation Folds** &mdash; collapse or expand all `${...}` / `%{...}` folds.
- **Run Pipeline Probe** &mdash; launch the probe for the current file.

---

## Requirements

- **PyCharm Professional** or **IntelliJ IDEA** with the Python plugin
- **Python SDK** configured in the project (required for `_target:` resolution and Pipeline Probe)
- **SR-Forge** installed in the Python environment (required for Pipeline Probe)

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
