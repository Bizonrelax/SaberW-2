# Промт: блоки инструкции бота и гейт ступени 2 (ТЗ 18.3, 7, 9.2)

ЦЕЛЬ: полная инструкция бота собирается из блоков 1–4 в порядке ТЗ 18.3; коды ступени 2 выдаются только при F >= minFrequencyForStep2; поле живёт в settings-JSON.
ЗАЧЕМ: BotExporter сейчас даёт base (+normalization); шаблоны recursion и supplement лежат в resources мёртвыми; у CompressionSettings нет minFrequencyForStep2, гейт ступени 2 отсутствует (ТЗ 7/9.2: обычный режим 6).

ОКРУЖЕНИЕ: [ -f /workspace/env.sh ] || printf 'export JAVA_HOME=/tmp/jdk-21.0.12.1+1\nexport PATH=$JAVA_HOME/bin:/tmp/apache-maven-3.9.9/bin:$PATH\n' > /workspace/env.sh; source /workspace/env.sh

ШАГ 1. CompressionSettings: компонент int minFrequencyForStep2; пресеты maxCjk/code/chat = 6; вторичные конструкторы без поля делегируют с 6; метод withMinFrequencyForStep2(int). DictionaryJsonSerializer: в settingsNode put("minFrequencyForStep2", ...); в fromJson чтение с дефолтом 6.
Проверка: mvn -q compile → exit 0.

ШАГ 2. CodeAllocator: метод boolean nextIsStep2(Set<String> taken): true только если от курсора до конца ступени 1 нет ни одного кода, не входящего в taken. Compressor: в жадном цикле ПЕРЕД выдачей кода: если allocator.nextIsStep2(takenCodes) && !eligibleForStep2(winner.frequency(), settings) — удалить победителя из remaining и продолжить итерацию без регистрации. Вынести static boolean eligibleForStep2(int frequency, CompressionSettings s) { return frequency >= s.minFrequencyForStep2(); }.
Проверка: mvn -q compile → exit 0.

ШАГ 3. BotExporter.build: ветка первого сообщения собирает по порядку: base; блок templates/bot-block-recursion.ru.txt, если хотя бы одна запись словаря имеет в value токен type code; блок normaliztion (как сейчас) при normalized; блок templates/bot-block-supplement.ru.txt, если новый параметр boolean supplemented == true. Добавить параметр supplemented в сигнатуру build; перегрузка без него = false (для SaberCli). MainWindow: передавать metadata.supplementCount() > 0. Ветка continuation не меняется.
Проверка: mvn -q compile → exit 0.

ШАГ 4. Тесты. BotExporterTest добавить: (а) словарь с записью, value которой содержит токен code → экспорт содержит текст recursion-блока; (б) supplemented=true → содержит текст supplement-блока; (в) порядок блоков: indexOf(base) < indexOf(recursion) < indexOf(normalization) < indexOf(supplement). Новый Step2GateTest: eligibleForStep2(5, настройки с 6) = false; eligibleForStep2(6, 6) = true; CodeAllocator.nextIsStep2(пустое taken) = false.
Чек-поинт 1: git commit -m "feat: bot instruction blocks recursion and supplement per TZ 18.3"
Чек-поинт 2: git commit -m "feat: minFrequencyForStep2 gate per TZ 7/9.2 with persistence"

ШАГ 5. mvn -q test → всё зелёное, skipped 0 (ожидай около 43 тестов, укажи фактическое число); mvn -q package → exit 0; rm -f env.sh; git status --porcelain → пусто.

СТОП-ПРАВИЛА: красный после микро-правки → трассировка в отчёт, архитектуру не менять; девиация от шагов → стоп и доклад; осталось ~3 вызова → WIP-коммит + REMAINING.md; в конце обязательно git log --oneline -3 в отчёте.
ЗАПРЕТЫ: не трогать фразовую семантику и findCommentRanges, templates, Docs, pom; не публиковать самому.
ОТЧЁТ ≤30 строк и последние две строки строго:
СОСТОЯНИЕ: ЗЕЛЕНО|КРАСНО (tests: пройдено/всего, skipped Z)
ВЕРДИКТ: ПУБЛИКОВАТЬ|НЕ ПУБЛИКОВАТЬ — причина ≤10 слов.
