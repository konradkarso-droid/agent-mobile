# Выгрузка направления прямого судьи в текстовый файл приложения.
#   python3 vygruzka_napravleniy.py папка_наборов слой выход.txt
# Учёба — как в замерах сита (sito.py): показание «отношение» (1 спор,
# 2 согласие, 3 не связаны), утверждения (uch) + реплики (repl, без развилок),
# логистическая регрессия C=0,01 на одном слое. Файл читает
# memory.judge.DirectJudgeDirections.
import json, sys, numpy as np
from sklearn.linear_model import LogisticRegression
nab, layer, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]

def L(j, b):
    r = json.load(open(j)); return r, np.fromfile(b, dtype=np.float32).reshape(len(r), 43, 3072)
uch, Xu = L(nab + '/uch.json', nab + '/sost_uch.bin'); rp, Xp = L(nab + '/repl.json', nab + '/sost_repl.bin')
lab = {'spor': 1, 'sogl': 2, 'nesv': 3}
ip = [i for i, r in enumerate(rp) if r['kind'] in lab]
X = np.concatenate([Xu[:, layer], Xp[ip, layer]]).astype(np.float64)
y = np.array([int(r['y']) for r in uch] + [lab[rp[i]['kind']] for i in ip])
mu = X.mean(0); sd = X.std(0) + 1e-3
m = LogisticRegression(C=0.01, max_iter=4000).fit((X - mu) / sd, y)
assert list(m.classes_) == [1, 2, 3]
# своя формула должна давать то же, что predict_proba
Z = (X - mu) / sd; lg = Z @ m.coef_.T + m.intercept_; P = np.exp(lg - lg.max(1, keepdims=True)); P /= P.sum(1, keepdims=True)
assert np.abs(P - m.predict_proba(Z)).max() < 1e-9, 'формула не та'
# округление до 7 значащих: проверить, что вероятности не уехали
def r7(a): return np.array([float('%.7g' % v) for v in a])
mu7, sd7, W7, b7 = r7(mu), r7(sd), np.array([r7(w) for w in m.coef_]), r7(m.intercept_)
lg7 = ((X - mu7) / sd7) @ W7.T + b7; P7 = np.exp(lg7 - lg7.max(1, keepdims=True)); P7 /= P7.sum(1, keepdims=True)
print('расхождение после округления: %.2e' % np.abs(P7 - P).max())
with open(out, 'w', encoding='utf-8') as f:
    f.write('# Направление прямого судьи: чтение отношения двух записей по состоянию модели.\n')
    f.write('# Откуда и как пересобрать — pryamoy_sud_SOURCE.md рядом. Числа — не правка руками.\n')
    f.write('версия 1\nслой %d\nширина %d\nклассы спор согласие несвязаны\n' % (layer, X.shape[1]))
    f.write('mu ' + ' '.join('%.7g' % v for v in mu7) + '\n')
    f.write('sd ' + ' '.join('%.7g' % v for v in sd7) + '\n')
    for k, w in zip(('w_spor', 'w_soglasie', 'w_nesvyazany'), W7): f.write(k + ' ' + ' '.join('%.7g' % v for v in w) + '\n')
    f.write('b ' + ' '.join('%.7g' % v for v in b7) + '\n')
print('учебных', len(y), '| p(спор) на учёбе: споры средн. %.2f, прочие %.2f' % (P[y == 1, 0].mean(), P[y != 1, 0].mean()))
