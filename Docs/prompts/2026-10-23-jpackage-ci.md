# Промт: Переход на jpackage и починка CI (П1-L4J, П2-CI)

ЦЕЛЬ (1 строка): Удалить launch4j из pom.xml и maven.yml, настроить сборку EXE через jpackage, добиться зелёного CI на main.
ГОТОВНОСТЬ: `mvn -q test` → `Failures: 0`; `cat .github/workflows/maven.yml | grep jpackage` → найдено; `grep launch4j pom.xml` → не найдено.
КОНТЕКСТ (3–5 строк): Мы ранее утвердили отказ от launch4j в пользу jpackage (ТЗ), но кодер оставил старый плагин, из-за чего падает CI и не собираются портативные EXE. Ветка `release-v0.2.0` зависла, нужно навести порядок в `main`. Файлы трогать: `pom.xml`, `.github/workflows/maven.yml`. НЕ трогать `src/`.
ОКРУЖЕНИЕ: [ -f /workspace/env.sh ] || printf 'export JAVA_HOME=/tmp/jdk-21.0.12.1+1\nexport PATH=$JAVA_HOME/bin:/tmp/apache-maven-3.9.9/bin:$PATH\n' > /workspace/env.sh; source /workspace/env.sh
ШАГИ (нумерованные микро-шаги):
  1. Самопроверка: `git fetch origin`; `git checkout -b ptabc-jpackage origin/main`.
  2. pom.xml: Полностью удалить блок `<plugin>` с `launch4j-maven-plugin`.
  3. maven.yml: Убрать шаг с launch4j. Добавить шаг сборки через jpackage: `jpackage --type app-image --name SaberW-2 --main-jar target/SaberW-2-*.jar --main-class com.saberw.gui.MainWindow --dest target/jpackage-out`. Добавить архивацию `target/jpackage-out/SaberW-2` в `SaberW-2-portable-win64.zip`.
  4. Тесты: `mvn -q test` → зелёный.
  5. Чек-поинт: `git commit -m "ci: replace launch4j with jpackage per TZ"`; `git push origin ptabc-jpackage`.
  6. PR: Создать PR в main через API GitHub (токен в первом сообщении).
СТОП-ПРАВИЛА: красный тест → трассировка (вывод в отчёт), архитектуру не менять; нужна девиация от сниппета → стоп и доклад; осталось ~3 вызова → протокол конца.
ЗАПРЕТЫ: launch4j; ручное редактирование ТЗ; git merge в контейнере; не трогать src/main/java.
ПРОТОКОЛ КОНЦА: коммит WIP + REMAINING.md в коммите; отчёт ≤30 строк: шаги/статус/хвосты вывода/хэши/долги.
СОСТОЯНИЕ: ЗЕЛЕНО|КРАСНО (tests: пройдено/всего, skipped Z)
ВЕРДИКТ: ПУБЛИКОВАТЬ|НЕ ПУБЛИКОВАТЬ — причина ≤10 слов.
