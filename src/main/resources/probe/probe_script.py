#!/usr/bin/env python3
"""
SR-Forge Pipeline Probe script.

Loads a YAML config, instantiates a dataset pipeline using SR-Forge's
ConfigResolver, captures Entry state after each transform step, and
emits structured JSON events for real-time UI rendering.

Invoked by the SR-Forge Assistant IntelliJ plugin.
Usage: python probe_script.py <config.json>
"""
import sys
import re
import json
import traceback
import time

import yaml


# ── Event emission ─────────────────────────────────────────────────

_EVENT_MARKER = '===PROBE_EVENT==='


def _emit(event):
    """Emit a single event as a one-line JSON string, flushed immediately."""
    print(f'{_EVENT_MARKER}{json.dumps(event, default=str)}', flush=True)


# ── Config loading ──────────────────────────────────────────────────

def _load_config(yaml_path):
    """Load a YAML config, handling SR-Forge's %{...} reference syntax."""
    from omegaconf import OmegaConf

    with open(yaml_path, 'r', encoding='utf-8') as f:
        raw = f.read()

    # Quote bare %{...} references that aren't already quoted,
    # so PyYAML doesn't choke on the '%' character.
    raw = re.sub(r'(?<=: )(%\{[^}]+\})', r'"\1"', raw)
    raw = re.sub(r'(?<=:\t)(%\{[^}]+\})', r'"\1"', raw)

    data = yaml.safe_load(raw)
    cfg = OmegaConf.create(data)
    return cfg


# ── Snapshot helpers ────────────────────────────────────────────────

def _snapshot_value(v, key, depth=2):
    """Snapshot a single value with optional children for containers."""
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
    """Capture the state of an Entry's fields."""
    keys = sorted(entry.keys()) if hasattr(entry, 'keys') else []
    fields = [_snapshot_value(entry[key], key) for key in keys]
    return {
        'stepLabel': label,
        'stepIndex': step_index,
        'fields': fields,
        'isBatched': getattr(entry, 'is_batched', False),
    }


# ── Probe logic ─────────────────────────────────────────────────────

def _apply_overrides(node, overrides):
    """Apply path overrides to 'root' params recursively."""
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
    """Probe a dataset using SR-Forge's ConfigResolver for instantiation."""
    from omegaconf import DictConfig

    # Navigate to the dataset config node
    node = cfg
    for part in dataset_path.split('.'):
        node = node[part]

    # Apply path overrides
    _apply_overrides(node, path_overrides)

    # Import ConfigResolver early — this registers the ${ref:...} OmegaConf
    # resolver, which is needed before any config value access.
    from srforge.config import ConfigResolver

    # Strip recache=True from all datasets to prevent shutil.rmtree during construction
    _strip_recache(node)

    resolver = ConfigResolver(cfg)

    _probe_node(resolver, node, dataset_path)


def _strip_recache(node):
    """Set recache=False on all dataset configs to prevent shutil.rmtree during construction."""
    from omegaconf import DictConfig, open_dict
    if not isinstance(node, DictConfig):
        return
    with open_dict(node):
        if 'params' in node and node.params is not None:
            if 'recache' in node.params:
                node.params.recache = False
            for k in node.params:
                child = node.params[k]
                if isinstance(child, DictConfig) and '_target' in child:
                    _strip_recache(child)


def _disable_instance_cache(dataset):
    """Disable caching on a live dataset instance and any nested datasets."""
    if hasattr(dataset, '_cache_enabled'):
        dataset._cache_enabled = False
    for attr in vars(dataset).values():
        if (attr is not None and attr is not dataset
                and hasattr(attr, '_cache_enabled')):
            _disable_instance_cache(attr)


