package com.github.ttarasiewicz.srforgeassistant.probe

/**
 * Generates a self-contained Python probe script that:
 * 1. Loads a YAML config handling SR-Forge's %{...} syntax
 * 2. Uses SR-Forge's ConfigParser for instantiation
 * 3. Captures Entry state after each transform step
 * 4. Outputs JSON between markers
 */
object ProbeScriptGenerator {

    fun generate(
        yamlFilePath: String,
        datasetPath: String,
        pipeline: DatasetNode,
        pathOverrides: Map<String, String>,
        projectPaths: List<String>
    ): String {
        val escapedYaml = yamlFilePath.replace("\\", "\\\\")
        val escapedPaths = projectPaths.joinToString(", ") { "r\"${it.replace("\\", "\\\\")}\"" }
        val overridesDict = if (pathOverrides.isEmpty()) "{}"
        else pathOverrides.entries.joinToString(", ", "{", "}") { (k, v) ->
            "r\"${k.replace("\\", "\\\\")}\": r\"${v.replace("\\", "\\\\")}\""
        }
        val escapedDatasetPath = datasetPath.replace("\\", "\\\\")

        return buildString {
            appendLine(SCRIPT_HEADER)
            appendLine()
            appendLine("# Project source roots")
            appendLine("for _p in [$escapedPaths]:")
            appendLine("    if _p not in sys.path:")
            appendLine("        sys.path.insert(0, _p)")
            appendLine()
            appendLine(LOAD_CONFIG_FUNCTION)
            appendLine()
            appendLine(SNAPSHOT_FUNCTION)
            appendLine()
            appendLine(PROBE_FUNCTIONS)
            appendLine()
            appendLine("def main():")
            appendLine("    start_time = time.time()")
            appendLine("    try:")
            appendLine("        yaml_path = r\"$escapedYaml\"")
            appendLine("        dataset_path = \"$escapedDatasetPath\"")
            appendLine("        path_overrides = $overridesDict")
            appendLine()
            appendLine("        cfg = _load_config(yaml_path)")
            appendLine("        result = _probe_dataset(cfg, dataset_path, path_overrides)")
            appendLine("        elapsed = int((time.time() - start_time) * 1000)")
            appendLine()
            appendLine("        output = {")
            appendLine("            'success': True,")
            appendLine("            'result': result,")
            appendLine("            'errorMessage': None,")
            appendLine("            'errorTraceback': None,")
            appendLine("            'executionTimeMs': elapsed,")
            appendLine("        }")
            appendLine("    except Exception as e:")
            appendLine("        elapsed = int((time.time() - start_time) * 1000)")
            appendLine("        output = {")
            appendLine("            'success': False,")
            appendLine("            'result': None,")
            appendLine("            'errorMessage': str(e),")
            appendLine("            'errorTraceback': traceback.format_exc(),")
            appendLine("            'executionTimeMs': elapsed,")
            appendLine("        }")
            appendLine()
            appendLine("    print('===PROBE_RESULT_BEGIN===')")
            appendLine("    print(json.dumps(output, indent=2, default=str))")
            appendLine("    print('===PROBE_RESULT_END===')")
            appendLine()
            appendLine()
            appendLine("if __name__ == '__main__':")
            appendLine("    main()")
        }
    }

    private val SCRIPT_HEADER = "#!/usr/bin/env python3\n" +
        "# SR-Forge Pipeline Probe - auto-generated, do not edit.\n" +
        "import sys\n" +
        "import os\n" +
        "import re\n" +
        "import json\n" +
        "import traceback\n" +
        "import time\n" +
        "import importlib\n" +
        "import yaml"

