# Промт: Фикс CI (Instant.now), миграция с Launch4j на jpackage, обновление экшенов

ЦЕЛЬ: Починить падающий CI на main: устранить недетерминизм времени в JSON, удалить устаревший launch4j, перевести упаковку на jpackage и обновить GitHub Actions (убрать Node.js 20).
ЗАЧЕМ: CI красный из-за гонки `Instant.now()` в `MetadataTest`; workflow падает на `gh release upload` и ругается на Node 20; `launch4j` признан устаревшим (согласно "Психология кодера-Гвен.md"). Прошлый бот нашел фикс времени, но не успел запушить (контейнер сгорел).

ОКРУЖЕНИЕ: 
[ -f /workspace/env.sh ] || cat > /workspace/env.sh <<'EOF'
export JAVA_HOME=/tmp/jdk-21.0.12.1+1
export PATH=$JAVA_HOME/bin:/opt/apache-maven-3.9.9/bin:$PATH
EOF
source /workspace/env.sh

ШАГИ:
1. Самопроверка и синхронизация: git fetch origin; git checkout main; git pull. 
   grep "Instant.now" src/main/java/com/saberw/core/DictionaryJsonSerializer.java.
   Создать ветку `ptabc-fix-ci` от `origin/main`.

2. Фикс Instant.now (DictionaryJsonSerializer.java):
   - Метод `toJson()` должен генерировать детерминированный JSON. 
   - Решение: зафиксировать время ОДИН РАЗ в начале метода (например, `Instant fixedTime = Instant.now();`) и использовать это замороженное значение для всех полей `updatedAt`/`addedAt` при сериализации и расчете `sizeBytes`.
   - Проверка: mvn -q test -Dtest=MetadataTest → зелёный.
   - Чек-поинт: git commit -m "fix: freeze Instant in DictionaryJsonSerializer to prevent sizeBytes race"

3. Удаление Launch4j (pom.xml):
   - Удалить секцию `<plugin>...launch4j-maven-plugin...</plugin>` полностью.
   - Проверка: mvn -q validate → exit 0.
   - Чек-поинт: git commit -m "chore: remove obsolete launch4j plugin per TZ"

4. Обновление GitHub Actions (.github/workflows/maven.yml):
   - Обновить все экшены до актуальных версий (checkout@v4, setup-java@v4, cache@v4, upload-artifact@v4), чтобы убрать варнинг "Node.js 20 is deprecated".
   - Убедиться, что job сборки запускается на `windows-latest` (это критично для работы jpackage под Windows).
   - Заменить шаг с launch4j на `jpackage`:
     `jpackage --type app-image --input target --main-jar <имя-jar-файла> --main-class com.saberw.gui.MainWindow --name "SaberW 2" --dest target/jpackage-out`
   - Упаковать папку `target/jpackage-out/SaberW 2` в ZIP (через PowerShell `Compress-Archive` на windows-latest).
   - Исправить шаг релиза: убрать сломанный `gh release upload`, использовать `softprops/action-gh-release@v2` и передать ему `files: target/SaberW-2-portable.zip`.
   - Чек-поинт: git commit -m "ci: migrate to jpackage, update actions to v4, fix release upload"

5. Финальный прогон: 
   mvn -q test (все зелёные, кроме @Disabled); 
   mvn -q package (exit 0).

6. Публикация (ПРОТОКОЛ ЧИСТОГО ФИНИША): 
   - git push origin HEAD:ptabc-fix-ci
   - Создать PR через API.
   - Если CI на PR зелёный — смержить через API.
   - Удалить рабочую ветку на remote.
   - В отчёте явно написать: «Ветка удалена, PR смержен. Любые кнопки слияния в UI GitHub недействительны».

СТОП-ПРАВИЛА: красный тест → трассировка, архитектуру не менять; осталось ~3 вызова → WIP-коммит + REMAINING.md.
ЗАПРЕТЫ: не трогать логику компрессии, не использовать launch4j, не оставлять Node 20 экшены, не делать merge вручную через UI.
ОТЧЁТ ≤30 строк. Последние две строки строго:
СОСТОЯНИЕ: ЗЕЛЕНО|КРАСНО
ВЕРДИКТ: ПУБЛИКОВАТЬ|НЕ ПУБЛИКОВАТЬ
