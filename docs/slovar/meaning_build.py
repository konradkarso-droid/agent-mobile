# Сборка файла векторов слов для прибора «смысл» (memory.nav.Meaning) из Navec.
# python3 -I meaning_build.py NAVEC_TAR ВЫХОД
#   NAVEC_TAR — navec_hudlit_v1_12B_500K_300d_100q.tar (https://github.com/natasha/navec)
#   ВЫХОД     — app/src/main/assets/meaning_vectors.bin (формат — в MeaningVectors.kt)
# Нужны: navec 0.10.0, pymorphy3 2.0.6 со словарём pymorphy3-dicts-ru 2.4.417150.4580142
# (те же, что у словаря начальных форм — lemma_build.py), wordfreq 3.1.1.
#
# Что берётся и почему:
#  - только начальные формы: слово Navec из русских букв и дефиса, которое у pymorphy3 само
#    себе начальная форма. Форму слова приложение приводит к начальной своим словарём
#    начальных форм, поэтому векторы форм не нужны — это главная экономия против Navec;
#  - «ё» заменена на «е», как во всех словарях приложения; из двух написаний берётся первое
#    по порядку Navec;
#  - только употребительные: частота лексемы (самая частая её форма по wordfreq, шкала zipf)
#    выше ZIPF_MIN. Редкие слова почти не встречаются в разговоре, а места занимают больше
#    половины. wordfreq решает только «брать или нет» — в файл из него ничего не попадает;
#  - коды векторов — те же, что у Navec (100 кусков по 3 числа, на кусок 1 байт — номер
#    образца из 256). Пережатие сильнее меняет, какую запись выбирает прибор.
import sys, struct, hashlib, collections
import pymorphy3
from navec import Navec
from wordfreq import zipf_frequency

ZIPF_MIN = 2.0
RESTART = 16
RU = set('абвгдеёжзийклмнопрстуфхцчшщъыьэюя-')


def main(navec_tar, out):
    nv = Navec.load(navec_tar)
    morph = pymorphy3.MorphAnalyzer()
    words = nv.vocab.words
    lemma_word = {}
    for w in words:
        if len(w) < 2 or not set(w) <= RU:
            continue
        if any(p.normal_form == w for p in morph.parse(w)):
            k = w.replace('ё', 'е')
            if k not in lemma_word:
                lemma_word[k] = w
    freq = collections.defaultdict(float)
    for w in words:
        if not set(w) <= RU:
            continue
        z = zipf_frequency(w, 'ru')
        if z <= 0:
            continue
        for p in morph.parse(w)[:2]:
            k = p.normal_form.replace('ё', 'е')
            if k in lemma_word and z > freq[k]:
                freq[k] = z
    keys = sorted((k for k in lemma_word if freq.get(k, 0) > ZIPF_MIN), key=lambda k: k.encode('utf-8'))
    pq = nv.pq
    M, K, SUB = pq.qdim, pq.centroids, pq.dim // pq.qdim
    index = {w: i for i, w in enumerate(words)}
    body = bytearray(b'UMV1')
    body += struct.pack('>6i', len(keys), pq.dim, M, K, SUB, RESTART)
    body += pq.codes.astype('>f4').tobytes()
    prev = b''
    for i, k in enumerate(keys):
        b = k.encode('utf-8')
        shared = 0
        if i % RESTART:
            while shared < min(len(b), len(prev), 255) and b[shared] == prev[shared]:
                shared += 1
        tail = b[shared:]
        assert len(tail) < 256
        body += bytes([shared, len(tail)]) + tail
        prev = b
    for k in keys:
        body += pq.indexes[index[lemma_word[k]]].astype('u1').tobytes()
    open(out, 'wb').write(body)
    print('начальных форм', len(keys), 'байт', len(body), 'sha256', hashlib.sha256(body).hexdigest())


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