    // Uses string concatenation to avoid triple-quote conflicts with Python docstrings
    private val LOAD_CONFIG_FUNCTION =
        "def _load_config(yaml_path):\n" +
        "    # Load a YAML config, handling SR-Forge's %{...} reference syntax.\n" +
        "    from omegaconf import OmegaConf\n" +
        "\n" +
        "    with open(yaml_path, 'r', encoding='utf-8') as f:\n" +
        "        raw = f.read()\n" +
        "\n" +
        "    # Quote bare %{...} references that aren't already quoted,\n" +
        "    # so PyYAML doesn't choke on the '%' character.\n" +
        "    raw = re.sub(r'(?<=: )(%\\{[^}]+\\})', r'\"\\1\"', raw)\n" +
        "    raw = re.sub(r'(?<=:\\t)(%\\{[^}]+\\})', r'\"\\1\"', raw)\n" +
        "\n" +
        "    data = yaml.safe_load(raw)\n" +
        "    cfg = OmegaConf.create(data)\n" +
        "    return cfg"

    @Suppress("SpellCheckingInspection")
    private val SNAPSHOT_FUNCTION = """
def _snapshot_value(v, key, depth=2):
    # Snapshot a single value with optional children for containers.
    import torch
    import numpy as np
    info = {
        'key': key,
        'pythonType': type(v).__module__ + '.' + type(v).__qualname__,
        'shape': None, 'dtype': None,
        'minValue': None, 'maxValue': None,
        'meanValue': None, 'stdValue': None,
        'preview': None, 'sizeBytes': None,
        'children': None,
    }
    if isinstance(v, torch.Tensor):
        info['shape'] = str(list(v.shape))
        info['dtype'] = str(v.dtype).replace('torch.', '')
        info['sizeBytes'] = v.element_size() * v.nelement()
        try:
            if v.numel() > 0:
                fv = v.float()
                info['minValue'] = f'{fv.min().item():.6g}'
                info['maxValue'] = f'{fv.max().item():.6g}'
                info['meanValue'] = f'{fv.mean().item():.6g}'
                info['stdValue'] = f'{fv.std().item():.6g}'
        except Exception:
            pass
        try:
            flat = v.flatten()[:8]
            info['preview'] = str(flat.tolist())
        except Exception:
            info['preview'] = f'Tensor{list(v.shape)}'
    elif isinstance(v, np.ndarray):
        info['shape'] = str(list(v.shape))
        info['dtype'] = str(v.dtype)
        info['sizeBytes'] = int(v.nbytes)
        try:
            if v.size > 0:
                fv = v.astype(float)
                info['minValue'] = f'{fv.min():.6g}'
                info['maxValue'] = f'{fv.max():.6g}'
                info['meanValue'] = f'{fv.mean():.6g}'
                info['stdValue'] = f'{fv.std():.6g}'
        except Exception:
            pass
        try:
            info['preview'] = str(v.flatten()[:8].tolist())
        except Exception:
            info['preview'] = f'ndarray{list(v.shape)}'
    elif isinstance(v, dict):
        info['preview'] = f'dict({len(v)} keys)'
        total_bytes = 0
        for dk, dv in v.items():
            if isinstance(dv, torch.Tensor):
                total_bytes += dv.element_size() * dv.nelement()
            elif isinstance(dv, np.ndarray):
                total_bytes += int(dv.nbytes)
        if total_bytes > 0:
            info['sizeBytes'] = total_bytes
        if depth > 0 and len(v) <= 200:
            info['children'] = [_snapshot_value(dv, str(dk), depth - 1) for dk, dv in v.items()]
    elif isinstance(v, (list, tuple)):
        type_name = type(v).__name__
        info['preview'] = f'{type_name}(len={len(v)})'
        total_bytes = 0
        for item in v:
            if isinstance(item, torch.Tensor):
                total_bytes += item.element_size() * item.nelement()
            elif isinstance(item, np.ndarray):
                total_bytes += int(item.nbytes)
        if total_bytes > 0:
            info['sizeBytes'] = total_bytes
        # Aggregate stats for homogeneous tensor/ndarray lists
        if len(v) > 0:
            first = v[0]
            if isinstance(first, torch.Tensor):
                info['shape'] = f'[{len(v)}x{list(first.shape)}]'
                try:
                    tensors = [t for t in v if isinstance(t, torch.Tensor) and t.numel() > 0]
                    all_vals = torch.cat([t.float().flatten() for t in tensors])
                    info['minValue'] = f'{all_vals.min().item():.6g}'
                    info['maxValue'] = f'{all_vals.max().item():.6g}'
                    info['meanValue'] = f'{all_vals.mean().item():.6g}'
                    info['stdValue'] = f'{all_vals.std().item():.6g}'
                except Exception:
                    pass
            elif isinstance(first, np.ndarray):
                info['shape'] = f'[{len(v)}x{list(first.shape)}]'
                try:
                    arrays = [a.astype(float).flatten() for a in v if isinstance(a, np.ndarray) and a.size > 0]
                    all_vals = np.concatenate(arrays)
                    info['minValue'] = f'{all_vals.min():.6g}'
                    info['maxValue'] = f'{all_vals.max():.6g}'
                    info['meanValue'] = f'{all_vals.mean():.6g}'
                    info['stdValue'] = f'{all_vals.std():.6g}'
                except Exception:
                    pass
        if depth > 0:
            limit = min(len(v), 200)
            children = [_snapshot_value(v[i], f'[{i}]', depth - 1) for i in range(limit)]
            if len(v) > limit:
                children.append({
                    'key': f'... {len(v) - limit} more', 'pythonType': '',
                    'shape': None, 'dtype': None, 'minValue': None, 'maxValue': None,
                    'meanValue': None, 'stdValue': None,
                    'preview': None, 'sizeBytes': None, 'children': None,
                })
            info['children'] = children
    elif isinstance(v, (str, int, float, bool)):
        info['preview'] = repr(v)
    else:
        info['preview'] = repr(v)[:100]
    return info


def _snapshot_entry(entry, label, step_index):
    # Capture the state of an Entry's fields.
    keys = sorted(entry.keys()) if hasattr(entry, 'keys') else []
    fields = [_snapshot_value(entry[key], key) for key in keys]
    return {
        'stepLabel': label,
        'stepIndex': step_index,
        'fields': fields,
        'isBatched': getattr(entry, 'is_batched', False),
    }
""".trimIndent()

