#!/usr/bin/env python3
"""Builds the data bundled in the Wanki app from open sources.

    python3 -I tools/decks/build.py [--cache DIR] [--only kanjivg|decks]

Downloads pinned releases into the cache (tools/decks/.cache by default, not in git) and writes
    app/src/main/assets/kanjivg/<codepoint>.svg   stroke references (KanjiVG, minimized)
    app/src/main/assets/decks/catalog.json        the ready-made deck catalog
    app/src/main/assets/decks/<id>.tsv            cards: key, front, back, tags
Standard library only. See tools/decks/README.md for sources and licenses.
"""
import argparse
import gzip
import hashlib
import json
import re
import shutil
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets"

JMS = "https://github.com/scriptin/jmdict-simplified/releases/download/3.6.2%2B20261012204142"
KRDICT = "https://raw.githubusercontent.com/spellcheck-ko/korean-dict-nikl-krdict/master"
KRDICT_PARTS = ["5000", "10000", "15000", "20000", "25000", "30000", "35000", "40000", "45000", "50000", "51947"]
SOURCES = {
    "kanjivg/kanjivg-main.zip": "https://github.com/KanjiVG/kanjivg/releases/download/r20260714/kanjivg-20260714-main.zip",
    "kanjidic/kanjidic2-en.json.zip": JMS + "/kanjidic2-en-3.6.2%2B20261012204142.json.zip",
    "jmdict-full/JMdict.gz": "http://ftp.edrdg.org/pub/Nihongo/JMdict.gz",
    "hsk/complete.min.json": "https://raw.githubusercontent.com/drkameleon/complete-hsk-vocabulary/v1.4/complete.min.json",
    **{f"krdict/{p}.xml": f"{KRDICT}/{p}.xml" for p in KRDICT_PARTS},
}

LICENSES = {
    "kanjivg": "KanjiVG © Ulrich Apel, CC BY-SA 3.0",
    "edrdg": "KANJIDIC2 и JMdict © EDRDG, CC BY-SA 4.0",
    "hsk": "Complete HSK Vocabulary (MIT), значения CC-CEDICT (CC BY-SA 4.0)",
    "krdict": "한국어기초사전 © 국립국어원, CC BY-SA 2.0 KR",
    "wanki": "Wanki",
}

XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"


def fetch(cache: Path) -> None:
    for rel, url in SOURCES.items():
        target = cache / rel
        if target.exists() and target.stat().st_size > 0:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        part = target.with_suffix(target.suffix + ".part")
        for attempt in range(5):
            try:
                print(f"downloading {url}")
                with urllib.request.urlopen(url, timeout=120) as r, open(part, "wb") as out:
                    shutil.copyfileobj(r, out)
                part.rename(target)
                break
            except OSError as e:
                print(f"  failed ({e}), retrying")
                time.sleep(5 * (attempt + 1))
        else:
            sys.exit(f"could not download {url}")


# --- KanjiVG -----------------------------------------------------------------------------------

TAG = re.compile(r"<(/?)(g|path)\b([^>]*?)(/?)>")
ATTR = re.compile(r'([\w:.-]+)\s*=\s*"([^"]*)"')


def minimize_svg(svg: str) -> str:
    """Keeps exactly what KanjiVg.parse reads: the StrokePaths group, nested groups with
    kvg:element and paths with d / kvg:type. Drops the license comment, DTD, stroke numbers, styles."""
    start = svg.index('<g id="kvg:StrokePaths_')
    end = svg.find('<g id="kvg:StrokeNumbers_')
    out = []
    for m in TAG.finditer(svg[start:end if end > 0 else len(svg)]):
        closing, name, attrs, self_closing = m.groups()
        if closing:
            out.append(f"</{name}>")
            continue
        a = dict(ATTR.findall(attrs))
        keep = ("id", "kvg:element") if name == "g" else ("d", "kvg:type")
        kept = [(k, a[k]) for k in keep if k in a and not (k == "id" and not a[k].startswith("kvg:StrokePaths_"))]
        out.append(f"<{name}" + "".join(f' {k}="{v}"' for k, v in kept) + ("/" if self_closing else "") + ">")
    return ('<svg xmlns="http://www.w3.org/2000/svg" xmlns:kvg="http://kanjivg.tagaini.net" viewBox="0 0 109 109">'
            + "".join(out) + "</svg>\n")


