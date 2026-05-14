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
import os
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

def _snapshot_value(v, key, depth=2, tensor_dir=None, path_prefix=""):
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
        'npyPath': None,
    }

    safe_key = re.sub(r'[^\w\-.]', '_', key)

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
        if tensor_dir is not None:
            try:
                npy_path = os.path.join(tensor_dir, f'{path_prefix}{safe_key}.npy')
                np.save(npy_path, v.detach().cpu().numpy())
                info['npyPath'] = npy_path
            except Exception:
                pass

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
        if tensor_dir is not None:
            try:
                npy_path = os.path.join(tensor_dir, f'{path_prefix}{safe_key}.npy')
                np.save(npy_path, v)
                info['npyPath'] = npy_path
            except Exception:
                pass

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
            info['children'] = [
                _snapshot_value(dv, str(dk), depth - 1,
                                tensor_dir=tensor_dir,
                                path_prefix=f'{path_prefix}{safe_key}__')
                for dk, dv in v.items()
            ]

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
            children = [
                _snapshot_value(v[i], f'[{i}]', depth - 1,
                                tensor_dir=tensor_dir,
                                path_prefix=f'{path_prefix}{safe_key}__')
                for i in range(limit)
            ]
            if len(v) > limit:
                children.append({
                    'key': f'... {len(v) - limit} more', 'pythonType': '',
                    'shape': None, 'dtype': None, 'minValue': None, 'maxValue': None,
                    'meanValue': None, 'stdValue': None,
                    'preview': None, 'sizeBytes': None, 'children': None,
                    'npyPath': None,
                })
            info['children'] = children

    elif isinstance(v, (str, int, float, bool)):
        info['preview'] = repr(v)
    else:
        info['preview'] = repr(v)[:100]

    return info


def _snapshot_entry(entry, label, step_index, tensor_dir=None):
    """Capture the state of an Entry's fields."""
    keys = sorted(entry.keys()) if hasattr(entry, 'keys') else []
    fields = [
        _snapshot_value(entry[key], key,
                        tensor_dir=tensor_dir,
                        path_prefix=f's{step_index}_')
        for key in keys
    ]
    return {
        'stepLabel': label,
        'stepIndex': step_index,
        'fields': fields,
        'isBatched': getattr(entry, 'is_batched', False),
    }


# ── Probe logic ─────────────────────────────────────────────────────

_PATH_PART_RE = re.compile(r'([^.\[]+)(?:\[(\d+)\])?')


def _navigate(cfg, path):
    """Walk an OmegaConf config by a dotted path that may carry [N] indices.

    Examples of paths the plugin produces:
        dataset.training
        dataset.training.params.dataset
        dataset.training.params.datasets[0]
        dataset.training.params.datasets[0].params.dataset
    """
    node = cfg
    for part in path.split('.'):
        if not part:
            continue
        m = _PATH_PART_RE.fullmatch(part)
        if not m:
            raise ValueError(f"Unparseable path segment: {part!r} in {path!r}")
        key, idx = m.group(1), m.group(2)
        node = node[key]
        if idx is not None:
            node = node[int(idx)]
    return node


def _probe_dataset(cfg, dataset_path, branch_choices,
                   dataset_paths, tensor_dir=None):
    """Probe a dataset using SR-Forge's ConfigResolver for instantiation."""
    from omegaconf import DictConfig

    # Navigate to the dataset config node
    node = _navigate(cfg, dataset_path)

    # Import ConfigResolver early — this registers the ${ref:...} OmegaConf
    # resolver, which is needed before any config value access.
    from srforge.config import ConfigResolver

    # Strip recache=True from all datasets to prevent shutil.rmtree during construction
    _strip_recache(node)

    resolver = ConfigResolver(cfg)

    _probe_node(resolver, node, dataset_path, branch_choices, dataset_paths,
                tensor_dir=tensor_dir)


def _strip_recache(node):
    """Set recache=False on all dataset configs to prevent shutil.rmtree during
    construction (recurses through single-child wrappers and composite lists)."""
    from omegaconf import DictConfig, ListConfig, open_dict
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
                elif isinstance(child, ListConfig):
                    for item in child:
                        if isinstance(item, DictConfig) and '_target' in item:
                            _strip_recache(item)


