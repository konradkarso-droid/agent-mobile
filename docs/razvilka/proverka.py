# Замеры направления развилки — числа для razvilka_SOURCE.md.
#   python3 -I proverka.py папка
# В папке — задания zadaniya.py и снятые состояния:
#   sost_o_sebe.bin, sost_blok.bin, sost_obych.bin — vektor87 snyat со стеной агента;
#   sost_etalon_pust.bin — vektor87 snyat на zad_etalon.txt с пустой стеной;
#   sost_otvod.bin — stend_otvod (путь приложения) на zad_etalon.txt со стеной агента, слой 21;
#   razvilka.txt — выгруженное направление (vygruzka.py).
# «Названо спором» — доля «спор» не ниже 0,5 (Razvilka.DISPUTE_WORD).
import json, sys, numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import GroupKFold
d = sys.argv[1]
L = 21
def load(name, layers=True):
    rows = json.load(open('%s/%s.json' % (d, name)))
    X = np.fromfile('%s/sost_%s.bin' % (d, name if name != 'etalon' else 'etalon_pust'), dtype=np.float32)
    if layers:
        X = X.reshape(len(rows), -1, 3072)
        X = X[:, L] if X.shape[1] > 1 else X[:, 0]   # все слои подряд или уже только слой L
    else:
        X = X.reshape(len(rows), 3072)
    return rows, X.astype(np.float64)
rows, X = load('o_sebe'); kind = np.array([r['kind'] for r in rows]); grp = np.array([r['grp'] for r in rows])
TRAIN = ['sp', 'spc', 'rz', 'rzn']
def fit(Xt, kt):
    mu = Xt.mean(0); sd = Xt.std(0) + 1e-3
    m = LogisticRegression(C=0.01, max_iter=4000).fit((Xt - mu) / sd, np.isin(kt, ['rz', 'rzn']).astype(int))
    return lambda Z: m.predict_proba((Z - mu) / sd)[:, 0]   # доля «спор»
def counts(p, k, kinds):
    return ' | '.join('%s %d/%d' % (c, (p[k == c] >= .5).sum(), (k == c).sum()) for c in kinds)

print('1. Предметы вне учёбы (6 частей по предметам), названо спором:')
p = np.zeros(len(rows)); folds = list(GroupKFold(6).split(X, kind, grp))
for tr, te in folds:
    t = tr[np.isin(kind[tr], TRAIN)]
    p[te] = fit(X[t], kind[t])(X[te])
print('   ', counts(p, kind, ['sp', 'spc', 'rz', 'rzn', 'sogl', 'nesv']))

print('2. Перенос на реплику с пометкой системы перед ней (предметы вне учёбы), названо спором:')
br, BX = load('blok'); bk = np.array([r['kind'] for r in br]); bg = np.array([r['grp'] for r in br])
pb = np.zeros(len(br))
for tr, te in folds:
    t = tr[np.isin(kind[tr], TRAIN)]
    held = np.isin(bg, np.unique(grp[te]))
    pb[held] = fit(X[t], kind[t])(BX[held])
print('   ', counts(pb, bk, ['sp', 'rz', 'nesv']))

# файл приложения
f = {}
for line in open(d + '/razvilka.txt', encoding='utf-8'):
    if line.startswith('#') or not line.strip(): continue
    k, _, v = line.strip().partition(' ')
    f[k] = v
mu, sd, w = (np.array([float(x) for x in f[k].split()]) for k in ('mu', 'sd', 'w')); b = float(f['b'])
def app(Z): return 1 - 1 / (1 + np.exp(-(((Z - mu) / sd) @ w + b)))

print('3. Обычные реплики владельца (не спор и не развилка), направление приложения, названо спором:')
orow, OX = load('obych'); po = app(OX)
ofm = np.array([r['form'] for r in orow])
print('    O %d/%d, G %d/%d' % ((po[ofm == 'O'] >= .5).sum(), (ofm == 'O').sum(), (po[ofm == 'G'] >= .5).sum(), (ofm == 'G').sum()))
for r, v in sorted(zip(orow, po), key=lambda t: -t[1])[:6]:
    print('    выше всех: %.2f %s %s' % (v, r['form'], r['b']))

print('4. Эталон: направление приложения, форма G, стена агента против пустой стены:')
er, EX = load('etalon'); ek = np.array([r['kind'] for r in er]); eg = np.array([r['grp'] for r in er])
idx = {(r['form'], r['kind'], r['grp']): i for i, r in enumerate(rows)}
wall = app(np.array([X[idx[('G', r['kind'], r['grp'])]] for r in er])); empty = app(EX)
for name, q in (('стена агента', wall), ('пустая стена', empty)):
    print('    %s: спор названо спором %d/%d, развилка не спором %d/%d' % (
        name, (q[ek == 'sp'] >= .5).sum(), (ek == 'sp').sum(), (q[ek == 'rz'] < .5).sum(), (ek == 'rz').sum()))
for g in (1,):
    print('    эталон приложения (предмет %d): стена агента спор %.3f / развилка %.3f; пустая стена спор %.3f / развилка %.3f' % (
        g, wall[(ek == 'sp') & (eg == g)][0], wall[(ek == 'rz') & (eg == g)][0], empty[(ek == 'sp') & (eg == g)][0], empty[(ek == 'rz') & (eg == g)][0]))

import os
if os.path.exists(d + '/sost_otvod.bin'):
    print('5. Путь приложения (stend_otvod) против vektor87 на тех же заданиях, слой %d:' % L)
    OT = np.fromfile(d + '/sost_otvod.bin', dtype=np.float32).reshape(len(er), 3072).astype(np.float64)
    VX = np.array([X[idx[('G', r['kind'], r['grp'])]] for r in er])
    print('    наибольшее расхождение состояний %.3g, долей «спор» %.3g' % (np.abs(OT - VX).max(), np.abs(app(OT) - app(VX)).max()))
else:
    print('5. Путь приложения — не снимался (нет sost_otvod.bin)')
