#!/usr/bin/env python3
"""M1 spike: how GCJ-02 and BD-09 offsets behave, for showing Chinese map tiles.

GCJ-02 is the obfuscated datum Chinese map providers (Amap, Tencent) draw their
tiles in; BD-09 is Baidu's further offset on top of it. GeoCrew stores true
coordinates (WGS 84 / CGCS2000) and only offsets for display, so it needs:

1. the forward offset (true -> GCJ-02 -> BD-09), from the published algorithm;
2. the inverse, to turn a tap on an offset map back into true coordinates;
3. a way to draw offset tiles in the right place.

This script measures, over a grid covering China:
- how large the offsets are;
- how accurate the inverse is, iterated and in one step;
- whether a whole tile can simply be shifted by the offset at its centre
  instead of being warped pixel by pixel, at each zoom level.

Usage: python3 gcj_offsets.py
"""
import math
import statistics

A = 6378245.0  # Krasovsky 1940 semi-major axis, as the algorithm uses
EE = 0.00669342162296594323  # its first eccentricity squared
X_PI = math.pi * 3000.0 / 180.0
EARTH = 6378137.0  # WGS 84, for distances and Web Mercator


def in_china(lon, lat):
    """The algorithm's own rough box: outside it, no offset is applied."""
    return 72.004 <= lon <= 137.8347 and 0.8293 <= lat <= 55.8271


def _lat(x, y):
    r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    r += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
    r += (160.0 * math.sin(y / 12.0 * math.pi) + 320.0 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return r


def _lon(x, y):
    r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    r += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
    r += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
    return r


def wgs_to_gcj(lon, lat):
    if not in_china(lon, lat):
        return lon, lat
    d_lat = _lat(lon - 105.0, lat - 35.0)
    d_lon = _lon(lon - 105.0, lat - 35.0)
    rad = lat / 180.0 * math.pi
    magic = 1 - EE * math.sin(rad) ** 2
    sqrt_magic = math.sqrt(magic)
    d_lat = d_lat * 180.0 / ((A * (1 - EE)) / (magic * sqrt_magic) * math.pi)
    d_lon = d_lon * 180.0 / (A / sqrt_magic * math.cos(rad) * math.pi)
    return lon + d_lon, lat + d_lat


def gcj_to_bd(lon, lat):
    z = math.sqrt(lon * lon + lat * lat) + 0.00002 * math.sin(lat * X_PI)
    theta = math.atan2(lat, lon) + 0.000003 * math.cos(lon * X_PI)
    return z * math.cos(theta) + 0.0065, z * math.sin(theta) + 0.006


def wgs_to_bd(lon, lat):
    return gcj_to_bd(*wgs_to_gcj(lon, lat))


def invert(forward, lon, lat, steps):
    """True coordinates whose forward offset lands on (lon, lat), by fixed-point iteration."""
    w_lon, w_lat = lon, lat
    for _ in range(steps):
        f_lon, f_lat = forward(w_lon, w_lat)
        w_lon -= f_lon - lon
        w_lat -= f_lat - lat
    return w_lon, w_lat


def metres(lon1, lat1, lon2, lat2):
    """Ground distance, accurate enough for offsets of a few hundred metres."""
    x = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2))
    y = math.radians(lat2 - lat1)
    return EARTH * math.hypot(x, y)


def mercator(lon, lat):
    return EARTH * math.radians(lon), EARTH * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def grid(step=0.25):
    """Points over mainland China and its coastal waters, within the algorithm's box."""
    lat = 18.0
    while lat <= 53.5:
        lon = 73.5
        while lon <= 135.0:
            yield lon, lat
            lon += step
        lat += step


def summary(values, unit):
    values = sorted(values)
    return "median %.3g %s, max %.3g %s" % (statistics.median(values), unit, values[-1], unit)


def offsets():
    for name, forward in (("GCJ-02", wgs_to_gcj), ("BD-09", wgs_to_bd)):
        d = [metres(lon, lat, *forward(lon, lat)) for lon, lat in grid()]
        print("%-6s offset from true coordinates: min %.0f m, %s" % (name, min(d), summary(d, "m")))


def inverse():
    for name, forward in (("GCJ-02", wgs_to_gcj), ("BD-09", wgs_to_bd)):
        for steps in (1, 2, 5):
            errors = []
            for lon, lat in grid(0.5):
                shown = forward(lon, lat)
                back = invert(forward, *shown, steps)
                errors.append(metres(lon, lat, *back))
            print("%-6s inverse, %d step%s: %s" % (name, steps, "" if steps == 1 else "s", summary(errors, "m")))


