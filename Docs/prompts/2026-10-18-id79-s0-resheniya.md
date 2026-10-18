# Промт: ИД из 79 символов, допуск S=0, записи РЕШЕНИЯ (решения R-002, R-003, R-008, R-009)

ЦЕЛЬ: DictionaryIdGenerator использует алфавит из 79 символов по полному ТЗ §5; кандидаты с S=0 допускаются в словарь по ТЗ п.7; в РЕШЕНИЯ.md внесены записи R-008 и R-009; полный набор тестов зелёный.
ЗАЧЕМ: решения владельца от 17.10.2026 (R-002, R-003) ещё не отражены в коде; записи журнала R-008/R-009 согласованы, но не внесены; владелец не правит документы руками.

ОКРУЖЕНИЕ: [ -f /workspace/env.sh ] || printf 'export JAVA_HOME=/tmp/jdk-21.0.12.1+1\nexport PATH=$JAVA_HOME/bin:/tmp/apache-maven-3.9.9/bin:$PATH\n' > /workspace/env.sh; source /workspace/env.sh

ШАГ 0. Самопроверка: git branch --show-current → ptatimon-2; grep -c "serializeValue" src/main/java/com/saberw/core/BotExporter.java → ≥1; grep -c "minFrequencyForStep2" src/main/java/com/saberw/core/CompressionSettings.java → ≥1. Любое НЕТ → СТОП и доклад, ничего не править.

ШАГ 1. DictionaryIdGenerator: заменить ALPHABET на строку из 79 символов ровно в таком порядке: ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%&()=+[]{}^;,  (проверь в коде комментарием: 62 буквы/цифры + 17 символов = 79, символы безопасны для имён файлов во всех ОС, § и . и пробел и - и _ не входят). В RoundTripTest.dictionaryIdIsAlphanumericSixChars заменить регулярку на класс из 79 символов (внутри класса экранировать [ ] и \): id.matches("[A-Za-z0-9!@#$%&()=+\\[\\]{}^;,]{6}"). Длина ИД остаётся 6, генерация случайная, проверка уникальности регистронезависимая — не менять.
Проверка: mvn -q test -Dtest=RoundTripTest → зелёный.
Чек-поинт: git commit -m "feat: dictionary ID alphabet of 79 chars per TZ 5 (R-002)"

ШАГ 2. Допуск S=0 (R-003, ТЗ п.7: кодировать если S ≥ порог, порог по умолчанию 0): в Compressor.compress в гейте слов-кандидатов убрать добавочное условие savingsNew(f, l, 1) > 0, оставить savingsNew(f, l, 1) >= settings.thresholdS(); в findPhrases гейт savingsNew(f, l, 1) > 0 заменить на savingsNew(f, l, 1) >= settings.thresholdS(); в prune-цикле условие doomed заменить на (c.frequency() < settings.minFrequency() || c.savings() < settings.thresholdS()), убрав оба старых члена с <=. Смысл: нейтральные (S=0) записи теперь живут в словаре, убыточные (S<порога) по-прежнему отбрасываются.
Проверка: mvn -q test → Tests run: 43, Failures: 0, Errors: 0, Skipped: 0 (compressedNeverLongerThanInput обязан остаться зелёным: S=0 не удлиняет вывод).
Чек-поинт: git commit -m "feat: accept neutral candidates S=0 per TZ 7 (R-003)"

ШАГ 3. В Docs/Техническое Задание/РЕШЕНИЯ.md дописать в журнал решений ровно две строки:
R-008 | 2026-10-17 | Фразовый проход на main был мёртв с 13.10.2026 (раны сбрасывались на SEPARATOR-атомах, findPhrases возвращал 0 кандидатов); релизы до фикса сжимали только слова | Исправлено коммитом fix: preserve exact separators in phrase values (round-trip) (PR #9); поведение закреплено тестами PhraseCompressionTest и RoundTripTest | трассировка Dbg3 от 17.10.2026
R-009 | 2026-10-18 | Контейнер кодера не имеет git-remote, история среза ветки — один grafted-коммит; fetch/merge/pull внутри контейнера невозможны | Все слияния веток выполняются только на стороне GitHub; промт-файлы начинаются с grep-проверки базовой линии со STOP; в контейнере запрещены fetch/merge/pull/remote add | отчёт кодера от 18.10.2026 (fatal: origin does not appear to be a git repository)
Чек-поинт: git commit -m "docs: add R-008 and R-009 to RESHENIYA journal"

ШАГ 4. mvn -q test → Tests run: 43, Failures: 0, Errors: 0, Skipped: 0; mvn -q package → exit 0; rm -f env.sh; git status --porcelain → пусто; git log --oneline -4 включить в отчёт.

СТОП-ПРАВИЛА: красный после микро-правки → трассировка (вывод в отчёт), архитектуру не менять; девиация от шагов → стоп и доклад; осталось ~3 вызова → WIP-коммит + REMAINING.md.
ЗАПРЕТЫ: git fetch/merge/pull/remote add в контейнере — никогда; не трогать фразовую exact-span семантику, findCommentRanges, BotExporter, metadata, templates, pom; не публиковать самому.
ОТЧЁТ ≤30 строк и последние две строки строго:
СОСТОЯНИЕ: ЗЕЛЕНО|КРАСНО (tests: пройдено/всего, skipped Z)
ВЕРДИКТ: ПУБЛИКОВАТЬ|НЕ ПУБЛИКОВАТЬ — причина ≤10 слов.
