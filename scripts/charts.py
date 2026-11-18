# 블로그용 비교 그래프 생성. results/*.json 의 회차 중앙값으로 SVG 를 만들고 Edge 헤드리스로 PNG 캡처한다.
#   python scripts/charts.py --small 20261114-124551 --big 20261116-221532 --ample 20261114-124859 --slots 20261114-130500
# 결과: results/charts/chart-soldout.png, chart-ample.png, chart-slots.png
# 외부 패키지 없음. Edge(또는 Chrome)가 설치돼 있어야 한다.
import argparse, glob, json, os, statistics, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "results")
OUT = os.path.join(RESULTS, "charts")
LABELS = {
    "naive": "제어 없음", "synchronized": "synchronized", "pessimistic": "비관적 락",
    "optimistic": "낙관적 락", "atomic-update": "조건부 UPDATE", "redisson-lock": "Redisson 분산 락",
    "redis-lua": "Redis Lua", "stock-shard": "재고 분할",
}
SURFACE, TEXT, MUTED, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#e6e5e1"
S1, S2 = "#2a78d6", "#eb6834"
FONT = "'Malgun Gothic','Apple SD Gothic Neo',sans-serif"
BROWSERS = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
]


def median_rps(pattern):
    """strategy -> 회차 중앙값 RPS"""
    by = {}
    for f in glob.glob(os.path.join(RESULTS, pattern)):
        r = json.load(open(f, encoding="utf-8-sig"))
        by.setdefault(r["strategy"], []).append(r["performance"]["rps"])
    return {k: statistics.median(v) for k, v in by.items()}


def fmt(v):
    return f"{round(v):,}"


def hbar(x, y, w, h, color):
    """왼쪽은 직각, 오른쪽(데이터 끝)만 둥근 가로 막대"""
    r = min(4, w)
    return (f'<path d="M{x},{y} h{w - r} a{r},{r} 0 0 1 {r},{r} v{h - 2 * r} a{r},{r} 0 0 1 -{r},{r} h-{w - r} z" '
            f'fill="{color}"/>')


def vbar(x, y, w, h, color):
    r = min(4, h)
    return (f'<path d="M{x},{y + h} v-{h - r} a{r},{r} 0 0 1 {r},-{r} h{w - 2 * r} a{r},{r} 0 0 1 {r},{r} v{h - r} z" '
            f'fill="{color}"/>')


def frame(width, height, title, subtitle, body):
    return f"""<!doctype html><meta charset="utf-8"><body style="margin:0;background:{SURFACE}">
<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" font-family="{FONT}">
<rect width="{width}" height="{height}" fill="{SURFACE}"/>
<text x="24" y="34" font-size="17" font-weight="700" fill="{TEXT}">{title}</text>
<text x="24" y="56" font-size="12.5" fill="{MUTED}">{subtitle}</text>
{body}</svg></body>"""


def chart_hbars(title, subtitle, rows, series, path):
    """rows: [(label, [v1, v2..])], series: [(name, color)] — 가로 막대 (묶음 가능)"""
    n = len(series)
    bar_h, gap, band = (18, 0, 40) if n == 1 else (13, 2, 48)
    left, right, top = 150, 90, 96 if n > 1 else 78
    width = 760
    plot_w = width - left - right
    height = top + band * len(rows) + 28
    vmax = max(v for _, vs in rows for v in vs)
    body = []
    if n > 1:
        lx = left
        for name, color in series:
            body.append(f'<rect x="{lx}" y="68" width="12" height="12" rx="2" fill="{color}"/>'
                        f'<text x="{lx + 18}" y="78.5" font-size="12.5" fill="{MUTED}">{name}</text>')
            lx += 18 + len(name) * 9 + 28
    for i, (label, vs) in enumerate(rows):
        cy = top + band * i + band / 2
        body.append(f'<text x="{left - 12}" y="{cy + 4.5}" font-size="13" text-anchor="end" fill="{TEXT}">{label}</text>')
        block = n * bar_h + (n - 1) * gap
        for j, v in enumerate(vs):
            y = cy - block / 2 + j * (bar_h + gap)
            w = max(2, plot_w * v / vmax)
            body.append(hbar(left, round(y, 1), round(w, 1), bar_h, series[j][1]))
            body.append(f'<text x="{left + w + 8}" y="{y + bar_h / 2 + 4.5}" font-size="12.5" fill="{TEXT}">{fmt(v)}</text>')
    body.append(f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top + band * len(rows)}" stroke="{GRID}" stroke-width="1"/>')
    return write(path, frame(width, height, title, subtitle, "\n".join(body)), width, height)