def _disable_instance_cache(dataset):
    """Disable caching on a live dataset instance and any nested datasets.

    Recursively descends into the dataset's own attributes AND into list/
    tuple/dict containers found among them. ConcatDataset stores its
    sub-datasets in a `self._datasets` list, so without container recursion
    we'd leave their caches enabled and the probe would silently read stale
    pickles from prior live runs (e.g. an Entry shape that's no longer what
    the current YAML produces).
    """
    if hasattr(dataset, '_cache_enabled'):
        dataset._cache_enabled = False
    for attr in vars(dataset).values():
        if attr is None or attr is dataset:
            continue
        if hasattr(attr, '_cache_enabled'):
            _disable_instance_cache(attr)
        elif isinstance(attr, (list, tuple)):
            for item in attr:
                if hasattr(item, '_cache_enabled'):
                    _disable_instance_cache(item)
        elif isinstance(attr, dict):
            for item in attr.values():
                if hasattr(item, '_cache_enabled'):
                    _disable_instance_cache(item)


def _probe_node(resolver, node, path, branch_choices, dataset_paths, tensor_dir=None):
    """Recursively probe a dataset node and its transforms, emitting events.

    Strategy: let ConfigResolver instantiate the dataset fully (it handles all
    reference resolution — {ref:...}, %{...}, inline configs). Then grab the
    live _transforms from the instance, clear them, and apply one by one.

    For composite datasets (e.g. ``ConcatDataset`` with a list of inner
    datasets in ``params.datasets``), recurse into the single branch the user
    picked in the selector dialog (``branch_choices``). Default to index 0
    when the user didn't supply a choice for this composite.

    Recursion is gated by ``dataset_paths`` — a whitelist of YAML-config paths
    that the Kotlin parser confirmed to be Dataset subclasses. Without this
    gate, lists of transforms (which also have ``_target:``) would be
    misidentified as composite-dataset branches.

    Returns True if any error occurred, False otherwise.
    """
    from omegaconf import DictConfig, ListConfig

    inner_had_errors = False

    # Identify wrapped datasets in params for separate step-by-step probing.
    # Handles both single-child wrappers (DictConfig with _target) and
    # multi-child composites (ListConfig of DictConfigs with _target).
    if 'params' in node and node.params is not None:
        for key in node.params:
            child = node.params[key]
            child_path = f'{path}.params.{key}'
            if isinstance(child, DictConfig) and '_target' in child:
                if child_path not in dataset_paths:
                    continue
                inner_had_errors = _probe_node(resolver, child, child_path,
                                               branch_choices, dataset_paths,
                                               tensor_dir=tensor_dir)
                if not inner_had_errors:
                    target_name = str(node._target).split('.')[-1]
                    _emit({'type': 'connector', 'label': f'Wrapped by {target_name}'})
                break
            elif isinstance(child, ListConfig) and len(child) > 0 and all(
                isinstance(it, DictConfig) and '_target' in it for it in child
            ):
                total = len(child)
                picked = branch_choices.get(child_path, 0)
                if picked < 0 or picked >= total:
                    picked = 0
                branch_path = f'{child_path}[{picked}]'
                if branch_path not in dataset_paths:
                    continue
                target_name = str(node._target).split('.')[-1]
                inner_had_errors = _probe_node(
                    resolver, child[picked], branch_path,
                    branch_choices, dataset_paths, tensor_dir=tensor_dir,
                )
                if not inner_had_errors:
                    _emit({'type': 'connector',
                           'label': f'Branch {picked + 1}/{total} of {target_name}'})
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
    snapshot = _snapshot_entry(entry, target_name, 0, tensor_dir=tensor_dir)
    _emit({'type': 'snapshot', **snapshot})

    # Apply saved transforms one by one
    had_errors = False
    for i, transform in enumerate(transforms):
        t_name = type(transform).__name__
        try:
            entry = transform(entry)
            snapshot = _snapshot_entry(entry, t_name, i + 1, tensor_dir=tensor_dir)
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

    tensor_dir = probe_config.get('tensorDir', None)
    if tensor_dir:
        os.makedirs(tensor_dir, exist_ok=True)

    start_time = time.time()
    try:
        cfg = _load_config(probe_config['yamlPath'])
        _probe_dataset(
            cfg,
            probe_config['datasetPath'],
            probe_config.get('branchChoices', {}),
            set(probe_config.get('datasetPaths', [])),
            tensor_dir=tensor_dir,
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
