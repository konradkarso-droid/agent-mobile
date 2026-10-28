# Учебные задания прямого судьи из двух наборов пар.
#   python3 zadaniya.py pary_sud.json pary_repliki.json папка_вывода
# Пишет uch.json + zad_uch_b.txt (утверждения: 1 спор, 2 согласие, 3 не связаны)
# и repl.json + zad_repl_b.txt (реплики: spor, razv, sogl, nesv). Каждая пара —
# в двух порядках (суффикс f — как в наборе, b — наоборот).
# Задание: «@@имя\n<начало>\x1f<отрезок>\x1d», начало — «<|im_start|>user\nЗапись А: …\nЗапись Б:»,
# отрезок — « » + запись Б. Состояние снимается по токенам отрезка (vektor87 snyat,
# стена — пустое системное «<|im_start|>system\n<|im_end|>\n»).
import json, sys, os
sud, repl, out = sys.argv[1:4]
US, GS = '\x1f', '\x1d'

def write(rows, name):
    with open(os.path.join(out, 'zad_%s_b.txt' % name), 'w') as f:
        for r in rows:
            f.write('@@%s\n<|im_start|>user\nЗапись А: %s\nЗапись Б:%s %s%s' % (r['n'], r['a'], US, r['b'], GS))
    json.dump(rows, open(os.path.join(out, '%s.json' % name), 'w'), ensure_ascii=False, indent=0)

d = json.load(open(sud)); rows = []
for y in '123':
    for k, (a, b) in enumerate(d[y]):
        for o, (x1, x2) in (('f', (a, b)), ('b', (b, a))):
            rows.append(dict(n='u%s_%02d%s' % (y, k, o), y=y, pair='u%s_%02d' % (y, k), a=x1, b=x2))
write(rows, 'uch')

d = json.load(open(repl)); rows = []
for kind in ('spor', 'razv', 'sogl', 'nesv'):
    for k, it in enumerate(d[kind]):
        a, b = it[0], it[1]  # у развилок третьим идёт вид допущения
        for o, (x1, x2) in (('f', (a, b)), ('b', (b, a))):
            rows.append(dict(n='p%s_%02d%s' % (kind, k, o), kind=kind, pair='%s_%02d' % (kind, k), a=x1, b=x2))
write(rows, 'repl')
print('готово')
