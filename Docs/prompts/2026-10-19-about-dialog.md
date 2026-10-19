# Промт: кнопка «Опр» и окно «О программе» (6 языков, спиннер шрифта, копирование)

ЦЕЛЬ: в главном окне кнопка «Опр» (подсказка «О программе»), открывающая модальное окно с описанием программы на 6 языках, спиннером размера шрифта и кнопкой «Бу» (копировать текст окна в буфер); тексты — в ресурсах; выбор языка и размер шрифта сохраняются в settings-JSON.
ЗАЧЕМ: пользователю нужно из самой программы узнать, что это, зачем, где скачать и что установить (JRE, шрифты), чтобы коды и эмодзи отображались без квадратов. Полное имя владельца не указывается нигде.

ОКРУЖЕНИЕ: [ -f /workspace/env.sh ] || printf 'export JAVA_HOME=/tmp/jdk-21.0.12.1+1\nexport PATH=$JAVA_HOME/bin:/tmp/apache-maven-3.9.9/bin:$PATH\n' > /workspace/env.sh; source /workspace/env.sh

ШАГ 0. Самопроверка: ветка ptatimon-1; grep -c "79" src/main/java/com/saberw/core/DictionaryIdGenerator.java ≥1; grep -c "serializeValue" src/main/java/com/saberw/core/BotExporter.java ≥1. Любое НЕТ → СТОП и доклад.
ШАГ 0.5. Синхронизация: git fetch origin && git merge origin/main (ожидается fast-forward или merge-пузырь; конфликтов быть не должно) && git push origin HEAD:ptatimon-1.

ШАГ 1. Ресурсы: создать src/main/resources/about/about.ru.txt, about.en.txt, about.zh.txt, about.fr.txt, about.es.txt, about.pt.txt. Русская версия — дословно следующий текст; остальные — его перевод с сохранением структуры, ссылок и чисел:
SaberW 2 — программа сжатия текста для переписки с нейросетями.
Что делает: находит в вашем сообщении повторяющиеся слова и фразы и заменяет их короткими кодами из диапазона CJK Extension A; нейросети передаёт словарь и инструкцию. Ответ бота распаковывается обратно без потерь.
Зачем: чтобы большие сообщения, код и логи помещались в лимит контекста нейросети и расходовали меньше токенов.
Для кого: для тех, кто много общается с LLM и пересылает длинные тексты, код и документы.
Где скачать: https://github.com/Bizonrelax/SaberW-2 → Releases (Latest): SaberW-2.exe (Windows), jar (любая ОС с Java 21+), портативный zip (Windows, JRE 21 внутри).
Окружение: Windows 10/11 x64 — портативный zip не требует установки (JRE внутри); exe и jar требуют Java 21+. Linux и macOS — Java 21+.
Шрифты: интерфейс — системный гротеск; код и сжатый текст — моноширинный шрифт. Сжатый текст содержит символы CJK Extension A: для их отображения нужен шрифт с этим диапазоном — Windows: SimSun или Microsoft YaHei (ставятся с языковым компонентом для восточноазиатских языков), универсальный бесплатный: Noto Sans CJK SC. Эмодзи: Segoe UI Emoji (Windows), Noto Color Emoji (Linux). Если вместо символов квадраты — установите Noto Sans CJK SC.
Лицензия: The Unlicense. Репозиторий проекта: https://github.com/Bizonrelax/SaberW-2
Проверка: mvn -q compile → exit 0.

ШАГ 2. Загрузчик: в gui пакет добавить класс AboutTexts: static String load(String lang) читает resources/about/about.<lang>.txt через classloader; неизвестный язык → fallback на ru. Тест AboutTextsTest: все 6 языков загружаются, непустые, содержат "SaberW 2" и строку releases; load("xx") возвращает русский текст.
Проверка: mvn -q test -Dtest=AboutTextsTest → зелёный. Чек-поинт: git commit -m "feat: about texts resources for 6 languages with loader" + пуш в ptatimon-1.

ШАГ 3. Окно: класс AboutDialog (модальный JDialog): JTextArea (нередактируемая, перенос строк) в JScrollPane; JComboBox языков (ru, en, zh, fr, es, pt); JSpinner размера шрифта 8–40, применяет размер к шрифту JTextArea немедленно; кнопка «Бу» (подсказка «Копировать в буфер обмена») копирует текущий текст области в системный буфер; кнопка «Закрыть». Выбор языка и размер сохраняются в settings-JSON (поля aboutLanguage, font.size) через DictionaryJsonSerializer; при открытии восстанавливаются. В MainWindow кнопка «Опр» (подсказка «О программе») открывает диалог. Имя владельца не указывается нигде.
Проверка: mvn -q compile → exit 0. Чек-поинт: git commit -m "feat: About dialog with 6 languages, font spinner and copy button" + пуш.

ШАГ 4. Полный прогон: mvn -q test → всё зелёное, skipped 0 (фактическое число укажи); mvn -q package → exit 0; rm -f env.sh; git status --porcelain → пусто.

ШАГ 5. Журнал: в Docs/Техническое Задание/РЕШЕНИЯ.md дописать строку R-013 и в строке R-009 заменить фрагмент «merge в main только вручную владельцем» на «merge в main выполняет кодер после зелёного вердикта по R-013»:
R-013 | 2026-10-19 | Кодер смержил рабочую ветку в main сам (коммит 73acaf5), без ручного merge владельца; владелец схему одобрил | После зелёного вердикта и пуша кодер мерджит рабочую ветку в main через PR API; контроль владельца — кнопка «Разница» построчно и страница коммитов; CI на push в main остаётся воротами качества | решение владельца от 19.10.2026
Чек-поинт: git commit -m "docs: R-013 coder-merge policy; amend R-009" + пуш.

ШАГ 6. Публикация по R-013: создать PR ptatimon-1 → main через GitHub API и смержить его; проверить, что main содержит коммиты шагов 2, 3, 5 (git ls-remote + страница коммитов); рабочую ветку не удалять.

СТОП-ПРАВИЛА: красный после микро-правки → трассировка в отчёт; девиация → стоп и доклад; ~3 вызова до лимита → WIP-коммит + REMAINING.md + пуш.
ЗАПРЕТЫ: не трогать ядро сжатия, findCommentRanges, BotExporter, metadata, templates, pom; не указывать имя владельца ни в каких строках; не удалять ветки.
ОТЧЁТ ≤30 строк, последние две строки строго:
СОСТОЯНИЕ: ЗЕЛЕНО|КРАСНО (tests: пройдено/всего, skipped Z)
ВЕРДИКТ: ПУБЛИКОВАТЬ|НЕ ПУБЛИКОВАТЬ — причина ≤10 слов.
