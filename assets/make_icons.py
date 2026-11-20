"""Builds Mellow's launcher and notification icons as Android vector drawables, straight
from assets/MellowLogoForeground.svg, so they are centred by construction: the logo's own
centre (330, 330 in its 660 unit square) is placed on the exact centre of each canvas."""
import os, re
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
svg = open(ROOT + "/assets/MellowLogoForeground.svg").read()
K = 0.5522847498

def fmt(v): return ("%.3f" % v).rstrip("0").rstrip(".")
def circle(cx, cy, rx, ry=None):
    ry = rx if ry is None else ry
    return f"M{fmt(cx-rx)},{fmt(cy)}a{fmt(rx)},{fmt(ry)} 0 1,0 {fmt(2*rx)},0a{fmt(rx)},{fmt(ry)} 0 1,0 {fmt(-2*rx)},0z"
def ellipse_through(cx, cy, rx, ry, m):
    """An ellipse under an affine matrix, as four cubic curves. Affine maps of curves are exact."""
    a, b, c, d, e, f = m
    T = lambda x, y: (a*x + c*y + e, b*x + d*y + f)
    pts = [(cx+rx, cy), (cx+rx, cy+K*ry), (cx+K*rx, cy+ry), (cx, cy+ry), (cx-K*rx, cy+ry), (cx-rx, cy+K*ry), (cx-rx, cy),
           (cx-rx, cy-K*ry), (cx-K*rx, cy-ry), (cx, cy-ry), (cx+K*rx, cy-ry), (cx+rx, cy-K*ry), (cx+rx, cy)]
    p = [T(*q) for q in pts]
    s = f"M{fmt(p[0][0])},{fmt(p[0][1])}"
    for i in range(1, 13, 3):
        s += "C" + " ".join(f"{fmt(x)},{fmt(y)}" for x, y in p[i:i+3])
    return s + "z"

shapes = []   # (kind, pathData, fill, stroked)
for tag, attrs in re.findall(r"<(circle|ellipse|path)\s([^>]*)/>", svg):
    A = dict(re.findall(r'([\w-]+)="([^"]*)"', attrs))
    if tag == "circle": d = circle(float(A["cx"]), float(A["cy"]), float(A["r"]))
    elif tag == "ellipse":
        cx, cy, rx, ry = (float(A[k]) for k in ("cx", "cy", "rx", "ry"))
        if "transform" in A:
            d = ellipse_through(cx, cy, rx, ry, [float(v) for v in re.findall(r"-?[\d.]+", A["transform"])])
        else: d = circle(cx, cy, rx, ry)
    else: d = A["d"]
    shapes.append((d, A["fill"], "stroke" in A))
assert len(shapes) == 10, len(shapes)
outer, inner, play, notes = shapes[0], shapes[1], shapes[2], shapes[3:]

def path(d, fill, stroked=False, extra=""):
    s = f'        <path\n            android:fillColor="{fill}"\n'
    if stroked: s += f'            android:strokeColor="{fill}"\n            android:strokeWidth="1"\n'
    return s + extra + f'            android:pathData="{d}" />\n'

def vector(size, box, body, note):
    """A square canvas of `size` dp with the 660 unit logo square scaled to `box` dp in its middle."""
    scale = box / 660.0; shift = (size - box) / 2.0
    centre = shift + 330 * scale
    assert abs(centre - size / 2.0) < 1e-9
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {note}\n     Generated from assets/MellowLogoForeground.svg. The logo\'s centre lands on\n'
            f'     ({fmt(centre)}, {fmt(centre)}) of a {size} x {size} canvas: the exact middle. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="{size}dp"\n    android:height="{size}dp"\n'
            f'    android:viewportWidth="{size}"\n    android:viewportHeight="{size}">\n'
            f'    <group\n        android:scaleX="{scale:.8f}"\n        android:scaleY="{scale:.8f}"\n        android:translateX="{fmt(shift)}"\n        android:translateY="{fmt(shift)}">\n'
            + body + "    </group>\n</vector>\n")

colour = "".join(path(d, fill, stroked) for d, fill, stroked in shapes)
def silhouette(ink):
    # A ring where the pale disc's edge is, the dark disc with the play mark cut out of it, and the notes.
    ring = circle(330, 330, 318) + circle(330, 330, 296)
    body = path(ring, ink, extra='            android:fillType="evenOdd"\n')
    body += path(inner[0] + play[0], ink, extra='            android:fillType="evenOdd"\n')
    return body + "".join(path(d, ink, stroked) for d, _, stroked in notes)

out = {
    "app/src/main/res/drawable/ic_launcher_foreground.xml": vector(108, 57, colour,
        "Launcher icon, front layer: the Mellow disc. A launcher shows the middle 72dp of this\n     108dp canvas, so the 57dp disc sits inside the mask with the background all round it."),
    "app/src/main/res/drawable/ic_launcher_monochrome.xml": vector(108, 57, silhouette("#FFFFFFFF"),
        "Launcher icon for themed home screens, where Android tints one flat shape. The mark\n     as a silhouette: its ring, its inner disc with the play cut out, and its notes."),
    "core/playback/src/main/res/drawable/ic_stat_mellow.xml": vector(24, 22, silhouette("#FFFFFFFF"),
        "Status bar and notification icon. Android draws these in one colour, using only the\n     shape. The Mellow mark as a silhouette, with a 1dp margin."),
    "core/playback/src/main/res/drawable/mellow_mark.xml": vector(660, 660, colour,
        "The Mellow mark in full colour, at its native size. Drawn into the picture shown on\n     the playback notification for music that has no cover of its own."),
}
for rel, text in out.items():
    os.makedirs(os.path.dirname(ROOT + "/" + rel), exist_ok=True)
    open(ROOT + "/" + rel, "w").write(text)
    print("wrote", rel, len(text), "bytes")