def tile_shift():
    """Shifting a whole tile by the offset at its centre: how far off are its corners, in pixels?"""
    for name, forward in (("GCJ-02", wgs_to_gcj), ("BD-09", wgs_to_bd)):
        for zoom in (8, 10, 12, 14, 16, 18):
            size = 2 * math.pi * EARTH / 2**zoom  # tile side in Web Mercator metres
            pixel = size / 256
            worst = []
            for lon, lat in grid(1.0):
                cx, cy = mercator(*forward(lon, lat))
                tx, ty = mercator(lon, lat)
                dx, dy = cx - tx, cy - ty  # the offset at the tile centre, in Mercator metres
                half = size / 2
                corner_lat = math.degrees(2 * math.atan(math.exp((ty + half) / EARTH)) - math.pi / 2)
                corner_lon = lon + math.degrees(half / EARTH)
                kx, ky = mercator(*forward(corner_lon, corner_lat))
                ox, oy = mercator(corner_lon, corner_lat)
                worst.append(math.hypot(kx - ox - dx, ky - oy - dy) / pixel)
            print("%-6s zoom %2d: corner error of a shifted tile %s" % (name, zoom, summary(worst, "px")))


def seams():
    """Neighbouring tiles each shifted by their own centre's offset: how far apart are they at the shared edge?"""
    for name, forward in (("GCJ-02", wgs_to_gcj), ("BD-09", wgs_to_bd)):
        for zoom in (10, 14, 18):
            size = 2 * math.pi * EARTH / 2**zoom
            pixel = size / 256
            gaps = []
            for lon, lat in grid(1.0):
                tx, ty = mercator(lon, lat)
                fx, fy = mercator(*forward(lon, lat))
                lon2 = lon + math.degrees(size / EARTH)  # the centre of the tile to the east
                gx, gy = mercator(*forward(lon2, lat))
                ux, uy = mercator(lon2, lat)
                gaps.append(math.hypot((gx - ux) - (fx - tx), (gy - uy) - (fy - ty)) / pixel)
            print("%-6s zoom %2d: seam between neighbouring shifted tiles %s" % (name, zoom, summary(gaps, "px")))


def grid_warp(cells=4):
    """Exact offsets on a (cells+1)^2 grid per tile, bilinear in between: how far off is any pixel?"""
    for name, forward in (("GCJ-02", wgs_to_gcj), ("BD-09", wgs_to_bd)):
        for zoom in (10, 14, 18):
            size = 2 * math.pi * EARTH / 2**zoom
            pixel = size / 256
            worst = []
            for lon0, lat0 in grid(2.0):
                x0, y0 = mercator(lon0, lat0)

                def offset(x, y):
                    lon = math.degrees(x / EARTH)
                    lat = math.degrees(2 * math.atan(math.exp(y / EARTH)) - math.pi / 2)
                    fx, fy = mercator(*forward(lon, lat))
                    return fx - x, fy - y

                step = size / cells
                nodes = [[offset(x0 + i * step, y0 + j * step) for i in range(cells + 1)] for j in range(cells + 1)]
                err = 0.0
                for k in range(1, 16):  # points inside the tile, off the grid lines
                    u, v = k / 16 * cells * 0.97, (16 - k) / 16 * cells * 0.97
                    i, j = int(u), int(v)
                    fu, fv = u - i, v - j
                    exact = offset(x0 + u * step, y0 + v * step)
                    interp = [
                        (1 - fu) * (1 - fv) * nodes[j][i][c] + fu * (1 - fv) * nodes[j][i + 1][c]
                        + (1 - fu) * fv * nodes[j + 1][i][c] + fu * fv * nodes[j + 1][i + 1][c]
                        for c in (0, 1)
                    ]
                    err = max(err, math.hypot(exact[0] - interp[0], exact[1] - interp[1]) / pixel)
                worst.append(err)
            print("%-6s zoom %2d: %dx%d grid per tile, bilinear between, error %s"
                  % (name, zoom, cells + 1, cells + 1, summary(worst, "px")))


if __name__ == "__main__":
    offsets()
    inverse()
    tile_shift()
    seams()
    grid_warp()
