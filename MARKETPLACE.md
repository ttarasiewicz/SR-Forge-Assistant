<!-- JetBrains Marketplace plugin description. Paste into the marketplace description field. -->

<a href="https://gitlab.com/tarasiewicztomasz/sr-forge">SR-Forge</a> is a Python framework for building super-resolution and image restoration pipelines through YAML configuration files. <b>SR-Forge Assistant</b> brings first-class IDE support to these configs &mdash; autocomplete, navigation, validation, live pipeline visualization, and tensor inspection, all without leaving the editor.

<h2>Target Intelligence</h2>

<b>_target: FQN Completion</b> &mdash; Fuzzy-search Python classes by fully qualified name. A dot auto-triggers the popup for the next segment.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/target-completion.gif" alt="Target completion">

<b>Parameter Name Completion</b> &mdash; Inside a <code>params:</code> block, get suggestions from the resolved class's <code>__init__</code> signature.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/param-completion.gif" alt="Parameter completion">

<b>Go-to-Definition</b> &mdash; Ctrl+Click on any <code>_target:</code> value to jump to the Python class definition.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/goto-definition.gif" alt="Go to definition">

<b>Hover Documentation</b> &mdash; Hover over <code>_target:</code> values to see docstrings and signatures. Hover over parameter keys to see their type, default, and description.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/hover-docs.png" alt="Hover documentation">

<b>Parameter Stub Generation</b> &mdash; One-click insertion of missing parameters via gutter icon or Alt+Enter intention.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/param-stubs.gif" alt="Parameter stubs">

<b>Inspections</b> &mdash; Warnings for missing required parameters and unknown parameter names, with quick-fix suggestions.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/missing-params-inspection.gif" alt="Missing params inspection">

<h2>Interpolation Support</h2>

<b>Path Completion</b> &mdash; Segment-by-segment key path completion inside <code>${...}</code> expressions with value previews and type icons.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/interpolation-completion.gif" alt="Interpolation completion">

<b>Code Folding</b> &mdash; Interpolations are resolved and displayed as fold placeholders. Collapse automatically on file open, toggle individually or all at once.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/interpolation-folding.gif" alt="Interpolation folding">

<b>Reference Validation</b> &mdash; Unresolvable paths are flagged with error annotations. Unknown resolver prefixes are also detected.

<b>Post-Interpolation Method Completion</b> &mdash; Type <code>.</code> after a <code>${ref:...}</code> expression to get method completions from the resolved Python class.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/post-interpolation-methods.gif" alt="Post-interpolation methods">

<h2>Visual Aids</h2>

<b>Scope Highlighting</b> &mdash; The current <code>_target:</code> block gets a subtle background shading and bold parent key, adapting to light and dark themes.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/scope-highlighting.png" alt="Scope highlighting">

<b>Editor Toolbar</b> &mdash; Quick-access buttons for toggling interpolation folds, running the pipeline probe, and opening settings.

<h2>Pipeline Probe</h2>

Run the actual dataset pipeline on a single sample and visualize the Entry state at every transform step &mdash; field names, shapes, dtypes, value ranges, and memory sizes.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/probe-overview.png" alt="Pipeline Probe overview">

<ul>
<li>Color-coded diffs: green (added), red (removed), yellow (modified)</li>
<li>Expandable field tables with type, shape, dtype, min/max/mean/std, and memory size</li>
<li>"Visualize" button on tensor/ndarray fields opens the interactive Tensor Visualizer</li>
<li>Nested pipeline support with inner dataset probing</li>
<li>Error handling with traceback display</li>
</ul>

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/probe-results.gif" alt="Probe results">

<h2>Tensor Visualizer</h2>

Interactive visualization of tensor and ndarray fields with per-dimension role assignment, display modes, colormaps, channel modes, zoom/pan, pixel inspection, histogram, and PNG export.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/tensor-visualizer.gif" alt="Tensor Visualizer">

<ul>
<li><b>Dimension roles</b>: H, W, C, Index, Mean, Max, Min, Sum</li>
<li><b>Display modes</b>: Min-Max, Histogram Eq, CLAHE, Custom Range</li>
<li><b>Colormaps</b>: gray, viridis, jet, inferno, turbo</li>
<li><b>Channel modes</b>: RGB, Custom RGB, Single Channel</li>
</ul>

<h2>Debugger Integration</h2>

Right-click any <b>PyTorch Tensor</b> or <b>NumPy ndarray</b> variable in the debugger's Threads &amp; Variables panel and select "Visualize Tensor" to open the tensor visualizer instantly.

<img src="https://raw.githubusercontent.com/ttarasiewicz/SR-Forge-Assistant/main/.github/readme/debugger-visualize.gif" alt="Debugger integration">

<h2>Requirements</h2>

<ul>
<li>PyCharm (Community or Professional) or IntelliJ IDEA with the Python plugin</li>
<li>Build 242+ (2024.2 or newer)</li>
<li>Python SDK configured in the project</li>
<li>SR-Forge installed in the Python environment (for Pipeline Probe)</li>
</ul>