def structure(svg: str):
    """Groups (element) and paths (d, type) in document order, for checking the minimized copy."""
    region = svg[svg.index('<g id="kvg:StrokePaths_'):]
    end = region.find('<g id="kvg:StrokeNumbers_')
    region = region[:end] if end > 0 else region
    items = []
    for m in TAG.finditer(region):
        closing, name, attrs, _ = m.groups()
        a = dict(ATTR.findall(attrs))
        items.append((closing, name, a.get("kvg:element"), a.get("d"), a.get("kvg:type")))
    return items


def build_kanjivg(cache: Path) -> None:
    out = ASSETS / "kanjivg"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    total = 0
    with zipfile.ZipFile(cache / "kanjivg/kanjivg-main.zip") as z:
        names = [n for n in z.namelist() if n.endswith(".svg")]
        for n in names:
            svg = z.read(n).decode("utf-8")
            small = minimize_svg(svg)
            if structure(small) != structure(svg):
                sys.exit(f"minimized {n} differs from the original")
            data = small.encode("utf-8")
            (out / Path(n).name).write_bytes(data)
            total += len(data)
    (out / "README.txt").write_text(
        "Stroke data from KanjiVG (https://kanjivg.tagaini.net), copyright (C) 2009-2026 Ulrich Apel.\n"
        "Distributed under the Creative Commons Attribution-Share Alike 3.0 licence\n"
        "(http://creativecommons.org/licenses/by-sa/3.0/). Release r20260714, reduced by\n"
        "tools/decks/build.py to the stroke paths and component groups; the same licence applies.\n",
        encoding="utf-8",
    )
    print(f"kanjivg: {len(names)} characters, {total / 1e6:.1f} MB")


# --- helpers -----------------------------------------------------------------------------------

def tsv_field(s: str) -> str:
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


