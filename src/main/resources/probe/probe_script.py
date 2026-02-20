#!/usr/bin/env python3
"""
SR-Forge Pipeline Probe script.

Loads a YAML config, instantiates a dataset pipeline using SR-Forge's
ConfigResolver, captures Entry state after each transform step, and
outputs structured JSON between markers.

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

    from srforge.config import ConfigResolver
    resolver = ConfigResolver(cfg)

    return _probe_node(resolver, node, dataset_path)


def _probe_node(resolver, node, path):
    """Recursively probe a dataset node and its transforms."""
    from omegaconf import OmegaConf, DictConfig, open_dict

    inner_result = None

    # Check for wrapped datasets in params
    if 'params' in node and node.params is not None:
        for key in node.params:
            child = node.params[key]
            if isinstance(child, DictConfig) and '_target' in child:
                try:
                    inner_result = _probe_node(resolver, child, f'{path}.params.{key}')
                except Exception as e:
                    inner_result = {
                        'datasetName': str(child._target).split('.')[-1],
                        'datasetTarget': str(child._target),
                        'snapshots': [{
                            'stepLabel': str(child._target).split('.')[-1],
                            'stepIndex': 0, 'fields': [], 'isBatched': False,
                            'errorMessage': str(e),
                            'errorTraceback': traceback.format_exc(),
                        }],
                        'innerResult': None,
                    }
                break

    # If inner dataset had errors, don't probe the outer - data wouldn't reach it
    if inner_result is not None:
        _has_err = any(s.get('errorMessage') for s in inner_result.get('snapshots', []))
        if _has_err:
            target_name = str(node._target).split('.')[-1]
            return {
                'datasetName': target_name,
                'datasetTarget': str(node._target),
                'snapshots': [],
                'innerResult': inner_result,
            }

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
        saved_transforms_location = None
        saved_cache = {}
        if 'params' in node and node.params is not None:
            # Strip transforms so __getitem__ doesn't auto-apply them
            if 'transforms' in node.params:
                saved_transforms = node.params.transforms
                saved_transforms_location = 'params'
                node.params.transforms = None
            # Neutralize cache: never delete or write to cache dirs
            if 'cache_dir' in node.params:
                saved_cache['cache_dir'] = node.params.cache_dir
                node.params.cache_dir = None
            if 'recache' in node.params:
                saved_cache['recache'] = node.params.recache
                node.params.recache = False
        if saved_transforms is None and 'transforms' in node:
            saved_transforms = node.transforms
            saved_transforms_location = 'node'
            node.transforms = None

    # Use a fresh ConfigResolver so cached instances from inner probing
    # (which had transforms stripped) don't leak into the outer dataset.
    from srforge.config import ConfigResolver as _CR
    dataset = _CR(resolver.config)(node)
    target_name = str(node._target).split('.')[-1]

    # Restore original config values
    with open_dict(node):
        if saved_transforms is not None:
            if saved_transforms_location == 'params':
                node.params.transforms = saved_transforms
            else:
                node.transforms = saved_transforms
        if 'params' in node and node.params is not None:
            for k, v in saved_cache.items():
                node.params[k] = v

    # Get first entry (without transforms since we stripped them)
    entry = dataset[0]
    snapshots = [_snapshot_entry(entry, target_name, 0)]

    # Apply transforms one by one
    if transforms_source is not None:
        # Resolve %{...} reference via ConfigResolver
        resolved = resolver._resolve_path(transforms_source)
        transforms = list(resolved) if isinstance(resolved, (list, tuple)) else [resolved]
        for i, transform in enumerate(transforms):
            t_name = type(transform).__name__
            try:
                entry = transform(entry)
                snapshots.append(_snapshot_entry(entry, t_name, i + 1))
            except Exception as e:
                snapshots.append({
                    'stepLabel': t_name,
                    'stepIndex': i + 1,
                    'fields': [],
                    'isBatched': False,
                    'errorMessage': str(e),
                    'errorTraceback': traceback.format_exc(),
                })
                break
    elif transforms_raw is not None:
        # Instantiate transforms from raw config using ConfigResolver
        from omegaconf import OmegaConf as _OC
        for i, t_cfg_raw in enumerate(transforms_raw):
            try:
                t_cfg = _OC.create(t_cfg_raw) if isinstance(t_cfg_raw, dict) else t_cfg_raw
                transform = resolver(t_cfg)
                t_name = type(transform).__name__
                entry = transform(entry)
                snapshots.append(_snapshot_entry(entry, t_name, i + 1))
            except Exception as e:
                t_name = t_cfg_raw.get('_target', '?').split('.')[-1] if isinstance(t_cfg_raw, dict) else '?'
                snapshots.append({
                    'stepLabel': t_name,
                    'stepIndex': i + 1,
                    'fields': [],
                    'isBatched': False,
                    'errorMessage': str(e),
                    'errorTraceback': traceback.format_exc(),
                })
                break

    return {
        'datasetName': target_name,
        'datasetTarget': str(node._target),
        'snapshots': snapshots,
        'innerResult': inner_result,
    }


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
        result = _probe_dataset(
            cfg,
            probe_config['datasetPath'],
            probe_config.get('pathOverrides', {}),
        )
        elapsed = int((time.time() - start_time) * 1000)

        output = {
            'success': True,
            'result': result,
            'errorMessage': None,
            'errorTraceback': None,
            'executionTimeMs': elapsed,
        }
    except Exception as e:
        elapsed = int((time.time() - start_time) * 1000)
        output = {
            'success': False,
            'result': None,
            'errorMessage': str(e),
            'errorTraceback': traceback.format_exc(),
            'executionTimeMs': elapsed,
        }

    print('===PROBE_RESULT_BEGIN===')
    print(json.dumps(output, indent=2, default=str))
    print('===PROBE_RESULT_END===')


if __name__ == '__main__':
    main()
