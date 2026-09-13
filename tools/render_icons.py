#!/usr/bin/env python3
"""Renders the Graviton launcher icon set (legacy mipmap PNGs + Play Store icon) without PIL.

The artwork mirrors the vector drawables exactly: deep navy radial background, soft blue glow,
two tilted gravitational ring arcs and a rounded play triangle with a blue-to-violet gradient.
Pure numpy rasterization with 4x supersampling, written out as PNG via zlib.
"""
import math
import struct
import zlib
import numpy as np

# --- palette ---------------------------------------------------------------
import numpy as _np
NAVY_INNER = _np.array([0x1B / 255, 0x26 / 255, 0x50 / 255])
NAVY_OUTER = _np.array([0x0A / 255, 0x0F / 255, 0x24 / 255])
BLUE = _np.array([0x4D / 255, 0x8D / 255, 0xFF / 255])
RING_BLUE = _np.array([0x6F / 255, 0xA3 / 255, 0xFF / 255])
VIOLET = _np.array([0x9B / 255, 0x6B / 255, 0xFF / 255])
RING_VIOLET = _np.array([0xB1 / 255, 0x8C / 255, 0xFF / 255])

VP = 108.0  # design viewport, same as the vector drawables


def rounded_triangle_polygon(scale=1.0, center=54.0):
    """The rounded play triangle as a polygon (quadratic corners flattened)."""
    r = 19.0 * scale
    cut = 6.0 * scale
    cx = center
    A = (cx - r * math.sin(math.radians(60)), center - r * math.cos(math.radians(60)))
    B = (A[0], center + r * math.cos(math.radians(60)))
    C = (cx + r, center)

    def unit(p, q):
        dx, dy = q[0] - p[0], q[1] - p[1]
        L = math.hypot(dx, dy)
        return dx / L, dy / L

    uab = unit(A, B)
    ubc = unit(B, C)
    uca = unit(C, A)

    pts = []

    def edge(p, q):
        n = max(2, int(math.hypot(q[0] - p[0], q[1] - p[1]) * 4))
        for i in range(n):
            t = i / n
            pts.append((p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t))

    def quad(p, ctrl, q):
        for i in range(1, 13):
            t = i / 12
            x = (1 - t) ** 2 * p[0] + 2 * (1 - t) * t * ctrl[0] + t ** 2 * q[0]
            y = (1 - t) ** 2 * p[1] + 2 * (1 - t) * t * ctrl[1] + t ** 2 * q[1]
            pts.append((x, y))

    # start just after corner A, walk A->B
    start_after_A = (A[0] + cut * uab[0], A[1] + cut * uab[1])
    before_B = (B[0] - cut * uab[0], B[1] - cut * uab[1])
    edge(start_after_A, before_B)
    after_B = (B[0] + cut * ubc[0], B[1] + cut * ubc[1])
    quad(before_B, B, after_B)
    before_C = (C[0] - cut * ubc[0], C[1] - cut * ubc[1])
    edge(after_B, before_C)
    after_C = (C[0] + cut * uca[0], C[1] + cut * uca[1])
    quad(before_C, C, after_C)
    before_A = (A[0] - cut * uca[0], A[1] - cut * uca[1])
    edge(after_C, before_A)
    quad(before_A, A, start_after_A)
    return pts


def point_in_poly(px, py, poly):
    """Vectorized even-odd coverage test."""
    inside = np.zeros(px.shape, dtype=bool)
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        cond = ((yi > py) != (yj > py)) & (
            px < (xj - xi) * (py - yi) / (yj - yi + 1e-12) + xi
        )
        inside ^= cond
        j = i
    return inside


def lerp_color(c1, c2, t):
    t = np.clip(t, 0, 1)[..., None]
    return c1[None, None, :] * (1 - t) + c2[None, None, :] * t