def _probe_node(resolver, node, path):
    """Recursively probe a dataset node and its transforms, emitting events.

    Strategy: let ConfigResolver instantiate the dataset fully (it handles all
    reference resolution — {ref:...}, %{...}, inline configs). Then grab the
    live _transforms from the instance, clear them, and apply one by one.

    Returns True if any error occurred, False otherwise.
    """
    from omegaconf import DictConfig

    inner_had_errors = False

    # Identify wrapped datasets in params for separate step-by-step probing
    if 'params' in node and node.params is not None:
        for key in node.params:
            child = node.params[key]
            if isinstance(child, DictConfig) and '_target' in child:
                inner_had_errors = _probe_node(resolver, child, f'{path}.params.{key}')
                if not inner_had_errors:
                    target_name = str(node._target).split('.')[-1]
                    _emit({'type': 'connector', 'label': f'Wrapped by {target_name}'})
                break

    target_fqn = str(node._target)
    target_name = target_fqn.split('.')[-1]

    # If inner dataset had errors, skip outer dataset
    if inner_had_errors:
        _emit({
            'type': 'dataset_start',
            'datasetName': target_name,
            'datasetTarget': target_fqn,
            'datasetPath': path,
        })
        _emit({'type': 'skipped', 'reason': 'Inner dataset pipeline failed'})
        _emit({'type': 'dataset_end', 'datasetPath': path})
        return True

    _emit({
        'type': 'dataset_start',
        'datasetName': target_name,
        'datasetTarget': target_fqn,
        'datasetPath': path,
    })

    # Instantiate the dataset fully — ConfigResolver handles all reference
    # resolution ({ref:...}, %{...}, inline configs, etc.)
    # Note: recache was already stripped at config level to prevent shutil.rmtree.
    from srforge.config import ConfigResolver as _CR
    try:
        dataset = _CR(resolver.config)(node)
    except Exception as e:
        _emit({
            'type': 'init_error',
            'errorMessage': str(e),
            'errorTraceback': traceback.format_exc(),
        })
        _emit({'type': 'dataset_end', 'datasetPath': path})
        return True

    target_name = type(dataset).__name__

    # Grab transforms from the live instance, then clear them
    # (Dataset.__getitem__ wrapper reads self._transforms)
    transforms = list(getattr(dataset, '_transforms', None) or [])
    dataset._transforms = []

    # Disable caching on the instance and all nested datasets
    _disable_instance_cache(dataset)

    # Get raw entry (no transforms, no caching)
    entry = dataset[0]
    snapshot = _snapshot_entry(entry, target_name, 0)
    _emit({'type': 'snapshot', **snapshot})

    # Apply saved transforms one by one
    had_errors = False
    for i, transform in enumerate(transforms):
        t_name = type(transform).__name__
        try:
            entry = transform(entry)
            snapshot = _snapshot_entry(entry, t_name, i + 1)
            _emit({'type': 'snapshot', **snapshot})
        except Exception as e:
            _emit({
                'type': 'step_error',
                'stepLabel': t_name,
                'stepIndex': i + 1,
                'errorMessage': str(e),
                'errorTraceback': traceback.format_exc(),
            })
            had_errors = True
            break

    _emit({'type': 'dataset_end', 'datasetPath': path})
    return had_errors


# ── Main ────────────────────────────────────────────────────────────

def main():
    with open(sys.argv[1], 'r', encoding='utf-8') as f:
        probe_config = json.load(f)

    # Add project source roots to sys.path
    for p in probe_config.get('projectPaths', []):
        if p not in sys.path:
            sys.path.insert(0, p)

    start_time = time.time()
    try:
        cfg = _load_config(probe_config['yamlPath'])
        _probe_dataset(
            cfg,
            probe_config['datasetPath'],
            probe_config.get('pathOverrides', {}),
        )
    except Exception as e:
        _emit({
            'type': 'error',
            'errorMessage': str(e),
            'errorTraceback': traceback.format_exc(),
        })

    elapsed = int((time.time() - start_time) * 1000)
    _emit({'type': 'complete', 'executionTimeMs': elapsed})


if __name__ == '__main__':
    main()
