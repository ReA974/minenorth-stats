"""Genere un site d'exemple (donnees fictives, meme format que le mod) dans exemple/site/.

Usage : py -I exemple/generer_exemple.py
"""
import json
import math
import random
import shutil
import datetime as dt
from pathlib import Path

random.seed(2610)
ICI = Path(__file__).resolve().parent
RES = ICI.parent / "src" / "main" / "resources" / "assets" / "minenorthstats" / "site"
OUT = ICI / "site"
OUT.mkdir(parents=True, exist_ok=True)
for f in ("index.html", "app.js", "style.css", "chart.umd.min.js"):
    shutil.copy(RES / f, OUT / f)

TODAY = dt.date(2026, 10, 17)
NJ = 75
PRENOMS = ["Lucas", "Emma", "Hugo", "Lea", "Noah", "Chloe", "Louis", "Manon", "Jules", "Camille", "Adam", "Sarah", "Nathan", "Ines",
           "Tom", "Zoe", "Enzo", "Lola", "Theo", "Eva", "Maxime", "Clara", "Ethan", "Alice"]
NOMS = ["Martin", "Bernard", "Dubois", "Thomas", "Robert", "Richard", "Petit", "Durand", "Leroy", "Moreau", "Simon", "Laurent",
        "Michel", "Garcia", "David", "Bertrand", "Roux", "Vincent", "Fournier", "Morel"]
ITEMS = ["minecraft:oak_log", "minecraft:cobblestone", "minecraft:iron_ingot", "minecraft:bread", "minecraft:diamond", "minecraft:gold_ingot",
         "minecraft:stone", "minecraft:coal", "minecraft:emerald", "minecraft:wheat", "minecraft:carrot", "minecraft:redstone",
         "minecraft:glass", "minecraft:string", "minecraft:arrow", "minecraft:oak_planks", "minecraft:dirt", "minecraft:cooked_beef",
         "minenorth_harvest:gasoline", "minenorth_eurobank:card", "minenorth_police:handcuffs", "minecraft:paper", "minecraft:sand"]
COMPANIES = ["Garage du Nord", "Brasserie Boreale", "Journal Le Cairn", "Transports Polaris", "Cabinet Lacroix", "Ferme des Pins"]
LICENCES = ["permisconduire"]
ZONES = [(0, 0, 25, 1.0), (180, -90, 18, 0.6), (-220, 150, 22, 0.5), (60, 260, 14, 0.4), (-40, -300, 30, 0.3)]


def day(i):
    return (TODAY - dt.timedelta(days=NJ - 1 - i)).isoformat()


def ms(d, hour, minute=0):
    t = dt.datetime.combine(d, dt.time(hour % 24, minute))
    return int(t.timestamp() * 1000)


players = []
hours = {}
heat = {}
eco = {}
series = []
activity_days = {}

