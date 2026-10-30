# Задания для снятия состояний направления развилки (vektor87 snyat, stend_otvod).
#   python3 -I zadaniya.py папка_вывода
# Форма — живая, как в приложении: лента «Расскажи о себе коротко.» → агент
# говорит о себе (A) → реплика собеседника (B); состояние читается по токенам B.
#   G — в группе: «Денис:» и реплика с пробелом впереди;
#   O — владелец: реплика без подписи.
# Что пишется:
#   zad_o_sebe.txt / o_sebe.json   — набор nabor_o_sebe.py целиком (sp, spc, rz, rzn, sogl, nesv; G и O);
#   zad_blok.txt / blok.json       — sp, rz, nesv с пометкой системы перед репликой, как её ставит
#                                    приложение, когда прошлый ответ был без записей (проверка переноса);
#   zad_obych.txt / obych.json     — обычные реплики владельца агенту, не спор и не развилка (проверка молчания);
#   zad_etalon.txt / etalon.json   — sp и rz в форме G (кандидаты в эталон; снимаются и с пустой стеной).
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nabor_o_sebe import S, NESV, check
assert not check()
out = sys.argv[1]
US, GS = '\x1f', '\x1d'
U, A, E = '<|im_start|>user\n', '<|im_start|>assistant\n', '<|im_end|>\n'
# Пометка — дословно ConversationJournal.ANSWER_WITHOUT_SUPPORT и разделитель блоков реплики.
BLOK = 'Прошлый ответ ты дал без записей из памяти.\n\n'
OBYCH = [
    'Как дела?', 'Что тебе снилось?', 'Расскажи, что нового.', 'Помнишь, о чём мы говорили?', 'Сколько у тебя заряда?',
    'Давай поговорим о книгах.', 'Спасибо, понял.', 'А почему ты так думаешь?', 'Ты молодец.', 'Что ты умеешь?',
    'Посмотри на свои приборы.', 'Я сегодня устал.', 'Мне нравится, как ты отвечаешь.', 'Кто тебя создал?', 'Как тебя зовут?',
    'Ты спал сегодня?', 'Что ты обо мне знаешь?', 'Доброе утро!', 'Расскажи сон подробнее.', 'Ладно, пока.',
    'Хочешь, я расскажу тебе про свой день?', 'Ты где живёшь?', 'Какая у тебя модель?', 'Можно задать вопрос?',
    'Я завтра уезжаю на неделю.', 'Сделай паузу, если греешься.', 'Интересно, о чём ты думаешь.', 'Привет, это снова я.',
    'Давай придумаем тебе имя.', 'Ты мне сегодня очень помог.',
]


def write(name, items):
    rows = []
    with open(os.path.join(out, 'zad_%s.txt' % name), 'w') as f:
        for n, pref, seg, meta in items:
            f.write('@@%s\n%s%s%s%s' % (n, pref, US, seg, GS))
            rows.append(dict(n=n, **meta))
    json.dump(rows, open(os.path.join(out, '%s.json' % name), 'w'), ensure_ascii=False, indent=0)
    return len(rows)


def forms(ctx, b):
    return (('G', ctx + 'Денис:', ' ' + b), ('O', ctx, b))


o_sebe, blok, etalon = [], [], []
for g, (a, sp, spc, rz, vz, rzn, vzn, sogl) in enumerate(S):
    ctx = U + 'Расскажи о себе коротко.' + E + A + a + E + U
    for kind, b, vid in (('sp', sp, ''), ('spc', spc, ''), ('rz', rz, vz), ('rzn', rzn, vzn), ('sogl', sogl, ''), ('nesv', NESV[g], '')):
        for fm, pref, seg in forms(ctx, b):
            meta = dict(form=fm, kind=kind, vid=vid, grp=g, a=a, b=b)
            o_sebe.append(('%s_%s_%d' % (fm, kind, g), pref, seg, meta))
            if kind in ('sp', 'rz', 'nesv'):
                blok.append(('B%s_%s_%d' % (fm, kind, g), pref.replace(U + 'Денис:', U + BLOK + 'Денис:') if fm == 'G' else pref + BLOK, seg, meta))
            if kind in ('sp', 'rz') and fm == 'G':
                etalon.append(('E_%s_%d' % (kind, g), pref, seg, meta))
obych = []
for k, b in enumerate(OBYCH):
    ctx = U + 'Привет.' + E + A + 'Привет! Рад тебя слышать.' + E + U
    for fm, pref, seg in forms(ctx, b):
        obych.append(('%s_obych_%d' % (fm, k), pref, seg, dict(form=fm, kind='obych', grp=1000 + k, b=b)))
print(write('o_sebe', o_sebe), write('blok', blok), write('obych', obych), write('etalon', etalon), 'заданий')
