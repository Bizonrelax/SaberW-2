# Промт: ignoreComments (ТЗ 3.2.1, 12.2, этап 24.1.15)

ЦЕЛЬ: опция ignoreComments реализована полностью: флаг в CompressionSettings (default false), гейт маскирования в Compressor, расширенная эвристика комментариев, чекбокс в GUI, персист в settings-JSON, все тесты зелёные.
ЗАЧЕМ: сейчас маскирование комментариев выполняется безусловно, эвристика знает только два маркера; ТЗ требует опцию и четыре маркера с защитами. Три теста CompressorMaskedTest, помеченные @Disabled, — приёмочный критерий: включить и озеленить.

ОКРУЖЕНИЕ: [ -f /workspace/env.sh ] || printf 'export JAVA_HOME=/tmp/jdk-21.0.12.1+1\nexport PATH=$JAVA_HOME/bin:/tmp/apache-maven-3.9.9/bin:$PATH\n' > /workspace/env.sh; source /workspace/env.sh

ШАГ 0. git status --porcelain: незакоммиченные изменения прошлых сессий НЕ откатывать; если есть включённые тесты из CompressorMaskedTest без реализации — это test-first, так и должно быть.

ШАГ 1. CompressionSettings: добавить шестой компонент boolean ignoreComments; вторичный конструктор из 5 аргументов делегирует с false; метод withIgnoreComments(boolean); пресеты maxCjk/code/chat передают false.
Проверка: mvn -q compile → exit 0.

ШАГ 2. Compressor.compress: гейт маскирования:
    List<int[]> commentRanges = settings.ignoreComments() ? findCommentRanges(tokens) : List.of();
Проверка: mvn -q compile → exit 0.

ШАГ 3. Тест maskingCanBeTurnedOffPerSpec: снять @Disabled и дать тело:
    String text = "alpha alpha // beta beta beta";
    Compressor.Result off = new Compressor(SETTINGS.withIgnoreComments(false)).compress(text);
    assertTrue(hasValue(off.dictionary(), "beta"), "with ignoreComments=false comment words must be counted");
    Compressor.Result on = new Compressor(SETTINGS.withIgnoreComments(true)).compress(text);
    assertFalse(hasValue(on.dictionary(), "beta"));
Проверка: mvn -q test -Dtest=CompressorMaskedTest → этот тест зелёный.
Чек-поинт: git commit -m "feat(ignoreComments): flag in settings and masking gate"

ШАГ 4. findCommentRanges: четыре маркера с защитами (ТЗ 12.2). Заменить метод целиком на следующий код (литерали маркеров в коде допустимы):

    private static final String[] PREPROCESSOR_KEYWORDS = {
            "include", "define", "undef", "if", "ifdef", "ifndef",
            "elif", "else", "endif", "line", "error", "pragma"};

    private static List<int[]> findCommentRanges(List<SaberTokenizer.Token> tokens) {
        List<int[]> ranges = new ArrayList<>();
        int n = tokens.size();
        int i = 0;
        while (i < n) {
            String text = tokens.get(i).text();
            int html = text.indexOf("<!--");
            int block = text.indexOf("/*");
            int line = indexOfLineMarker(text);
            int hash = indexOfHashMarker(text, tokens, i);
            int best = -1;
            char kind = 0;
            if (html >= 0) { best = html; kind = 'h'; }
            if (block >= 0 && (best < 0 || block < best)) { best = block; kind = 'b'; }
            if (line >= 0 && (best < 0 || line < best)) { best = line; kind = 'l'; }
            if (hash >= 0 && (best < 0 || hash < best)) { best = hash; kind = 'x'; }
            if (kind == 0) { i++; continue; }
            int start = i;
            if (kind == 'l' || kind == 'x') {
                while (i < n && !containsNewline(tokens.get(i).text())) i++;
                if (i < n) i++;
            } else if (kind == 'b') {
                while (i < n && !endsWith(tokens.get(i).text(), "*/")) i++;
                i = Math.min(n, i + 1);
            } else {
                while (i < n && !tokens.get(i).text().contains("-->")) i++;
                i = Math.min(n, i + 1);
            }
            ranges.add(new int[]{start, i});
        }
        return ranges;
    }

    /** Две косые черты: НЕ открывают комментарий, если непосредственно перед ними двоеточие (защита URL, ТЗ 12.2). */
    private static int indexOfLineMarker(String atom) {
        int from = 0;
        while (true) {
            int idx = atom.indexOf("//", from);
            if (idx < 0) return -1;
            if (idx == 0 || atom.charAt(idx - 1) != ':') return idx;
            from = idx + 1;
        }
    }

    /** Решётка: НЕ открывает комментарий, если стоит первым непробельным символом строки и за ней идёт ключевое слово C-препроцессора (директива, ТЗ 12.2). */
    private static int indexOfHashMarker(String atom, List<SaberTokenizer.Token> tokens, int i) {
        int idx = atom.indexOf('#');
        if (idx < 0) return -1;
        int nl = atom.lastIndexOf('\n', idx);
        boolean atLineStart;
        if (nl >= 0) {
            atLineStart = atom.substring(nl + 1, idx).isBlank();
        } else {
            atLineStart = atom.substring(0, idx).isBlank()
                    && (i == 0 || containsNewline(tokens.get(i - 1).text()));
        }
        if (atLineStart) {
            String rest = atom.substring(idx + 1).trim();
            for (String kw : PREPROCESSOR_KEYWORDS) {
                if (rest.startsWith(kw)) return -1;
            }
        }
        return idx;
    }

Проверка: mvn -q test -Dtest=CompressorMaskedTest → снять @Disabled с urlIsNotCommentPerSpec и htmlCommentsMaskedPerSpec, оба зелёные.
Чек-поинт: git commit -m "feat(ignoreComments): comment heuristics per TZ 12.2 with URL and preprocessor guards"

ШАГ 5. MainWindow: JCheckBox ignoreCommentsCheck = new JCheckBox("Игнорировать комментарии", false) в панель options; parseMode() возвращает base.withIgnoreComments(ignoreCommentsCheck.isSelected()).
DictionaryJsonSerializer: в toJson в settingsNode добавить put("ignoreComments", root.settings().ignoreComments()); в fromJson читать sn.path("ignoreComments").asBoolean(false).
Проверка: mvn -q compile → exit 0.

ШАГ 6. mvn -q test → Tests run: 32, Failures: 0, Errors: 0, Skipped: 0. mvn -q package → exit 0.

ШАГ 7. Гигиена: rm -f env.sh trace_now.txt full_test.log junit_*.log; git status --porcelain → только файлы задачи.
Финальный коммит: feat: ignoreComments option with checkbox, persistence and extended comment heuristics

СТОП-ПРАВИЛА: красный после микро-правки → трассировка (вывод в отчёт), архитектуру не менять; нужна девиация от сниппетов → стоп и доклад; осталось ~3 вызова → протокол конца.
ЗАПРЕТЫ: не откатывать переносы прошлых сессий; не трогать фразовую exact-span семантику, GUI сверх указанного, pom, Docs; не публиковать самому.
ПРОТОКОЛ КОНЦА: при исчерпании квоты — коммит WIP: ignoreComments (in progress) + файл REMAINING.md со списком долгов в коммите.
ОТЧЁТ ≤30 строк: шаги/статус/хвосты вывода/хэши/долги, и последние две строки строго:
СОСТОЯНИЕ: ЗЕЛЕНО|КРАСНО (tests: пройдено/всего, skipped Z)
ВЕРДИКТ: ПУБЛИКОВАТЬ|НЕ ПУБЛИКОВАТЬ — причина ≤10 слов.
