#!/usr/bin/env python3
"""
Tensor visualization renderer for SR-Forge Pipeline Probe.

Loads a .npy file, applies dimension slicing/reduction, display transforms,
and outputs a base64-encoded PNG image with histogram data.

Usage: python viz_script.py <config.json>
"""
import sys
import json
import base64
import io
import numpy as np


def main():
    with open(sys.argv[1], 'r', encoding='utf-8') as f:
        config = json.load(f)

    try:
        result = render(config)
    except Exception as e:
        import traceback
        result = {
            'image': None, 'histogram': None, 'stats': None,
            'pixelFormat': None, 'error': str(e),
            'errorTraceback': traceback.format_exc(),
        }

    print(json.dumps(result, default=str), flush=True)


def render(config):
    npy_path = config['npyPath']
    data = np.load(npy_path).astype(np.float64)

    dim_roles = config.get('dimRoles', [])
    channel_mode = config.get('channelMode', 'rgb')
    channel_action = config.get('channelAction', {'mode': 'index', 'value': 0})
    custom_rgb_channels = config.get('customRgbChannels', [0, 1, 2])
    display_mode = config.get('displayMode', 'normalized')
    clahe_clip = config.get('claheClipLimit', 2.0)
    clahe_tile = config.get('claheTileSize', 8)
    custom_min = config.get('customMin', 0.0)
    custom_max = config.get('customMax', 1.0)
    colormap_name = config.get('colormap', 'gray')
    output_width = config.get('outputWidth', 1024)

    # Step 1: Role-based axis processing
    # Identify H, W, C axes and reduce/slice all others
    h_axis = w_axis = c_axis = None
    reduce_actions = []

    for r in dim_roles:
        role = r['role']
        dim_idx = r['dimIndex']
        if role == 'h':
            h_axis = dim_idx
        elif role == 'w':
            w_axis = dim_idx
        elif role == 'c':
            c_axis = dim_idx
        else:
            reduce_actions.append(r)

    # Apply reductions from highest dim index to lowest to avoid index shifting
    reduce_actions.sort(key=lambda a: a['dimIndex'], reverse=True)
    for action in reduce_actions:
        dim_idx = action['dimIndex']
        role = action['role']
        if dim_idx >= data.ndim:
            continue
        if role == 'index':
            idx = min(int(action.get('value', 0)), data.shape[dim_idx] - 1)
            data = np.take(data, idx, axis=dim_idx)
        elif role == 'mean':
            data = np.mean(data, axis=dim_idx)
        elif role == 'max':
            data = np.max(data, axis=dim_idx)
        elif role == 'min':
            data = np.min(data, axis=dim_idx)
        elif role == 'sum':
            data = np.sum(data, axis=dim_idx)

    # Remap H/W/C axis indices after reductions removed some dims
    reduced_indices = sorted([a['dimIndex'] for a in reduce_actions])

    def remap_axis(orig_idx):
        return orig_idx - sum(1 for r in reduced_indices if r < orig_idx)

    if h_axis is not None:
        h_axis = remap_axis(h_axis)
    if w_axis is not None:
        w_axis = remap_axis(w_axis)
    if c_axis is not None:
        c_axis = remap_axis(c_axis)

    # Handle edge cases: missing H or W (e.g., 1D tensor or all-reduced)
    if h_axis is None:
        data = data[np.newaxis]
        h_axis = 0
        if w_axis is not None:
            w_axis += 1
        if c_axis is not None:
            c_axis += 1
    if w_axis is None:
        data = np.expand_dims(data, data.ndim)
        w_axis = data.ndim - 1

    # Transpose to (H, W, C) or (H, W)
    if c_axis is not None:
        perm = (h_axis, w_axis, c_axis)
        data = np.transpose(data, perm)
        channels = data  # [H, W, C]
    else:
        perm = (h_axis, w_axis)
        data = np.transpose(data, perm)
        channels = data[:, :, np.newaxis]  # [H, W, 1]

    shape_after = list(channels.shape)
    h, w, c = channels.shape

    # Step 3: Channel selection
    if channel_mode == 'custom_rgb':
        img = np.zeros((h, w, 3), dtype=np.float64)
        for i, ch_idx in enumerate(custom_rgb_channels[:3]):
            ch_idx = min(int(ch_idx), c - 1)
            img[:, :, i] = channels[:, :, ch_idx]
    elif channel_mode == 'rgb':
        if c >= 3:
            img = channels[:, :, :3]
        elif c == 1:
            img = np.repeat(channels, 3, axis=2)
        else:
            img = np.zeros((h, w, 3), dtype=np.float64)
            img[:, :, :c] = channels[:, :, :c]
    elif channel_mode == 'single_channel':
        reduce_mode = channel_action.get('mode', 'index')
        reduce_val = int(channel_action.get('value', 0))
        if reduce_mode == 'index':
            ch_idx = min(reduce_val, c - 1)
            img = channels[:, :, ch_idx:ch_idx + 1]
        elif reduce_mode == 'mean':
            img = np.mean(channels, axis=2, keepdims=True)
        elif reduce_mode == 'max':
            img = np.max(channels, axis=2, keepdims=True)
        elif reduce_mode == 'min':
            img = np.min(channels, axis=2, keepdims=True)
        elif reduce_mode == 'sum':
            img = np.sum(channels, axis=2, keepdims=True)
        else:
            img = channels[:, :, :1]
    else:
        img = channels[:, :, :1]

    is_single_channel = (img.shape[2] == 1)

    # Compute stats on the selected image data before display transform
    stats = {
        'min': float(np.min(img)),
        'max': float(np.max(img)),
        'mean': float(np.mean(img)),
        'std': float(np.std(img)),
        'shape': shape_after,
    }

    # Encode raw float pixel data for hover inspection (H, W, C) as float32
    raw_f32 = img.astype(np.float32)
    raw_pixels_b64 = base64.b64encode(raw_f32.tobytes()).decode('ascii')
    raw_pixel_shape = list(img.shape)  # [H, W, C]

    # Compute histogram
    num_bins = max(4, min(int(config.get('numBins', 256)), 4096))
    hist_data = img.flatten()
    if len(hist_data) > 0:
        hist_counts, hist_edges = np.histogram(hist_data, bins=num_bins)
    else:
        hist_counts = np.zeros(num_bins, dtype=int)
        hist_edges = np.linspace(0, 1, num_bins + 1)
    histogram = [
        {'binStart': float(hist_edges[i]),
         'binEnd': float(hist_edges[i + 1]),
         'count': int(hist_counts[i])}
        for i in range(num_bins)
    ]

    # Step 4: Apply display transform
    if display_mode == 'normalized':
        vmin, vmax = np.min(img), np.max(img)
        if vmax - vmin > 1e-8:
            display = (img - vmin) / (vmax - vmin) * 255.0
        else:
            display = np.full_like(img, 128.0)
    elif display_mode == 'custom_range':
        rng = max(custom_max - custom_min, 1e-8)
        display = np.clip((img - custom_min) / rng * 255.0, 0, 255)
    elif display_mode == 'histogram_eq':
        display = _histogram_equalize(img)
    elif display_mode == 'clahe':
        display = _apply_clahe(img, clahe_clip, clahe_tile)
    else:
        # Fallback: min-max
        vmin, vmax = np.min(img), np.max(img)
        if vmax - vmin > 1e-8:
            display = (img - vmin) / (vmax - vmin) * 255.0
        else:
            display = np.full_like(img, 128.0)

    display = np.round(np.clip(display, 0, 255)).astype(np.uint8)

    # Step 5: Apply colormap for single-channel images
    pixel_format = 'grayscale' if is_single_channel else 'rgb'
    if is_single_channel and colormap_name != 'gray':
        import cv2
        cmap_map = {
            'viridis': cv2.COLORMAP_VIRIDIS,
            'jet': cv2.COLORMAP_JET,
            'inferno': cv2.COLORMAP_INFERNO,
            'turbo': cv2.COLORMAP_TURBO,
        }
        cmap = cmap_map.get(colormap_name, cv2.COLORMAP_VIRIDIS)
        display = cv2.applyColorMap(display[:, :, 0], cmap)
        display = cv2.cvtColor(display, cv2.COLOR_BGR2RGB)
        pixel_format = 'rgb'
    elif is_single_channel:
        display = np.repeat(display, 3, axis=2)

    # Step 6: Resize for performance
    if w > output_width:
        scale = output_width / w
        new_h = max(1, int(h * scale))
        import cv2
        display = cv2.resize(display, (output_width, new_h),
                             interpolation=cv2.INTER_AREA)

    # Step 7: Encode to PNG base64
    from PIL import Image
    pil_img = Image.fromarray(display)
    buf = io.BytesIO()
    pil_img.save(buf, format='PNG')
    image_b64 = base64.b64encode(buf.getvalue()).decode('ascii')

    return {
        'image': image_b64,
        'histogram': histogram,
        'stats': stats,
        'pixelFormat': pixel_format,
        'rawPixels': raw_pixels_b64,
        'rawPixelShape': raw_pixel_shape,
        'totalChannels': int(c),
        'error': None,
    }


