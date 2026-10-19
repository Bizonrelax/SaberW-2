# REMAINING — релиз v0.2.0 (блокировка токена)

БЛОКИРОВКА: выданный fine-grained PAT НЕ имеет разрешения "Workflows":
- git push с коммитом, меняющим .github/workflows/maven.yml → remote rejected ("refusing to allow a Personal Access Token to create or update workflow ... without `workflow` scope");
- POST /git/trees с путём .github/workflows/* → HTTP 403 "Resource not accessible by personal access token".
Поэтому ШАГ 3 (pom.xml + maven.yml) не мог быть опубликован; на main остались pom 0.1.0-SNAPSHOT и workflow с tag_name: latest / -DskipTests.

ЧТО СДЕЛАНО:
- ШАГ 0/1/2 выполнены (базовая линия OK; CI #67 success; релизы v.0.1.0/v.002/v.1.001 и теги удалены; создан релиз v0.1.0 на 5e7be7e).
- PR #20 (docs-only) смержен в main (merge-commit 0046138): Docs/RELEASE-NOTES-v0.2.0.md на main.
- CI #69 на main — success (старый workflow: skipTests, релиз "latest").

ЗАДАЧА ДЛЯ ВЛАДЕЛЬЦА (нужен PAT с разрешением Workflows или ручной пуш):
1. На ветке ptatimon-1 есть готовые локальные коммиты (в рабочей копии /workspace):
   - d7ca65a blob maven.yml: шаг Export PROJECT_VERSION из pom; tag_name: v${{ env.PROJECT_VERSION }}; name: SaberW 2 v${{ env.PROJECT_VERSION }}; overwrite_files и make_latest сохранены; убран -DskipTests (тесты 40/40 зелёные локально).
   - pom.xml: <version>0.2.0</version>.
2. Запушить ветку целиком (коммиты 1a0fb9d+86f6ebc либо один commit "chore: release v0.2.0" c blob'ами d7ca65a08dbcc46b1b6084309d6dd1cb83ac3995 и 6f8e52f759045f33a4dc124959ce6e33b4975cb3), открыть PR → merge в main.
3. Дождаться CI на main: должен создать релиз v0.2.0 (make_latest) с ассетами jar/exe/zip.
4. Проверить API: релизы = v0.1.0 + v0.2.0(latest); теги v.002/v.1.001/v.0.1.0 отсутствуют; при желании удалить легаси-релиз "latest".