# arrivees : plus nombreuses au debut puis irregulieres
for n in range(130):
    first = min(NJ - 1, int(abs(random.gauss(0, 28))))
    first = NJ - 1 - first
    fidelity = random.choice([0.05, 0.1, 0.2, 0.35, 0.5, 0.7, 0.85])
    lifespan = random.choice([1, 2, 3, 7, 14, 30, 60, 90])
    rng = random.Random(n)
    uuid = "%08x-%04x-4%03x-a%03x-%012x" % (rng.getrandbits(32), rng.getrandbits(16), rng.getrandbits(12), rng.getrandbits(12), rng.getrandbits(48))
    label = uuid[:8]
    rp = "%s %s" % (random.choice(PRENOMS), random.choice(NOMS))
    p = dict(id=uuid, label=label, rp=rp, first=0, last=0, online=False, playMin=0, nSessions=0, sessions=[], ad=[], days={}, tot={},
             bal=-1, companies=[], police=False, secours=False, licences=[], items={})
    job = random.random()
    if job < 0.08:
        p["police"] = True
    elif job < 0.14:
        p["secours"] = True
    elif job < 0.55:
        p["companies"] = [random.choice(COMPANIES)]
    if random.random() < 0.7:
        p["licences"] = list(LICENCES)
    money = max(0, int(random.lognormvariate(8.8, 1.5)))
    fav = random.choice(ZONES)
    tot = {}
    for i in range(first, NJ):
        age = i - first
        if age > lifespan and random.random() > 0.04:
            continue
        if age > 0 and random.random() > fidelity + (0.5 if age < 3 else 0):
            continue
        d = TODAY - dt.timedelta(days=NJ - 1 - i)
        nsess = random.choice([1, 1, 1, 2])
        playS = 0
        c = {}
        for _ in range(nsess):
            hr = int(min(23, max(9, random.gauss(20, 3.2))))
            dur = max(8, int(random.lognormvariate(3.9, 0.8)))
            s = ms(d, hr, random.randint(0, 59))
            e = s + dur * 60000
            p["sessions"].append([s, e])
            p["nSessions"] += 1
            playS += dur * 60
            for k in range(dur * 2):
                h = (hr + (k // 120)) % 24
                hours.setdefault(day(i), [0] * 24)[h] += 30
                if random.random() < 0.9:
                    zx, zz, sp, _w = fav
                    cx = int(random.gauss(zx, sp)); cz = int(random.gauss(zz, sp))
                    heat.setdefault(day(i), {})["minecraft:overworld|%d|%d" % (cx, cz)] = heat.setdefault(day(i), {}).get("minecraft:overworld|%d|%d" % (cx, cz), 0) + 1
        mins = playS / 60
        for key, rate in (("casses", 18), ("poses", 12), ("craft", 3), ("kills_mobs", 0.6), ("morts", 0.04), ("trajets_vehicule", 0.12), ("paiements", 0.2)):
            v = int(random.expovariate(1 / max(0.01, rate * mins / 5)))
            if v:
                c[key] = v
        if p["police"] and random.random() < 0.6:
            c["service_police_s"] = int(playS * random.uniform(0.3, 0.9))
        if p["secours"] and random.random() < 0.6:
            c["service_secours_s"] = int(playS * random.uniform(0.3, 0.9))
        if c.get("trajets_vehicule"):
            c["vehicule_s"] = c["trajets_vehicule"] * random.randint(120, 600)
        rev = int(random.expovariate(1 / 4000)) if random.random() < 0.6 else 0
        dep = int(random.expovariate(1 / 3000)) if random.random() < 0.5 else 0
        if rev: c["revenus_c"] = rev
        if dep: c["depenses_c"] = dep
        if p["companies"] and random.random() < 0.3:
            c["salaires_c"] = 12000; c["revenus_c"] = c.get("revenus_c", 0) + 12000
        money = max(0, money + rev - dep + random.randint(-300, 800))
        for k, v in c.items():
            tot[k] = tot.get(k, 0) + v
        p["days"][day(i)] = {"p": playS, "b": money, "c": c}
        p["ad"].append(day(i))
    if not p["ad"]:
        continue
    p["tot"] = tot
    p["sessions"] = p["sessions"][-30:]
    p["first"] = p["sessions"][0][0] if p["sessions"] else ms(TODAY, 12)
    p["last"] = p["sessions"][-1][1]
    p["playMin"] = int(sum(v["p"] for v in p["days"].values()) / 60)
    p["bal"] = money
    for it in random.sample(ITEMS, random.randint(4, 12)):
        p["items"][it] = int(random.expovariate(1 / 60)) + 1
    p["online"] = p["ad"][-1] == day(NJ - 1) and random.random() < 0.15
    players.append(p)

# jours : on ne garde que 60 jours dans "days" comme le mod
cut = day(NJ - 60)
for p in players:
    p["days"] = {k: v for k, v in p["days"].items() if k >= cut}
    p["label"] = p["id"][:8]
players.sort(key=lambda p: -p["last"])

# economie par jour
for i in range(NJ):
    d = day(i)
    bal = []
    for p in players:
        last = None
        for k in sorted(p["days"]):
            if k <= d and p["days"][k].get("b") is not None:
                last = p["days"][k]["b"]
        if last is None and i < NJ - 60:
            last = p["bal"] * random.uniform(0.5, 1.0) if p["ad"] and p["ad"][0] <= d else None
        if last is not None:
            bal.append(last)
    if bal:
        bal.sort()
        t = sum(bal)
        top = sum(bal[-max(1, len(bal) // 10):])
        eco[d] = dict(total=t, accounts=len(bal), median=bal[len(bal) // 2], top10=int(1000 * top / t) if t else 0)

# serie par echantillon (une par 30 min sur 3 jours pour la demo)
now = ms(TODAY, 18)
for k in range(144):
    t = now - (143 - k) * 1800000
    hr = (dt.datetime.fromtimestamp(t / 1000).hour)
    series.append([t, int(max(0, 6 + 8 * math.sin((hr - 14) / 24 * 2 * math.pi) + random.randint(-2, 2))), eco[day(NJ - 1)]["total"], eco[day(NJ - 1)]["accounts"]])

# objets / entreprises
itot = {}
for p in players:
    for k, v in p["items"].items():
        a = itot.setdefault(k, [0, 0]); a[0] += v; a[1] += 1
items = sorted(([k, v[0], v[1]] for k, v in itot.items()), key=lambda x: -x[1])
comp = {}
for p in players:
    for c in p["companies"]:
        a = comp.setdefault(c, [0, 0]); a[0] += 1; a[1] += max(0, p["bal"])
companies = [dict(name=k, members=v[0], balance=v[1]) for k, v in sorted(comp.items())]


def cells(prefix_days):
    out = {}
    for d, m in heat.items():
        if d not in prefix_days:
            continue
        for key, n in m.items():
            dim, cx, cz = key.split("|")
            out.setdefault(dim, {})
            out[dim][(int(cx), int(cz))] = out[dim].get((int(cx), int(cz)), 0) + n
    return {dim: [[x, z, n] for (x, z), n in m.items()] for dim, m in out.items()}


alldays = set(heat)
last7 = {day(i) for i in range(NJ - 7, NJ)}
data = dict(generated=int(dt.datetime(2026, 10, 17, 18, 5).timestamp() * 1000), today=TODAY.isoformat(), retentionDays=180, mode="uuid",
            sampleMinutes=5, players=players, hours=hours, heat=dict(all=cells(alldays), d7=cells(last7)), series=series, eco=eco,
            items=items[:150], companies=companies)
js = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
(OUT / "data.json").write_text(js, encoding="utf-8")
(OUT / "data.js").write_text("window.MN_DATA = " + js + ";\n", encoding="utf-8")
print("joueurs:", len(players), "taille data.js: %d Ko" % (len(js) // 1024))
