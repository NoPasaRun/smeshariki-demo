import json, re, sys, urllib.parse
D = sys.argv[1]
raw = json.load(open(D + "/raw.json"))
BASE = "https://losyash-library.fandom.com/ru/wiki/"
def url(t): return BASE + urllib.parse.quote(t.strip().replace(" ", "_"), safe="()_,'!-.:")
def split_top(s):
    out, cur, d = [], "", 0
    i = 0
    while i < len(s):
        two = s[i:i+2]
        if two in ("[[", "{{"): d += 1; cur += two; i += 2; continue
        if two in ("]]", "}}"): d -= 1; cur += two; i += 2; continue
        if s[i] == "|" and d == 0: out.append(cur); cur = ""; i += 1; continue
        cur += s[i]; i += 1
    out.append(cur); return out
def template_body(t, start):
    d, i = 0, start
    while i < len(t):
        if t.startswith("{{", i): d += 1; i += 2; continue
        if t.startswith("}}", i):
            d -= 1; i += 2
            if d == 0: return t[start+2:i-2]
            continue
        i += 1
    return t[start+2:]
LINK = re.compile(r"\[\[([^\]|#]+)(?:#[^\]|]*)?(?:\|([^\]]*))?\]\]")
def plain(s):
    s = LINK.sub(lambda m: m.group(2) or m.group(1), s)
    s = re.sub(r"'{2,}", "", s); s = re.sub(r"<[^>]+>", "", s)
    return re.sub(r"\s+", " ", s).strip()
res, nosec = [], []
for e in raw["eps"]:
    t = raw["pages"].get(e["page"], "")
    places = []
    m = re.search(r"==\s*(?:Места|Локации)\s*==", t)
    if not m: nosec.append(e["name"])
    else:
        sec = t[m.end():]
        nx = re.search(r"\n==[^=]", sec); sec = sec[:nx.start()] if nx else sec
        for tm in re.finditer(r"\{\{\s*Список мест", sec):
            parts = [p.strip() for p in split_top(template_body(sec, tm.start()))[1:]]
            parts = [p for p in parts if not re.match(r"^\w+\s*=", p) or "[[" in p]
            for j in range(0, len(parts) - 2, 3):
                img, name, desc = parts[j:j+3]
                links = [l.group(1).strip() for l in LINK.finditer(name) if not l.group(1).startswith(("Файл:", "File:"))]
                places.append({"name": plain(name), "links": [{"title": l, "url": url(l)} for l in links],
                               "image": img, "description": plain(desc)})
        if not places and "{|" in sec:  # вики-таблица: картинка | место | комментарий (rowspan)
            span_desc, span_left = "", 0
            for row in sec[sec.find("{|"):].split("\n|-")[1:]:
                cells = [c.strip() for c in re.split(r"\n\||\|\|", "\n" + row.split("\n|}")[0]) if c.strip() and not c.strip().startswith("!")]
                if len(cells) < 2: continue
                img, name = cells[0], cells[1]
                if len(cells) >= 3:
                    d = cells[2]; rs = re.match(r'\s*rowspan="?(\d+)"?\s*\|(.*)', d, re.S)
                    if rs: span_desc, span_left, d = rs.group(2), int(rs.group(1)) - 1, rs.group(2)
                elif span_left > 0: d = span_desc; span_left -= 1
                else: d = ""
                links = [l.group(1).strip() for l in LINK.finditer(name) if not l.group(1).startswith(("Файл:", "File:"))]
                places.append({"name": plain(name), "links": [{"title": l, "url": url(l)} for l in links], "image": img, "description": plain(d)})
        if not places:  # другой формат: список с [[ссылками]]
            for line in sec.splitlines():
                if line.startswith("*"):
                    links = [l.group(1).strip() for l in LINK.finditer(line) if not l.group(1).startswith(("Файл:", "File:"))]
                    places.append({"name": plain(line.lstrip("* ")), "links": [{"title": l, "url": url(l)} for l in links], "image": "", "description": ""})
    res.append({"n": e["n"], "episode": e["name"], "url": url(e["page"]), "places": places})
