# Промт: BotExporter — полное значение записи и защита разделителя (ТЗ 10.2, 18.6, критерий 23.1.8)

ЦЕЛЬ: экспорт «Для бота» сериализует значение записи как конкатенацию ВСЕХ токенов с экранированием (ТЗ 10.2) и выбирает разделитель блоков без коллизий (ТЗ 18.6).
ЗАЧЕМ: сейчас appendDictLines печатает только tokens().get(0).text() — многотокенные значения обрежутся; экранирования нет; разделитель "___" жёстко зашит и может встретиться в тексте (нарушение 23.1.8).

ОКРУЖЕНИЕ: [ -f /workspace/env.sh ] || printf 'export JAVA_HOME=/tmp/jdk-21.0.12.1+1\nexport PATH=$JAVA_HOME/bin:/tmp/apache-maven-3.9.9/bin:$PATH\n' > /workspace/env.sh; source /workspace/env.sh

ШАГ 1. В BotExporter добавить static String serializeValue(EntryValue v): конкатенация токенов по порядку: type text → t.text(), type code → t.code(); затем экранирование полученной строки в порядке: обратная косая черта → удвоенная обратная косая черта; таб → последовательность из косой черты и t; перевод строки → косая черта и n; возврат каретки → косая черта и r. Заменить в appendDictLines вывод en.value().tokens().get(0).text() на serializeValue(en.value()).
Проверка: mvn -q compile → exit 0.

ШАГ 2. Защита разделителя (ТЗ 18.6): static String resolveSeparator(String dictText, String instruction, String compressed): кандидаты по порядку: три подчёркивания, три тильды, три решётки, три знака равенства, три звёздочки; вернуть первый, который НЕ встречается ни в одном из трёх аргументов; если все встречаются — IllegalStateException("All block separators collide"). В build(): собрать instruction и dictText, вычислить sep через resolveSeparator(dictText, instruction, compressed) и использовать sep вместо жёстких "___" во всех местах (после словаря в первом сообщении, после заголовка continuation, в ветке "Сообщение:").
Проверка: mvn -q compile → exit 0.

ШАГ 3. Тесты: новый src/test/java/com/saberw/core/BotExporterTest.java: (а) значение из трёх токенов (text, code, text) экспортируется одной строкой как конкатенация с кодом посередине; значение с переводом строки и табом внутри текстового токена экспортируется с экранированием (косая черта n, косая черта t), обратная косая черта удваивается; (б) compressed, содержащий три подчёркивания, даёт экспорт с разделителем из трёх тильд; (в) все пять кандидатов встречаются в блоках → IllegalStateException.
Проверка: mvn -q test -Dtest=BotExporterTest → 3/3 зелёные.
Чек-поинт: git commit -m "fix: bot export serializes full values with escaping and guards block separator (TZ 10.2, 18.6)"

ШАГ 4. Полный прогон: mvn -q test → Tests run: 37, Failures: 0, Errors: 0, Skipped: 0; mvn -q package → exit 0. Гигиена: rm -f env.sh; git status --porcelain → пусто.

СТОП-ПРАВИЛА: красный после микро-правки → трассировка в отчёт, архитектуру не менять; девиация от шагов → стоп и доклад; осталось ~3 вызова → WIP-коммит + REMAINING.md.
ЗАПРЕТЫ: не трогать Compressor, CompressionSettings, MainWindow, pom, Docs, templates; не публиковать самому.
ОТЧЁТ ≤20 строк и последние две строки строго:
СОСТОЯНИЕ: ЗЕЛЕНО|КРАСНО (tests: пройдено/всего, skipped Z)
ВЕРДИКТ: ПУБЛИКОВАТЬ|НЕ ПУБЛИКОВАТЬ — причина ≤10 слов.