    @Suppress("SpellCheckingInspection")
    private val PROBE_FUNCTIONS = """
def _apply_overrides(node, overrides):
    # Apply path overrides to 'root' params recursively.
    from omegaconf import DictConfig, open_dict
    if not overrides:
        return
    if isinstance(node, DictConfig):
        with open_dict(node):
            if 'params' in node and node.params is not None:
                if 'root' in node.params:
                    original = str(node.params.root)
                    if original in overrides:
                        node.params.root = overrides[original]
                for k in node.params:
                    child = node.params[k]
                    if isinstance(child, DictConfig) and '_target' in child:
                        _apply_overrides(child, overrides)


def _probe_dataset(cfg, dataset_path, path_overrides):
    # Probe a dataset using SR-Forge's ConfigParser for instantiation.
    from omegaconf import OmegaConf, DictConfig

    # Navigate to the dataset config node
    node = cfg
    for part in dataset_path.split('.'):
        node = node[part]

    # Apply path overrides
    _apply_overrides(node, path_overrides)

    # Set up ConfigParser with a mock run (we don't need W&B)
    from srforge.config import ConfigParser

    class _MockRun:
        resumed = False

    parser = ConfigParser(cfg, _MockRun())

    return _probe_node(parser, node, dataset_path)


def _probe_node(parser, node, path):
    # Recursively probe a dataset node and its transforms.
    from omegaconf import OmegaConf, DictConfig, open_dict

    inner_result = None

    # Check for wrapped datasets in params
    if 'params' in node and node.params is not None:
        for key in node.params:
            child = node.params[key]
            if isinstance(child, DictConfig) and '_target' in child:
                try:
                    inner_result = _probe_node(parser, child, f'{path}.params.{key}')
                except Exception as e:
                    inner_result = {
                        'datasetName': str(child._target).split('.')[-1],
                        'datasetTarget': str(child._target),
                        'snapshots': [{'stepLabel': f'ERROR: {e}', 'stepIndex': 0, 'fields': [], 'isBatched': False}],
                        'innerResult': None,
                    }
                break

    # Collect transforms config BEFORE stripping them
    transforms_raw = None
    transforms_source = None
    if 'params' in node and node.params is not None and 'transforms' in node.params:
        transforms_val = node.params.transforms
        if isinstance(transforms_val, str) and re.match(r'^%\{.+\}$', transforms_val.strip()):
            ref_path = re.match(r'^%\{(.+)\}$', transforms_val.strip()).group(1).strip()
            transforms_source = ref_path
        else:
            transforms_raw = OmegaConf.to_container(transforms_val, resolve=True)
    elif 'transforms' in node:
        transforms_val = node.transforms
        if isinstance(transforms_val, str) and re.match(r'^%\{.+\}$', transforms_val.strip()):
            ref_path = re.match(r'^%\{(.+)\}$', transforms_val.strip()).group(1).strip()
            transforms_source = ref_path
        else:
            transforms_raw = OmegaConf.to_container(transforms_val, resolve=True)

    # Strip transforms and neutralize cache before instantiation
    with open_dict(node):
        saved_transforms = None
        saved_cache = {}
        if 'params' in node and node.params is not None:
            # Strip transforms so __getitem__ doesn't auto-apply them
            if 'transforms' in node.params:
                saved_transforms = node.params.transforms
                node.params.transforms = None
            # Neutralize cache: never delete or write to cache dirs
            if 'cache_dir' in node.params:
                saved_cache['cache_dir'] = node.params.cache_dir
                node.params.cache_dir = None
            if 'recache' in node.params:
                saved_cache['recache'] = node.params.recache
                node.params.recache = False
        elif 'transforms' in node:
            saved_transforms = node.transforms
            node.transforms = None

    # Instantiate dataset using ConfigParser (same path as SR-Forge)
    dataset = parser(node)
    target_name = str(node._target).split('.')[-1]

    # Restore original config values
    with open_dict(node):
        if 'params' in node and node.params is not None:
            if saved_transforms is not None:
                node.params.transforms = saved_transforms
            for k, v in saved_cache.items():
                node.params[k] = v
        elif saved_transforms is not None:
            node.transforms = saved_transforms

    # Get first entry (without transforms since we stripped them)
    entry = dataset[0]
    snapshots = [_snapshot_entry(entry, target_name, 0)]

    # Apply transforms one by one
    if transforms_source is not None:
        # Resolve %{...} reference via ConfigParser
        resolved = parser._resolve_path(transforms_source)
        from srforge.config.legacy import ParamsList
        transforms = list(resolved) if isinstance(resolved, (list, ParamsList)) else [resolved]
        for i, transform in enumerate(transforms):
            t_name = type(transform).__name__
            try:
                entry = transform(entry)
                snapshots.append(_snapshot_entry(entry, t_name, i + 1))
            except Exception as e:
                snapshots.append({
                    'stepLabel': f'ERROR at {t_name}: {e}',
                    'stepIndex': i + 1,
                    'fields': [],
                    'isBatched': False,
                })
                break
    elif transforms_raw is not None:
        # Instantiate transforms from raw config using ConfigParser
        from omegaconf import OmegaConf as _OC
        for i, t_cfg_raw in enumerate(transforms_raw):
            try:
                t_cfg = _OC.create(t_cfg_raw) if isinstance(t_cfg_raw, dict) else t_cfg_raw
                transform = parser(t_cfg)
                t_name = type(transform).__name__
                entry = transform(entry)
                snapshots.append(_snapshot_entry(entry, t_name, i + 1))
            except Exception as e:
                t_name = t_cfg_raw.get('_target', '?').split('.')[-1] if isinstance(t_cfg_raw, dict) else '?'
                snapshots.append({
                    'stepLabel': f'ERROR at {t_name}: {e}',
                    'stepIndex': i + 1,
                    'fields': [],
                    'isBatched': False,
                })
                break

    return {
        'datasetName': target_name,
        'datasetTarget': str(node._target),
        'snapshots': snapshots,
        'innerResult': inner_result,
    }
""".trimIndent()
}