json.dump(res, open(D + "/places_by_episode.json", "w"), ensure_ascii=False, indent=1)
idx = {}
for r in res:
    for p in r["places"]:
        for l in (p["links"] or [{"title": p["name"], "url": None}]):
            x = idx.setdefault(l["title"], {"place": l["title"], "url": l["url"], "episodes": []})
            if r["episode"] not in x["episodes"]: x["episodes"].append(r["episode"])
import os
ck = json.load(open(D + "/check.json")) if os.path.exists(D + "/check.json") else {"missing": [], "redirects": {}}
for x in idx.values():
    x["redirect_to"] = ck["redirects"].get(x["place"]); x["page_missing"] = x["place"] in ck["missing"]
    if x["redirect_to"]: x["redirect_url"] = url(x["redirect_to"])
ix = sorted(idx.values(), key=lambda x: (-len(x["episodes"]), x["place"]))
json.dump(ix, open(D + "/places_index.json", "w"), ensure_ascii=False, indent=1)
with open(D + "/places.md", "w") as f:
    f.write("# Места в сериях «Смешариков»\n\nИсточник: https://losyash-library.fandom.com/ru/wiki/Смешарики_(мультсериал), раздел «Места» каждой серии.\n\n")
    f.write(f"## Все места ({len(ix)}), по числу серий\n\n| Место | Серий | Серии |\n|---|---|---|\n")
    for x in ix:
        nm = f"[{x['place']}]({x['url']})" if x["url"] else x["place"] + " _(без ссылки)_"
        if x.get("redirect_to"): nm += f" → [{x['redirect_to']}]({x['redirect_url']})"
        if x.get("page_missing"): nm += " _(страницы нет)_"
        f.write(f"| {nm} | {len(x['episodes'])} | {', '.join(x['episodes'])} |\n")
    grp = {}
    for x in ix:
        if x["url"] and not x["page_missing"]:
            k = x.get("redirect_to") or x["place"]; g = grp.setdefault(k, {"eps": [], "aliases": []})
            if k != x["place"]: g["aliases"].append(x["place"])
            g["eps"] += [e for e in x["episodes"] if e not in g["eps"]]
    f.write(f"\n## Страницы мест на вики ({len(grp)}), с учётом перенаправлений\n\n| Страница | Другие названия в сериях | Серий |\n|---|---|---|\n")
    for k, g in sorted(grp.items(), key=lambda kv: (-len(kv[1]["eps"]), kv[0])):
        f.write(f"| [{k}]({url(k)}) | {', '.join(g['aliases'])} | {len(g['eps'])} |\n")
    json.dump([{"page": k, "url": url(k), "aliases": g["aliases"], "episodes": g["eps"]} for k, g in grp.items()], open(D + "/places_pages.json", "w"), ensure_ascii=False, indent=1)
    f.write("\n## По сериям\n")
    for r in res:
        f.write(f"\n### {r['n']}. [{r['episode']}]({r['url']})\n\n")
        if not r["places"]: f.write("_раздела «Места» нет_\n")
        for p in r["places"]:
            ls = ", ".join(f"[{l['title']}]({l['url']})" for l in p["links"]) or p["name"]
            f.write(f"- {ls}" + (f" — {p['description']}" if p["description"] else "") + "\n")
print("эпизодов:", len(res), "с местами:", sum(1 for r in res if r["places"]), "мест-записей:", sum(len(r["places"]) for r in res), "уникальных:", len(ix))
print("без раздела:", nosec)
print("раздел есть, но пусто:", [r["episode"] for r in res if not r["places"] and r["episode"] not in nosec])
print("записи без ссылок:", [(r["episode"], p["name"]) for r in res for p in r["places"] if not p["links"]][:30])