def chart_columns(title, subtitle, points, xlabel, path):
    """points: [(x label, value)] — 세로 막대"""
    width, height = 760, 380
    left, right, top, bottom = 40, 40, 84, 64
    plot_w, plot_h = width - left - right, height - top - bottom
    vmax = max(v for _, v in points)
    band = plot_w / len(points)
    body = [f'<line x1="{left}" y1="{top + plot_h}" x2="{width - right}" y2="{top + plot_h}" stroke="{GRID}" stroke-width="1"/>']
    for i, (label, v) in enumerate(points):
        h = max(2, plot_h * v / vmax)
        x = left + band * i + band / 2 - 12
        y = top + plot_h - h
        body.append(vbar(round(x, 1), round(y, 1), 24, round(h, 1), S1))
        body.append(f'<text x="{x + 12}" y="{y - 8}" font-size="13" text-anchor="middle" fill="{TEXT}">{fmt(v)}</text>')
        body.append(f'<text x="{x + 12}" y="{top + plot_h + 20}" font-size="13" text-anchor="middle" fill="{TEXT}">{label}</text>')
    body.append(f'<text x="{width / 2}" y="{height - 16}" font-size="12.5" text-anchor="middle" fill="{MUTED}">{xlabel}</text>')
    return write(path, frame(width, height, title, subtitle, "\n".join(body)), width, height)


def write(path, html, width, height):
    os.makedirs(OUT, exist_ok=True)
    html_path = os.path.join(OUT, os.path.basename(path).replace(".png", ".html"))
    open(html_path, "w", encoding="utf-8").write(html)
    browser = next((b for b in BROWSERS if os.path.exists(b)), None)
    if not browser:
        sys.exit("Edge/Chrome 을 찾지 못했다")
    subprocess.run([browser, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=2",
                    f"--window-size={width},{height}", f"--screenshot={path}", "file:///" + html_path.replace("\\", "/")],
                   check=True, capture_output=True, timeout=120)
    print("saved:", path)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--small"); p.add_argument("--big"); p.add_argument("--ample"); p.add_argument("--slots")
    a = p.parse_args()

    if a.small and a.big:
        small, big = median_rps(f"{a.small}-r*_burst_*.json"), median_rps(f"{a.big}-r*_burst_*.json")
        keys = sorted((k for k in big if k in small), key=lambda k: -big[k])
        chart_hbars("매진 상황의 처리량 (RPS)", "쿠폰 100장 · 3회 중앙값 · 높을수록 좋음",
                    [(LABELS[k], [small[k], big[k]]) for k in keys],
                    [("요청 3,000건 / 동시 200명", S1), ("요청 30,000건 / 동시 1,000명", S2)],
                    os.path.join(OUT, "chart-soldout.png"))
    if a.ample:
        ample = median_rps(f"{a.ample}-r*_burst_*.json")
        keys = sorted((k for k in ample if k != "naive"), key=lambda k: -ample[k])
        chart_hbars("재고가 충분할 때의 처리량 (RPS)", "쿠폰 3,000장 / 요청 3,000건 / 동시 200명 · 3회 중앙값 · 높을수록 좋음",
                    [(LABELS[k], [ample[k]]) for k in keys], [("RPS", S1)],
                    os.path.join(OUT, "chart-ample.png"))
    if a.slots:
        pts = []
        for f in glob.glob(os.path.join(RESULTS, f"{a.slots}-s*-write-r1_*.json")):
            n = int(os.path.basename(f).split("-s")[1].split("-")[0])
            pts.append((n, median_rps(f"{a.slots}-s{n}-write-r*_*.json")["stock-shard"]))
        pts.sort()
        chart_columns("재고 분할: 슬롯 수에 따른 처리량 (RPS)", "쿠폰 3,000장 / 요청 3,000건 / 동시 200명 · 3회 중앙값",
                      [(f"{n}개", v) for n, v in pts], "슬롯 수", os.path.join(OUT, "chart-slots.png"))


if __name__ == "__main__":
    main()
