# word_stencil6.bin — источник, лицензия, как пересобрать

Трафарет детектора порчи слов (`app/src/main/java/com/uroboros/llm/WordDamage.kt`): все сочетания из 6 знаков подряд,
встречающиеся в русских словоформах «^слово$» («^» — начало слова, «$» — конец; «ё» приведена к «е»).
Сочетание — число из 36 бит (по 6 бит на знак: «^» 0, «$» 1, а..я 2..33, первый знак — в старших битах).

Формат: 4 байта `UST6`, 4 байта — число сочетаний (старший байт вперёд), затем разности соседних сочетаний по
возрастанию, каждая — varint (по 7 бит, младшие вперёд, старший бит байта означает «дальше есть ещё»).

Получен из словаря OpenCorpora через морфологический анализатор pymorphy3 (словарь pymorphy3-dicts-ru
2.4.417150.4580142): 907 543 сочетания, 1 180 157 байт. Сами словоформы в файл не входят.

- Словарь OpenCorpora: http://opencorpora.org — лицензия **CC BY-SA 3.0** (https://creativecommons.org/licenses/by-sa/3.0/).
- Словарь OpenCorpora восходит к словарю АОТ (http://aot.ru) и «Грамматическому словарю русского языка» А. А. Зализняка.

Файл — производное от словаря и распространяется под той же лицензией (CC BY-SA 3.0). Код приложения, который его
читает, этой лицензией не затрагивается.

Пересборка (`pip install pymorphy3`, затем `python3 sdelat_trafaret.py`; другая версия словаря даст другое число
сочетаний — сверить с числом выше и прогнать тесты WordDamageTest):

```python
# Собирает файл трафарета для детектора порчи (app/src/main/assets/word_stencil6.bin) из словаря OpenCorpora
# через pymorphy3: все сочетания из 6 знаков подряд в словоформах «^слово$» (ё→е, только слова из букв а–я).
# Знак — 6 бит: ^=0, $=1, а..я=2..33 (ё не бывает — приведена); сочетание — число из 36 бит, первый знак старший.
# Формат: 4 байта «UST6», 4 байта число сочетаний (big-endian), затем разности соседних чисел по возрастанию,
# каждая — varint (по 7 бит, младшие вперёд, старший бит байта — «дальше есть ещё»). Первая разность — от нуля.
import re, struct, pymorphy3
M = pymorphy3.MorphAnalyzer()
def code(ch): return 0 if ch == '^' else 1 if ch == '$' else ord(ch) - 0x430 + 2
G = set()
for w, *_ in M.dictionary.iter_known_words():
    w = w.replace('ё', 'е')
    if not re.fullmatch('[а-я]+', w): continue
    s = '^' + w + '$'
    for i in range(len(s) - 5):
        v = 0
        for ch in s[i:i + 6]: v = (v << 6) | code(ch)
        G.add(v)
vals = sorted(G); out = bytearray(b'UST6') + struct.pack('>I', len(vals)); prev = 0
for v in vals:
    d = v - prev; prev = v
    while True:
        b = d & 0x7f; d >>= 7
        if d: out.append(b | 0x80)
        else: out.append(b); break
open('word_stencil6.bin', 'wb').write(out)
print('сочетаний', len(vals), 'байт', len(out))
```