class Catalog:
    def __init__(self):
        self.decks = []
        out = ASSETS / "decks"
        if out.exists():
            shutil.rmtree(out)
        out.mkdir(parents=True)

    def add(self, deck_id, path, language, rows, description, source, license_key, mode="HANDWRITING"):
        """rows: (key, front, back, tags); keys must be unique within the deck."""
        keys = [r[0] for r in rows]
        if len(set(keys)) != len(keys):
            sys.exit(f"{deck_id}: duplicate keys")
        body = "".join("\t".join(tsv_field(x) for x in r) + "\n" for r in rows)
        file = f"{deck_id}.tsv"
        (ASSETS / "decks" / file).write_text(body, encoding="utf-8")
        self.decks.append({
            "id": deck_id, "path": path, "language": language, "mode": mode, "count": len(rows), "file": file,
            "description": description, "source": source, "license": LICENSES[license_key],
            "version": hashlib.sha1(body.encode("utf-8")).hexdigest()[:10],
        })
        print(f"{deck_id}: {len(rows)} cards")

    def write(self):
        (ASSETS / "decks" / "catalog.json").write_text(
            json.dumps({"format": 1, "decks": self.decks}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def shorten(parts, limit, sep="; "):
    """Joins distinct meanings with [sep] while the result stays within [limit] characters; a single
    overlong first meaning is cut at a word boundary."""
    out = []
    for p in parts:
        if not p or p in out:
            continue
        if out and len(sep.join(out + [p])) > limit:
            break
        out.append(p)
    text = sep.join(out)
    if len(text) > limit:
        cut = text[:limit].rsplit(" ", 1)[0].rstrip(",;:( ")
        text = cut + "…"
    return text


# --- Japanese ----------------------------------------------------------------------------------

HIRAGANA = [
    ("a", "а", "あ"), ("i", "и", "い"), ("u", "у", "う"), ("e", "э", "え"), ("o", "о", "お"),
    ("ka", "ка", "か"), ("ki", "ки", "き"), ("ku", "ку", "く"), ("ke", "кэ", "け"), ("ko", "ко", "こ"),
    ("sa", "са", "さ"), ("shi", "си", "し"), ("su", "су", "す"), ("se", "сэ", "せ"), ("so", "со", "そ"),
    ("ta", "та", "た"), ("chi", "ти", "ち"), ("tsu", "цу", "つ"), ("te", "тэ", "て"), ("to", "то", "と"),
    ("na", "на", "な"), ("ni", "ни", "に"), ("nu", "ну", "ぬ"), ("ne", "нэ", "ね"), ("no", "но", "の"),
    ("ha", "ха", "は"), ("hi", "хи", "ひ"), ("fu", "фу", "ふ"), ("he", "хэ", "へ"), ("ho", "хо", "ほ"),
    ("ma", "ма", "ま"), ("mi", "ми", "み"), ("mu", "му", "む"), ("me", "мэ", "め"), ("mo", "мо", "も"),
    ("ya", "я", "や"), ("yu", "ю", "ゆ"), ("yo", "ё", "よ"),
    ("ra", "ра", "ら"), ("ri", "ри", "り"), ("ru", "ру", "る"), ("re", "рэ", "れ"), ("ro", "ро", "ろ"),
    ("wa", "ва", "わ"), ("wo", "о (во)", "を"), ("n", "н", "ん"),
    ("ga", "га", "が"), ("gi", "ги", "ぎ"), ("gu", "гу", "ぐ"), ("ge", "гэ", "げ"), ("go", "го", "ご"),
    ("za", "дза", "ざ"), ("ji", "дзи", "じ"), ("zu", "дзу", "ず"), ("ze", "дзэ", "ぜ"), ("zo", "дзо", "ぞ"),
    ("da", "да", "だ"), ("ji (ряд та)", "дзи", "ぢ"), ("zu (ряд та)", "дзу", "づ"), ("de", "дэ", "で"), ("do", "до", "ど"),
    ("ba", "ба", "ば"), ("bi", "би", "び"), ("bu", "бу", "ぶ"), ("be", "бэ", "べ"), ("bo", "бо", "ぼ"),
    ("pa", "па", "ぱ"), ("pi", "пи", "ぴ"), ("pu", "пу", "ぷ"), ("pe", "пэ", "ぺ"), ("po", "по", "ぽ"),
]


def kana_decks(catalog: Catalog) -> None:
    for deck_id, name, shift in (("ja-kana-hiragana", "Хирагана", 0), ("ja-kana-katakana", "Катакана", 0x60)):
        rows = []
        for romaji, ru, kana in HIRAGANA:
            ch = chr(ord(kana) + shift)
            rows.append((ch, f"{romaji}\n{ru}", ch, f"kana {name.lower()}"))
        catalog.add(deck_id, ["Японский", "Кана", name], "ja", rows,
                    f"{name}: 46 основных знаков и 25 со звонкими значками. Вопрос — чтение ромадзи и по Поливанову.",
                    "Wanki", "wanki")


KANA_ONLY = "word usually written using kana alone"
EXCLUDED_KANJI_FORMS = ("rarely used kanji form", "search-only kanji form", "out-dated kanji", "irregular kanji usage")
COMMON = ("news1", "ichi1", "spec1", "spec2", "gai1")


JAPANESE = re.compile(r"[\u3040-\u30ff\u3400-\u9fff々〆～…]")


LABELS = re.compile(r"\((?:[а-яё]+\.\s*(?:и\s*)?)+\)\s*")


def clean_ru(g: str) -> str:
    g = re.sub(r"\{[^}]*\}", "", g)
    g = LABELS.sub("", g)  # usage labels: (связ.), (прям. и перен.), (уст.)
    rest = re.sub(r"^\([^)]*\)\s*", "", g.strip())  # a leading note: (гл. обр. на ценных бумагах) три
    g = rest if rest else g
    g = g.replace("[", "").replace("]", "")
    g = g.strip().lstrip(":").strip()
    return re.sub(r"\s+", " ", g)


def ru_senses(glosses):
    """JMdict's Russian glosses carry their own numbering ("1) …; 2): …", "а) …"), cross-references
    "(ср.) …" and Japanese examples. Returns clean senses, each a ', '-joined list of synonyms."""
    text = "; ".join(glosses)
    senses = []
    for part in re.split(r"\s*\b\d+\)\s*:?\s*", text):
        items = []
        for item in re.split(r"\s*;\s*", part):
            item = re.sub(r"^[а-я]\)\s*", "", item.strip(" ,;:"))
            if not item or item.startswith("(ср.") or JAPANESE.search(item):
                continue
            for word in re.split(r",\s*(?![^()]*\))", clean_ru(item)):
                word = word.strip(" ,")
                if word and word not in items:
                    items.append(word)
        if items:
            senses.append(", ".join(items))
    return senses


def read_jmdict(cache: Path):
    """Entries with their best frequency rank (nf01..nf48 → 1..48, other common → 100)."""
    entries = []
    with gzip.open(cache / "jmdict-full/JMdict.gz") as f:
        for _, el in ET.iterparse(f, events=("end",)):
            if el.tag != "entry":
                continue
            kebs = []
            for k in el.findall("k_ele"):
                infs = [i.text or "" for i in k.findall("ke_inf")]
                kebs.append((k.findtext("keb"), [p.text for p in k.findall("ke_pri")], infs))
            rebs = []
            for r in el.findall("r_ele"):
                rebs.append((r.findtext("reb"), [p.text for p in r.findall("re_pri")],
                             [x.text for x in r.findall("re_restr")], r.find("re_nokanji") is not None))
            eng, rus, misc0 = [], [], None
            for s in el.findall("sense"):
                gl = s.findall("gloss")
                langs = {g.get(XML_LANG, "eng") for g in gl}
                if "eng" in langs:
                    if misc0 is None:
                        misc0 = [m.text or "" for m in s.findall("misc")]
                    eng.append([g.text for g in gl if g.get(XML_LANG, "eng") == "eng" and g.text])
                elif "rus" in langs:
                    rus.append([clean_ru(g.text) for g in gl if g.text and clean_ru(g.text)])
            entries.append({
                "seq": int(el.findtext("ent_seq")), "kebs": kebs, "rebs": rebs,
                "eng": [e for e in eng if e], "rus": [r for r in rus if r], "misc": misc0 or [],
            })
            el.clear()
    return entries


def pri_rank(pris):
    nf = [int(p[2:]) for p in pris if p.startswith("nf")]
    if nf:
        return min(nf)
    return 100 if any(p in COMMON for p in pris) else None


def entry_rank(e):
    ranks = [r for r in (pri_rank(p) for _, p, _ in e["kebs"]) if r is not None]
    ranks += [r for r in (pri_rank(p) for _, p, _, _ in e["rebs"]) if r is not None]
    return min(ranks) if ranks else None


def ru_meaning(e, senses=2, limit=50):
    """From the first Russian sense block (later blocks mostly repeat it)."""
    parts = ru_senses(e["rus"][0])[:senses] if e["rus"] else []
    return shorten(parts, limit) if parts else None


def en_meaning(e, senses=2, glosses=3, limit=50):
    parts = [", ".join(s[:glosses]) for s in e["eng"][:senses]]
    return shorten(parts, limit) if parts else None


def word_decks(catalog: Catalog, entries) -> None:
    ranked = []
    for e in entries:
        r = entry_rank(e)
        if r is None or r > 48 or not e["eng"]:
            continue
        pris = [p for _, ps, _ in e["kebs"] for p in ps] + [p for _, ps, _, _ in e["rebs"] for p in ps]
        ranked.append(((r, "ichi1" not in pris, "news1" not in pris, e["seq"]), e))
    ranked.sort(key=lambda x: x[0])
    rows, seen = [], set()
    for _, e in ranked:
        kebs = [k for k in e["kebs"] if not any(x in EXCLUDED_KANJI_FORMS for x in k[2])]
        best_keb = min(kebs, key=lambda k: pri_rank(k[1]) or 1000)[0] if kebs else None
        kana_only = best_keb is None or any(KANA_ONLY in m for m in e["misc"])
        reading = next((r[0] for r in e["rebs"] if not r[3] and (not r[2] or best_keb in r[2])), e["rebs"][0][0])
        answer = reading if kana_only else best_keb
        if answer in seen:
            continue
        seen.add(answer)
        meaning = ru_meaning(e) or en_meaning(e)
        front = meaning if kana_only else f"{meaning}\n{reading}"
        rows.append((answer, front, answer, "jmdict"))
        if len(rows) == 6000:
            break
    for i in range(6):
        chunk = rows[i * 1000:(i + 1) * 1000]
        name = f"{i * 1000 + 1:04d}–{(i + 1) * 1000:04d}"
        catalog.add(f"ja-words-{i + 1:02d}", ["Японский", "Слова", name], "ja",
                    [(k, f, b, t + f" freq{i + 1}") for k, f, b, t in chunk],
                    f"Самые частые слова японского, {name} по частоте в газетах (JMdict). "
                    "Вопрос — значение и чтение, ответ — запись словом.",
                    "JMdict (EDRDG)", "edrdg")


def kanji_decks(catalog: Catalog, cache: Path, entries) -> None:
    with zipfile.ZipFile(cache / "kanjidic/kanjidic2-en.json.zip") as z:
        data = json.loads(z.read(z.namelist()[0]))
    # Russian meaning of a kanji: the most frequent JMdict word written with that kanji alone
    # Only from words spelled with this kanji alone and nothing else (an entry with several spellings
    # mixes their Russian senses: 歳;才 → «талант»), and only when the word's English senses share a
    # word with KANJIDIC's meanings; otherwise the English meanings are shown.
    en_by_kanji = {c["literal"]: [m["value"] for g in ((c.get("readingMeaning") or {}).get("groups") or [])
                                  for m in g["meanings"] if m["lang"] == "en"] for c in data["characters"]}
    ru_by_kanji = {}
    for e in sorted(entries, key=lambda e: entry_rank(e) or 1000):
        kebs = [k for k in e["kebs"] if not any(x in EXCLUDED_KANJI_FORMS for x in k[2])]
        if len(kebs) != 1 or len(kebs[0][0]) != 1 or kebs[0][0] in ru_by_kanji or not e["rus"]:
            continue
        lit = kebs[0][0]
        if not overlap([g for s in e["eng"] for g in s], en_by_kanji.get(lit, [])):
            continue
        m = ru_meaning(e, senses=2, limit=40)
        if m:
            ru_by_kanji[lit] = m
    grades = [(1, "1 класс"), (2, "2 класс"), (3, "3 класс"), (4, "4 класс"), (5, "5 класс"), (6, "6 класс"),
              (8, "Средняя школа")]
    for grade, name in grades:
        chars = [c for c in data["characters"] if c["misc"].get("grade") == grade]
        chars.sort(key=lambda c: (c["misc"].get("frequency") or 10_000, (c["misc"].get("strokeCounts") or [99])[0]))
        rows = []
        for c in chars:
            lit = c["literal"]
            groups = (c.get("readingMeaning") or {}).get("groups") or []
            readings = [r for g in groups for r in g["readings"]]
            on = [r["value"] for r in readings if r["type"] == "ja_on"][:3]
            kun = [kun_reading(r["value"]) for r in readings if r["type"] == "ja_kun"][:3]
            en = [m["value"] for g in groups for m in g["meanings"] if m["lang"] == "en"][:3]
            meaning = ru_by_kanji.get(lit) or ", ".join(en)
            line = " · ".join(x for x in (("он: " + "、".join(on)) if on else "", ("кун: " + "、".join(kun)) if kun else "") if x)
            rows.append((lit, f"{meaning}\n{line}" if line else meaning, lit, f"kanji grade{grade}"))
        deck_id = f"ja-kanji-{'secondary' if grade == 8 else grade}"
        what = "кандзи средней школы (остальные дзёё)" if grade == 8 else f"кандзи {grade} класса японской школы"
        catalog.add(deck_id, ["Японский", "Кандзи", name], "ja", rows,
                    f"{len(rows)} {what}. Вопрос — значение и чтения, ответ — знак. Порядок — по частоте.",
                    "KANJIDIC2, JMdict (EDRDG)", "edrdg")


def words_of(texts):
    return {w[:4] for t in texts for w in re.findall(r"[a-z]{3,}", t.lower()) if w not in ("the", "and", "for", "one")}


def overlap(a, b) -> bool:
    return bool(words_of(a) & words_of(b))


def kun_reading(v: str) -> str:
    """つ.ぐ → つ(ぐ): the part after the dot is okurigana."""
    if "." in v:
        stem, oku = v.split(".", 1)
        return f"{stem}({oku})"
    return v


# --- Chinese -----------------------------------------------------------------------------------

JUNK_MEANING = re.compile(r"^(\(old\) |old |unofficial |archaic |erhua |Japanese |)variant of|^surname |^abbr\. for|^see |^used in |^\(Tw\)")


def hsk_meaning(w):
    """CC-CEDICT lists every reading of a character; variants, surnames and proper nouns are
    dropped. One reading: its first meanings; several (得 dé/de/děi): the first meaning of each."""
    forms = []
    for f in w["f"]:
        pinyin = f["i"]["y"]
        meanings = [m for m in f["m"] if not JUNK_MEANING.search(m)]
        if not meanings or (pinyin[:1].isupper() and len(w["f"]) > 1):
            continue
        if forms and any("abbr." in m or "(dialect)" in m or "(Tw)" in m for m in meanings):
            continue
        if any(p.lower() == pinyin.lower() for p, _ in forms):
            continue
        forms.append((pinyin, meanings))
    if not forms:
        f = w["f"][0]
        forms = [(f["i"]["y"], f["m"])]
    if len(forms) == 1:
        pinyin, meanings = forms[0]
        return shorten(meanings, 50), pinyin
    forms = forms[:3]
    return shorten([m[0] for _, m in forms], 60, " / "), " · ".join(p for p, _ in forms)


def hsk_decks(catalog: Catalog, cache: Path) -> None:
    words = json.loads((cache / "hsk/complete.min.json").read_text(encoding="utf-8"))
    by_level = {}
    for w in words:
        levels = [int(l[1:]) for l in w["l"] if l.startswith("n")]
        if levels:
            by_level.setdefault(min(levels), []).append(w)
    for level in sorted(by_level):
        ws = sorted(by_level[level], key=lambda w: (w.get("q") or 10 ** 9, w["s"]))
        rows, seen = [], {}
        for w in ws:
            meaning, pinyin = hsk_meaning(w)
            key = w["s"]
            seen[key] = seen.get(key, 0) + 1
            if seen[key] > 1:
                key = f"{key}#{seen[key]}"
            rows.append((key, f"{meaning}\n{pinyin}", w["s"], f"hsk3 hsk{level}"))
        name = "Уровни 7–9" if level == 7 else f"Уровень {level}"
        catalog.add(f"zh-hsk3-{level}", ["Китайский", "HSK 3.0", name], "zh", rows,
                    f"{len(rows)} слов HSK 3.0, {name.lower()}. Вопрос — значение (англ., CC-CEDICT) и пиньинь, "
                    "ответ — упрощённые иероглифы.",
                    "Complete HSK Vocabulary, CC-CEDICT", "hsk")


# --- Korean ------------------------------------------------------------------------------------

VAL = re.compile(r'val="([^"]*)"')


def krdict_entries(path: Path):
    """LexicalEntry elements. The dump has a few unescaped '<' inside attribute values, so values
    are escaped line by line before parsing."""
    parser = ET.XMLPullParser(events=("end",))
    with open(path, encoding="utf-8") as f:
        for line in f:
            parser.feed(VAL.sub(lambda m: 'val="' + m.group(1).replace("<", "&lt;").replace(">", "&gt;") + '"', line))
            for _, el in parser.read_events():
                if el.tag == "LexicalEntry":
                    yield el
                    el.clear()
    parser.close()


def krdict_decks(catalog: Catalog, cache: Path) -> None:
    levels = {"초급": [], "중급": [], "고급": []}
    for part in KRDICT_PARTS:
        for el in krdict_entries(cache / f"krdict/{part}.xml"):
            feats = {x.get("att"): x.get("val") for x in el.findall("feat")}
            level = feats.get("vocabularyLevel")
            word = el.find("Lemma/feat[@att='writtenForm']")
            word = word.get("val").strip() if word is not None else ""
            if level in levels and feats.get("lexicalUnit") == "단어" and word and not re.search(r"[-^\s]", word):
                ru, en = [], []
                for sense in el.findall("Sense"):
                    for eq in sense.findall("Equivalent"):
                        q = {x.get("att"): x.get("val") for x in eq.findall("feat")}
                        lemma = (q.get("lemma") or "").strip()
                        if q.get("language") == "러시아어":
                            # counters and particles get a transliteration ("mari") as their lemma
                            if not re.search("[а-яёА-ЯЁ]", lemma):
                                lemma = (q.get("definition") or "").strip().rstrip(".")
                            if re.search("[а-яёА-ЯЁ]", lemma):
                                ru.append(lemma)
                        elif lemma and q.get("language") == "영어":
                            en.append(lemma)
                meaning = shorten(ru[:2], 50) or shorten(en[:2], 50)
                if meaning:
                    levels[level].append((f"{word}#{el.get('val')}", meaning, word, feats.get("partOfSpeech") or ""))
    names = {"초급": ("beginner", "Начальный (초급)"), "중급": ("intermediate", "Средний (중급)"), "고급": ("advanced", "Продвинутый (고급)")}
    for level, rows in levels.items():
        suffix, name = names[level]
        rows.sort(key=lambda r: hashlib.sha1(r[0].encode("utf-8")).hexdigest())
        catalog.add(f"ko-krdict-{suffix}", ["Корейский", name], "ko",
                    [(k, f, b, f"krdict {suffix}") for k, f, b, _ in rows],
                    f"{len(rows)} слов уровня «{level}» из словаря 한국어기초사전 (НИИ корейского языка). "
                    "Вопрос — значение по-русски, ответ — слово хангылем.",
                    "한국어기초사전 (krdict.korean.go.kr)", "krdict")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", type=Path, default=Path(__file__).resolve().parent / ".cache")
    ap.add_argument("--only", choices=["kanjivg", "decks"])
    args = ap.parse_args()
    fetch(args.cache)
    if args.only in (None, "kanjivg"):
        build_kanjivg(args.cache)
    if args.only in (None, "decks"):
        catalog = Catalog()
        kana_decks(catalog)
        entries = read_jmdict(args.cache)
        print(f"jmdict: {len(entries)} entries")
        kanji_decks(catalog, args.cache, entries)
        word_decks(catalog, entries)
        hsk_decks(catalog, args.cache)
        krdict_decks(catalog, args.cache)
        catalog.write()


if __name__ == "__main__":
    main()
