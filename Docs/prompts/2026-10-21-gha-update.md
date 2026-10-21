# Промт: обновление GitHub Actions (убрать варнинги Node.js 20, починить кэш)

ЦЕЛЬ: все .yml-файлы в .github/workflows/ используют актуальные версии экшенов (без варнингов о deprecated Node.js 20); кэш Maven восстанавливается без ошибок; CI-прогон на main зелёный.
ЗАЧЕМ: в Actions на релизе v0.2.0 видны варнинги «Node.js 20 is deprecated» и «Failed to restore cache». Это не ломает сборку, но делает CI шумным и медленным.

ОКРУЖЕНИЕ: [ -f /workspace/env.sh ] || printf 'export JAVA_HOME=/tmp/jdk-21.0.12.1+1\nexport PATH=$JAVA_HOME/bin:/tmp/apache-maven-3.9.9/bin:$PATH\n' > /workspace/env.sh; source /workspace/env.sh

ШАГ 0. Самопроверка: test -f .github/workflows/maven.yml → да; grep -c "actions/checkout" .github/workflows/maven.yml → ≥1. Любое НЕТ → СТОП.

ШАГ 0.5. Инициализация ветки: git fetch origin && git checkout -b ptabc-gha-update origin/main. Всю работу вести ТОЛЬКО в ptabc-gha-update.

ШАГ 1. Прочитать все .yml-файлы в .github/workflows/. Для каждого экшена заменить версию по таблице:
- actions/checkout@* → actions/checkout@v4
- actions/setup-java@* → actions/setup-java@v4
- actions/cache@* → actions/cache@v4
- actions/upload-artifact@* → actions/upload-artifact@v4
- actions/download-artifact@* → actions/download-artifact@v4
- softprops/action-gh-release@* → softprops/action-gh-release@v2
- akathist/maven-launch4j-plugin (если используется в pom.xml) — оставить, это не экшен
- Любые другие экшены, где в строке uses: видно @v1/@v2/@v3 — обновить до последней стабильной мажорной версии (проверить через GitHub Marketplace API: GET https://api.github.com/repos/<owner>/<repo>/releases/latest, взять tag_name).
Проверка: mvn -q validate → exit 0 (синтаксис pom не сломан).

ШАГ 2. Кэш Maven: в actions/cache@v4 параметр key должен содержать hashFiles('**/pom.xml'), restore-keys — префикс без хэша. Убедиться, что path: ~/.m2/repository (не target). Если в maven.yml кэш уже есть — оставить структуру, обновить только версию экшена.

ШАГ 3. Permissions: в начале каждого workflow-файла добавить блок:
permissions:
  contents: write
  actions: read
  checks: write
Это убирает варнинги про «GITHUB_TOKEN has insufficient permissions».

ШАГ 4. mvn -q test → всё зелёное, skipped 0; mvn -q package → exit 0. Чек-поинт: git commit -m "chore: update GitHub Actions to v4 and fix cache restore" + пуш в ptabc-gha-update.

ШАГ 5. Публикация: PR ptabc-gha-update → main через API; НЕ мержить самому — дождаться команды владельца «merge».

СТОП-ПРАВИЛА: красный после микро-правки → трассировка; девиация → стоп; ~3 вызова до лимита → WIP-коммит + REMAINING.md + пуш.
ЗАПРЕТЫ: не трогать Java-код, тесты, Docs, pom.xml (кроме случая, если там есть зашитая версия экшена); не удалять теги/релизы; не merge-ить PR без команды.
ОТЧЁТ ≤30 строк, последние две строки строго:
СОСТОЯНИЕ: ЗЕЛЕНО|КРАСНО (tests: пройдено/всего, skipped Z)
ВЕРДИКТ: ПУБЛИКОВАТЬ|НЕ ПУБЛИКОВАТЬ — причина ≤10 слов.
