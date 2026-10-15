# Сборка словаря начальных форм для приложения из OpenCorpora (через pymorphy3).
# python3 -I lemma_build.py ВЫХОД_СЛОВАРЯ ВЫХОД_ЭТАЛОНА
#   ВЫХОД_СЛОВАРЯ — app/src/main/assets/lemma_forms.txt (формат — в LemmaDictionary.kt)
#   ВЫХОД_ЭТАЛОНА — все формы словаря с разборами, для сверки чтения на Котлине
import sys, re, collections, time
import pymorphy3

KEEP = {'NOUN', 'ADJF', 'ADJS', 'COMP', 'VERB', 'INFN', 'PRTF', 'PRTS', 'GRND', 'NUMR', 'ADVB', 'NPRO', 'PRED',
        'PREP', 'CONJ', 'PRCL', 'INTJ', '1per', '2per', '3per', 'sing', 'plur', 'Name', 'Surn', 'Patr', 'Geox',
        'impr', 'past', 'pres', 'futr', 'incl', 'Apro', 'Prnt', 'Ques'}
PREF = ['наи', 'по']
WORD = re.compile('[а-я-]+')


def red(tag):
    return ','.join(sorted(set(g for g in str(tag).replace(' ', ',').split(',') if g in KEEP)))


def lcp(a, b):
    k = 0
    while k < min(len(a), len(b)) and a[k] == b[k]:
        k += 1
    return k


def main(out_dict, out_truth):
    D = pymorphy3.MorphAnalyzer().dictionary
    t0 = time.time()
    lex = collections.defaultdict(set)      # (paradigm, normal) -> {(form, tag)}
    truth = collections.defaultdict(set)    # form -> {lemma|tag}
    for word, tag, normal, para, idx in D.iter_known_words():
        w = word.replace('ё', 'е')
        nf = normal.replace('ё', 'е')
        if not WORD.fullmatch(w) or not WORD.fullmatch(nf):
            continue
        t = red(tag)
        lex[(para, nf)].add((w, t))
        truth[w].add(nf + '|' + t)
    print('форм', len(truth), 'лексем', len(lex), round(time.time() - t0), 'с', file=sys.stderr)

    pat = collections.Counter()
    rows = []
    for (para, nf), forms in lex.items():
        split = []
        for w, t in sorted(forms):
            pre = ''
            for p in PREF:
                if w.startswith(p) and not nf.startswith(p) and lcp(w[len(p):], nf) > lcp(w, nf):
                    pre, w = p, w[len(p):]
                    break
            split.append((pre, w, t))
        stem = split[0][1]
        for _, w, _ in split[1:]:
            stem = stem[:lcp(stem, w)]
        # Начальная форма — основа плюс своё окончание; если она с основой не
        # сходится, основа укорачивается до общего с ней начала.
        stem = stem[:lcp(stem, nf)]
        entries = tuple(sorted(set((pre, w[len(stem):], t) for pre, w, t in split)))
        key = (nf[len(stem):], entries)
        pat[key] += 1
        rows.append((stem, key))
    print('образцов', len(pat), file=sys.stderr)

    ends = collections.Counter(e for _, es in pat for e in es)
    tags = [t for t, _ in collections.Counter(e[2] for e in ends.elements()).most_common()]
    tid = {t: i for i, t in enumerate(tags)}
    eorder = [e for e, _ in ends.most_common()]
    eid = {e: i for i, e in enumerate(eorder)}
    porder = [p for p, _ in pat.most_common()]
    pid = {p: i for i, p in enumerate(porder)}

    lines = ['LEM1', f'T {len(tags)}']
    lines += tags
    lines.append(f'E {len(eorder)}')
    lines += [f'{pre}|{suf}|{tid[t]}' for pre, suf, t in eorder]
    lines.append(f'P {len(porder)}')
    lines += [f'{ls}|' + ' '.join(str(eid[e]) for e in es) for ls, es in porder]
    rows.sort(key=lambda r: (r[0], pid[r[1]]))
    lines.append(f'S {len(rows)}')
    prev = ''
    for st, p in rows:
        k = lcp(st, prev)
        lines.append(f'{k}{st[k:]} {pid[p]}')
        prev = st
    data = ('\n'.join(lines) + '\n').encode('utf-8')
    open(out_dict, 'wb').write(data)
    print('словарь', len(data), 'байт; тегов', len(tags), 'окончаний', len(eorder), 'образцов', len(porder),
          'основ', len(rows), file=sys.stderr)

    with open(out_truth, 'w', encoding='utf-8') as f:
        for w in sorted(truth):
            f.write(w + '\t' + ';'.join(sorted(truth[w])) + '\n')
    print('эталон', len(truth), 'форм', file=sys.stderr)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
