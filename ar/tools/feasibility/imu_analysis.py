"""Phase 00 test 4: reads the probe's sensor CSV (feasibility.Probe sensors) and reports the real event rates, the still
poses it finds (ahead, turned right, looking up, tilted right), the head's axes in the device frame, and the yaw drift
of the Game RotationVector (15) and RotationVector (11) while still.
Usage: python3 -I imu_analysis.py <csv> [--still-only]"""
import csv
import math
import sys

GYRO, ACCEL, RV, GRV = 4, 1, 11, 15


def qmul(a, b):
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return (aw * bw - ax * bx - ay * by - az * bz, aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx, aw * bz + ax * by - ay * bx + az * bw)


def qconj(q):
    return (q[0], -q[1], -q[2], -q[3])


def qnorm(q):
    n = math.sqrt(sum(c * c for c in q))
    return tuple(c / n for c in q)


def qrot(q, v):
    p = qmul(qmul(q, (0.0,) + tuple(v)), qconj(q))
    return p[1:]


def read(path):
    rows = {GYRO: [], ACCEL: [], RV: [], GRV: []}
    with open(path) as f:
        for r in csv.DictReader(f):
            t = int(r["type"])
            if t in rows:
                v = [float(r["v%d" % i]) for i in range(5)]
                rows[t].append((int(r["t_ns"]), v))
    return rows


def quat(v):
    # Android's rotation vector: values[0..2] = x, y, z; values[3] = w.
    return qnorm((v[3], v[0], v[1], v[2]))


def rates(rows):
    for t, name in ((GYRO, "gyroscope"), (ACCEL, "accelerometer"), (RV, "rotation vector"), (GRV, "game rotation vector")):
        s = rows[t]
        if len(s) < 2:
            print("%s: %d events" % (name, len(s)))
            continue
        dur = (s[-1][0] - s[0][0]) / 1e9
        gaps = sorted((s[i + 1][0] - s[i][0]) / 1e6 for i in range(len(s) - 1))
        print("%s: %d events in %.1f s = %.1f Hz; gap ms median %.2f p99 %.2f max %.1f" % (
            name, len(s), dur, (len(s) - 1) / dur, gaps[len(gaps) // 2], gaps[int(len(gaps) * 0.99)], gaps[-1]))


def still_segments(rows, threshold=0.06, min_s=3.0):
    """Runs of at least min_s seconds where the gyroscope's rate stays under threshold rad/s (smoothed over 0.2 s)."""
    g = rows[GYRO]
    out = []
    start = None
    window = []
    for t, v in g:
        window.append((t, math.sqrt(v[0] ** 2 + v[1] ** 2 + v[2] ** 2)))
        while window and t - window[0][0] > 0.2e9:
            window.pop(0)
        rate = sum(w for _, w in window) / len(window)
        if rate < threshold:
            if start is None:
                start = t
        else:
            if start is not None and (t - start) / 1e9 >= min_s:
                out.append((start, t))
            start = None
    if start is not None and (g[-1][0] - start) / 1e9 >= min_s:
        out.append((start, g[-1][0]))
    return out


def mean_quat(samples, a, b):
    ref = None
    acc = [0.0, 0.0, 0.0, 0.0]
    for t, v in samples:
        if a <= t <= b:
            q = quat(v)
            if ref is None:
                ref = q
            if sum(x * y for x, y in zip(q, ref)) < 0:
                q = tuple(-c for c in q)
            acc = [x + y for x, y in zip(acc, q)]
    return qnorm(tuple(acc)) if ref else None


def mean_vec(samples, a, b):
    vs = [v[:3] for t, v in samples if a <= t <= b]
    return [sum(c) / len(vs) for c in zip(*vs)] if vs else None


def axis_angle(q):
    if q[0] < 0:
        q = tuple(-c for c in q)
    angle = 2 * math.degrees(math.acos(max(-1.0, min(1.0, q[0]))))
    s = math.sqrt(max(1e-12, 1 - q[0] ** 2))
    return [c / s for c in q[1:]], angle


def fmt(v):
    return "(" + ", ".join("%+.2f" % c for c in v) + ")"


def heading_deg(q, up):
    """The yaw of a device axis perpendicular to `up` (device frame), in the sensor world's horizontal plane."""
    # Use the device axis most perpendicular to up as a reference "forward".
    cands = [(1, 0, 0), (0, 1, 0), (0, 0, 1)]
    ref = min(cands, key=lambda c: abs(sum(x * y for x, y in zip(c, up))))
    f = qrot(q, ref)
    return math.degrees(math.atan2(f[1], f[0]))


def drift(rows, t, a, b, up):
    pts = [(ts, heading_deg(quat(v), up)) for ts, v in rows[t] if a <= ts <= b]
    if len(pts) < 10:
        return None
    # unwrap
    out = []
    prev = None
    off = 0.0
    for ts, h in pts:
        if prev is not None:
            d = h - prev
            if d > 180:
                off -= 360
            elif d < -180:
                off += 360
        prev = h
        out.append(((ts - pts[0][0]) / 60e9, h + off))
    n = len(out)
    mx = sum(x for x, _ in out) / n
    my = sum(y for _, y in out) / n
    sxx = sum((x - mx) ** 2 for x, _ in out)
    slope = sum((x - mx) * (y - my) for x, y in out) / sxx if sxx else 0.0
    span = max(y for _, y in out) - min(y for _, y in out)
    return slope, span, out[-1][0]


def main():
    path = sys.argv[1]
    rows = read(path)
    rates(rows)
    segs = still_segments(rows)
    print("still segments (s from start):")
    t0 = rows[GYRO][0][0]
    for a, b in segs:
        q = mean_quat(rows[GRV], a, b)
        g = mean_vec(rows[ACCEL], a, b)
        print("  %6.1f-%6.1f (%5.1f s) grv=%s accel=%s" % ((a - t0) / 1e9, (b - t0) / 1e9, (b - a) / 1e9, fmt(q), fmt(g)))
    if "--still-only" not in sys.argv and len(segs) >= 6:
        ahead = segs[0]
        qa = mean_quat(rows[GRV], *ahead)
        up = mean_vec(rows[ACCEL], *ahead)
        n = math.sqrt(sum(c * c for c in up))
        up = [c / n for c in up]
        print("gravity (up) looking ahead, device frame:", fmt(up))
        for name, seg in (("turn right", segs[1]), ("look up", segs[3]), ("tilt right", segs[5])):
            d = qmul(qconj(qa), mean_quat(rows[GRV], *seg))
            axis, angle = axis_angle(d)
            print("  %-10s: device-frame axis %s, %.1f deg" % (name, fmt(axis), angle))
    last = segs[-1] if segs else None
    if last:
        up = mean_vec(rows[ACCEL], *last)
        n = math.sqrt(sum(c * c for c in up))
        up = [c / n for c in up]
        for t, name in ((GRV, "game rotation vector"), (RV, "rotation vector")):
            r = drift(rows, t, last[0], last[1], up)
            if r:
                print("drift while still (%s): %.2f deg/min over %.1f min, range %.2f deg" % (name, r[0], r[2], r[1]))


main()