def render(size, mark_scale=1.18, ss=4):
    W = size * ss
    # design-unit scale: viewport VP maps to the icon edge-to-edge
    s = W / VP
    ys, xs = np.mgrid[0:W, 0:W].astype(np.float64)
    x = xs / s
    y = ys / s

    # --- background: radial navy -------------------------------------------
    bcx, bcy, brad = 54.0, 46.0, 86.0
    t = np.clip(np.hypot(x - bcx, y - bcy) / brad, 0, 1)
    img = np.empty((W, W, 3), dtype=np.float64)
    img = NAVY_INNER[None, None, :] * (1 - t[..., None]) + NAVY_OUTER[None, None, :] * t[..., None]
    alpha = np.ones((W, W), dtype=np.float64)

    # --- glow ---------------------------------------------------------------
    gd = np.hypot(x - 54.0, y - 54.0) / 30.0
    galpha = np.clip(1 - gd, 0, 1) ** 2 * 0.16
    img = img * (1 - galpha[..., None]) + np.array(BLUE)[None, None, :] * galpha[..., None]

    # --- rings ---------------------------------------------------------------
    tilt = math.radians(-16)

    def ring(rx, ry, a0, a1, stroke, alpha_ring, cap_ends=True, grad_c1=RING_BLUE, grad_c2=RING_VIOLET):
        nonlocal img
        # transform to ring-local coords
        lx = x - 54.0
        ly = y - 54.0
        c, si = math.cos(tilt), math.sin(tilt)
        # rotate by -tilt to undo the group rotation
        px_ = lx * c + ly * si
        py_ = -lx * si + ly * c
        # ellipse angle of each pixel (only meaningful near the ring)
        ang = np.degrees(np.arctan2(-py_ / ry, px_ / rx))
        # ellipse "radius" function
        f = np.sqrt((px_ / rx) ** 2 + (py_ / ry) ** 2)
        dist = np.abs(f - 1.0) * min(rx, ry)
        # angular wrap: normalize angle to [0,360)
        ang = np.mod(ang, 360.0)
        in_arc = (ang >= a0) & (ang <= a1)
        cov = (dist <= stroke / 2.0) & in_arc
        # round caps
        if cap_ends:
            for (ex, ey) in ellipse_point(rx, ry, a0), ellipse_point(rx, ry, a1):
                dcap = np.hypot(x - ex, y - ey)
                cov |= dcap <= stroke / 2.0
        # gradient along global x
        g = (x - 25.8) / (82.2 - 25.8)
        gcol = lerp_color(grad_c1, grad_c2, g)
        a = cov.astype(np.float64) * alpha_ring
        img = img * (1 - a[..., None]) + gcol * a[..., None]

    def ellipse_point(rx, ry, deg):
        a = math.radians(deg)
        ex = 54.0 + rx * math.cos(a)
        ey = 54.0 - ry * math.sin(a)
        # apply group rotation (tilt) around (54,54)
        c, si = math.cos(tilt), math.sin(tilt)
        dx, dy = ex - 54.0, ey - 54.0
        return 54.0 + dx * c - dy * si, 54.0 + dx * si + dy * c

    # scale the mark around the centre
    def scale_coords(rx, ry):
        return rx * mark_scale, ry * mark_scale

    rx1, ry1 = scale_coords(26.0, 10.5)
    rx2, ry2 = scale_coords(30.0, 13.0)
    sw = 2.4 * mark_scale

    # outer ring segment: bottom arc 200..340 (local coords before tilt)
    ring(rx2, ry2, 200, 340, sw, 0.85)
    # inner ring back half (top, behind triangle)
    ring(rx1, ry1, 0, 180, sw, 0.45)

    # --- triangle -------------------------------------------------------------
    poly = rounded_triangle_polygon(scale=mark_scale)
    cov = point_in_poly(x, y, poly).astype(np.float64)
    g = (x - 37.5 * mark_scale) / ((73.0 - 37.5) * mark_scale)
    gcol = lerp_color(BLUE, VIOLET, g)
    img = img * (1 - cov[..., None]) + gcol * cov[..., None]

    # inner ring front half (bottom, in front of triangle)
    ring(rx1, ry1, 180, 360, sw, 0.95)

    # --- downsample ------------------------------------------------------------
    img = img.reshape(size, ss, size, ss, 3).mean(axis=(1, 3))
    alpha = alpha.reshape(size, ss, size, ss).mean(axis=(1, 3))
    rgb = np.clip(img * 255.0 + 0.5, 0, 255).astype(np.uint8)
    return rgb


def write_png(path, rgb):
    h, w, _ = rgb.shape
    raw = b"".join(b"\x00" + rgb[i].tobytes() for i in range(h))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    print("wrote", path, f"{w}x{h}")


if __name__ == "__main__":
    import os
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    base = os.path.join(root, "app", "src", "main", "res")
    sizes = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96, "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
    for folder, px in sizes.items():
        rgb = render(px)
        d = os.path.join(base, folder)
        os.makedirs(d, exist_ok=True)
        write_png(os.path.join(d, "ic_launcher.png"), rgb)
        write_png(os.path.join(d, "ic_launcher_round.png"), rgb)
    write_png(os.path.join(root, "app", "src", "main", "ic_launcher-playstore.png"), render(512, mark_scale=1.05))
