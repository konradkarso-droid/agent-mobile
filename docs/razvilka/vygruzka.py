# Выгрузка направления развилки в текстовый файл приложения.
#   python3 -I vygruzka.py папка слой выход.txt
# В папке — o_sebe.json и sost_o_sebe.bin (vektor87 snyat со стеной агента на
# zad_o_sebe.txt из zadaniya.py). Учёба: спор (sp) и спор-ловушка (spc) против
# развилки (rz) и развилки-ловушки (rzn), обе формы (G и O), все предметы;
# логистическая регрессия C=0,01 на одном слое. Ловушки в учёбе обязательны:
# без них направление учит слова развилки («вчера», «Иван»), а не её смысл.
# Файл читает memory.nav.RazvilkaDirection; откуда числа — razvilka_SOURCE.md.
import json, sys, numpy as np
from sklearn.linear_model import LogisticRegression
nab, layer, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
rows = json.load(open(nab + '/o_sebe.json'))
# В файле — все слои подряд (vektor87 snyat) или уже только нужный (снятое по кускам в Colab).
X = np.fromfile(nab + '/sost_o_sebe.bin', dtype=np.float32).reshape(len(rows), -1, 3072)
X = (X[:, layer] if X.shape[1] > 1 else X[:, 0]).astype(np.float64)
kind = np.array([r['kind'] for r in rows])
m = np.isin(kind, ['sp', 'spc', 'rz', 'rzn'])
Xt, y = X[m], np.isin(kind[m], ['rz', 'rzn']).astype(int)
mu = Xt.mean(0); sd = Xt.std(0) + 1e-3
lr = LogisticRegression(C=0.01, max_iter=4000).fit((Xt - mu) / sd, y)
# своя формула приложения: доля «спор» = 1 − сигмоида логита «развилки»
def p_spor(Z, mu, sd, w, b):
    return 1 - 1 / (1 + np.exp(-(((Z - mu) / sd) @ w + b)))
P = p_spor(Xt, mu, sd, lr.coef_[0], lr.intercept_[0])
assert np.abs(P - lr.predict_proba((Xt - mu) / sd)[:, 0]).max() < 1e-9, 'формула не та'
def r7(a): return np.array([float('%.7g' % v) for v in a])
mu7, sd7, w7, b7 = r7(mu), r7(sd), r7(lr.coef_[0]), float('%.7g' % lr.intercept_[0])
P7 = p_spor(Xt, mu7, sd7, w7, b7)
print('расхождение после округления: %.2e' % np.abs(P7 - P).max())
with open(out, 'w', encoding='utf-8') as f:
    f.write('# Направление развилки: спор реплики с фактом агента против ложной развилки, по состоянию модели.\n')
    f.write('# Откуда и как пересобрать — razvilka_SOURCE.md рядом. Числа — не правка руками.\n')
    f.write('версия 1\nслой %d\nширина %d\nклассы спор развилка\n' % (layer, X.shape[1]))
    f.write('mu ' + ' '.join('%.7g' % v for v in mu7) + '\n')
    f.write('sd ' + ' '.join('%.7g' % v for v in sd7) + '\n')
    f.write('w ' + ' '.join('%.7g' % v for v in w7) + '\n')
    f.write('b %.7g\n' % b7)
print('учебных', len(y), '| доля «спор» на учёбе: споры средн. %.3f, развилки %.3f' % (P[y == 0].mean(), P[y == 1].mean()))
# доли на эталонных кандидатах — для выбора эталона и его записи в описание
for g in (1, 0, 3):
    for k in ('sp', 'rz'):
        i = [j for j, r in enumerate(rows) if r['form'] == 'G' and r['kind'] == k and r['grp'] == g][0]
        print('эталон-кандидат предмет %d %s: %.3f  %s' % (g, k, p_spor(X[i:i + 1], mu7, sd7, w7, b7)[0], rows[i]['b']))
