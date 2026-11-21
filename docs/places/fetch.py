import json, re, sys, urllib.request, urllib.parse, time
D = sys.argv[1]
API = "https://losyash-library.fandom.com/ru/api.php"
def get(params):
    q = urllib.parse.urlencode({**params, "format": "json", "formatversion": "2"})
    req = urllib.request.Request(API + "?" + q, headers={"User-Agent": "Mozilla/5.0 wiki-parser"})
    return json.load(urllib.request.urlopen(req, timeout=60))
t = json.load(open(D + "/series.json"))["parse"]["wikitext"]
s = t[t.find("== Список серий"):t.find("== Интересные факты")]
eps = []
for row in s.split("\n|-")[1:]:
    m = re.match(r"\s*\|(\d+)\|\|.*?thumb\|center\|\[\[([^\]|]+)(?:\|([^\]]+))?\]\]", row, re.S)
    if m: eps.append({"n": int(m.group(1)), "title": m.group(2).strip(), "name": (m.group(3) or m.group(2)).strip()})
print(len(eps), "episodes", file=sys.stderr)
titles = list(dict.fromkeys(e["title"] for e in eps))
pages, redir = {}, {}
for i in range(0, len(titles), 50):
    r = get({"action": "query", "prop": "revisions", "rvprop": "content", "rvslots": "main", "redirects": 1, "titles": "|".join(titles[i:i+50])})
    for x in r["query"].get("normalized", []) + r["query"].get("redirects", []): redir[x["from"]] = x["to"]
    for p in r["query"]["pages"]:
        if "revisions" in p: pages[p["title"]] = p["revisions"][0]["slots"]["main"]["content"]
        else: print("missing", p["title"], file=sys.stderr)
    time.sleep(1)
for e in eps:
    k = e["title"]
    while k in redir: k = redir[k]
    e["page"] = k
json.dump({"eps": eps, "pages": pages}, open(D + "/raw.json", "w"), ensure_ascii=False)
