# Промт: детерминированное время и sizeBytes в DictionaryJsonSerializer (MetadataTest стабилен на Windows)

ЦЕЛЬ: MetadataTest зелёный на Linux и на windows-latest: сериализация и расчёт sizeBytes используют одно зафиксированное Instant и один фиксированный перевод строки; гонок Instant.now() нет.
ЗАЧЕМ: CI на Windows падал с расхождением sizeBytes 1460 против 1463 (гонка Instant.now() плюс системные переводы строк); прежний фикс погиб в контейнере неопубликованным.

ОКРУЖЕНИЕ: [ -f /workspace/env.sh ] || printf 'export JAVA_HOME=/tmp/jdk-21.0.12.1+1\nexport PATH=$JAVA_HOME/bin:/tmp/apache-maven-3.9.9/bin:$PATH\n' > /workspace/env.sh; source /workspace/env.sh

ШАГ 0. Самопроверка: git branch --show-current; grep -n "Instant.now" src/main/java/com/saberw/core/DictionaryJsonSerializer.java → перечень вхождений включить в отчёт.
ШАГ 1. Фикс: в начале сериализации фиксировать Instant один раз и использовать его во всех полях дат, которые выставляет сериализатор; sizeBytes вычислять из ТОЙ ЖЕ строки одного прохода сериализации с sizeBytes=0 (единый источник истины); перевод строки в JSON зафиксировать как LF независимо от ОС (настроить pretty-printer или измерять байты на непretty-строке, но тогда запись файла должна быть побайтово равна измеренной строке).
ШАГ 2. MetadataTest: независимый пересчёт sizeBytes вести через тот же публичный метод сериализатора, без ручной склейки строк с системными переводами.
ШАГ 3. mvn -q test -Dtest=MetadataTest → зелёный; mvn -q test → всё зелёное, skipped 0; mvn -q package → exit 0.
ШАГ 4. Гигиена ПЕРЕД коммитом: если .gitignore отличается от версии origin/main — git fetch origin && git checkout origin/main -- .gitignore; в индекс добавлять ТОЛЬКО src/main/java/com/saberw/core/DictionaryJsonSerializer.java и (если тронут) src/test/java/com/saberw/core/MetadataTest.java; target/, env.sh, *.jar, *.xml-отчёты не коммитить никогда.
ШАГ 5. Чек-поинт: git commit -m "fix: deterministic Instant and sizeBytes serialization (MetadataTest windows-stable)"; пуш в ветку ptabc-fix-instant; создать PR ptabc-fix-instant → main через API; НЕ мержить самому (merge делает владелец squash-ем).
СТОП-ПРАВИЛА: красный после микро-правки → трассировка в отчёт; девиация → стоп и доклад; ~3 вызова до лимита → WIP-коммит + REMAINING.md + пуш.
ЗАПРЕТЫ: не трогать .gitignore, pom, workflows, Compressor, GUI; не коммитить артефакты сборки.
ОТЧЁТ ≤20 строк и последние две строки строго:
СОСТОЯНИЕ: ЗЕЛЕНО|КРАСНО (tests: пройдено/всего, skipped Z)
ВЕРДИКТ: ПУБЛИКОВАТЬ|НЕ ПУБЛИКОВАТЬ — причина ≤10 слов.