def _histogram_equalize(img):
    """Per-channel histogram equalization."""
    import cv2
    result = np.zeros_like(img)
    for c_idx in range(img.shape[2]):
        ch = img[:, :, c_idx]
        vmin, vmax = np.min(ch), np.max(ch)
        if vmax - vmin > 1e-8:
            normalized = ((ch - vmin) / (vmax - vmin) * 255).astype(np.uint8)
        else:
            normalized = np.full_like(ch, 128, dtype=np.uint8)
        result[:, :, c_idx] = cv2.equalizeHist(normalized).astype(np.float64)
    return result


def _apply_clahe(img, clip_limit, tile_size):
    """Per-channel CLAHE."""
    import cv2
    clahe = cv2.createCLAHE(clipLimit=clip_limit,
                             tileGridSize=(tile_size, tile_size))
    result = np.zeros_like(img)
    for c_idx in range(img.shape[2]):
        ch = img[:, :, c_idx]
        vmin, vmax = np.min(ch), np.max(ch)
        if vmax - vmin > 1e-8:
            normalized = ((ch - vmin) / (vmax - vmin) * 255).astype(np.uint8)
        else:
            normalized = np.full_like(ch, 128, dtype=np.uint8)
        result[:, :, c_idx] = clahe.apply(normalized).astype(np.float64)
    return result


if __name__ == '__main__':
    main()
