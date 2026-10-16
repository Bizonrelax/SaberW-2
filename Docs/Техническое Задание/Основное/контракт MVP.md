
## ТЕХНИЧЕСКОЕ ЗАДАНИЕ ДЛЯ РАЗРАБОТКИ: SaberW 2 (MVP)
при противоречии для MVP приоритет у контракт MVP.md + РЕШЕНИЯ.md

Контекст: Требуется реализовать MVP (минимально жизнеспособный продукт) настольного приложения для эффективного двустороннего сжатия текста при общении с нейросетями. Приложение кодирует частые слова и фразы в компактные CJK-символы на основе динамического словаря.

---

## 1. СТЕК И АРХИТЕКТУРА

- Язык: Java 21+ (разрешено использование Virtual Threads).
- Интерфейс: Стриктно Swing (без сторонних UI-фреймворков).
- Сборка: Maven.
- Зависимости: `com.fasterxml.jackson.core:jackson-databind` (для JSON), JUnit 5 (scope test).
- Лицензия: The Unlicense.
- Главный архитектурный запрет: Никаких «классов-богов». Логика сжатия/декомпрессии (Core) должна быть полностью отделена от GUI и не содержать зашитых строк интерфейса.

---

## 2. ЯДРО (CORE): ЭКРАНИРОВАНИЕ И ТОКЕНИЗАЦИЯ

Любой текст перед анализом и сжатием проходит обязательный лево-правый проход экранирования для обеспечения однозначности декомпрессии.

## 2.1. Алгоритм экранирования:

1. Если символ = `§`, заменить на `§§`.
2. Если символ = CJK-иероглиф из диапазона `U+3400`–`U+4DBF` (Extension A), заменить на `§#<символ>`.
3. Остальные символы — без изменений.

## 2.2. Правила жадной токенизации (в порядке приоритета):

- Атомарные единицы экранирования: Последовательности `§§` (длина 2) и `§#<CJK>` (длина 3) считываются как ОДИН неделимый токен-разделитель. Их внутренние части нельзя заменять кодами!
- Слово: Максимальная цепочка символов классов «буква» (исключая CJK из Extension A), «цифра» и знак подчёркивания `_`.
- Эмодзи: Выделяются через `java.text.BreakIterator.getCharacterInstance()`. Один эмодзи = один токен.
- Разделитель: Максимальная цепочка любых других символов.

---

## 3. МАТЕМАТИКА И АЛГОРИТМ СЖАТИЯ (УРОВЕНЬ 1)

_В MVP реализуется только Уровень 1 (плоское сжатие: кодируются только исходные слова и фразы, без вложений кодов в коды)._

## 3.1. Формулы экономии (S):

- Для новых кандидатов (ещё нет в словаре):  
    $$S_{new} = (F - 1) \times L - F \times C - C$$
- Для существующих записей (уже есть в словаре):  
    $$S_{exist} = F \times (L - C)$$

_Где: F — частота повтора, L — длина кандидата в символах, C — длина будущего кода (1 или 3 символа)._

## 3.2. Порядок работы алгоритма (в фоновом потоке):

1. Опция Игнорирования комментариев (`ignoreComments`): Если включена, строки, попадающие под базовую эвристику (`//`, `/* */`, `<!-- -->`, `#`), экранируются, но исключаются из подсчёта частот и не заменяются кодами.
2. Проход по словам: Считаются частоты слов, проходящих по условию L ≥ minWordLength и $S_{new} \ge \text{порог}$.
3. Проход по фразам: На основе ID токенов строится суффиксный массив и LCP-массив. Выделяются фразы длиной ≥ minPhraseLength токенов.
4. Детерминированный тай-брейк (Сортировка кандидатов): По убыванию S → по убыванию L → по убыванию F → лексикографически.
5. Жадная замена: Токены последовательно заменяются кодами. Замена внутри атомарных единиц экранирования или внутри других кодов запрещена.

## 3.3. Генерация кодов:

- Ступень 1: Одиночные символы из диапазона `U+3400`–`U+4DBF` (длина C=1).
- Ступень 2: Если Ступень 1 исчерпана, генерируются префиксные коды вида `§XY`, где X и Y — символы из CJK Extension A (длина C=3).

---

## 4. АЛГОРИТМ ДЕКОМПРЕССИИ

Строгий лево-правый проход по сжатому тексту без повторного сканирования уже выведенного результата.

- `§§` → вывести `§`.
- `§#` + CJK из Extension A → вывести этот CJK.
- `§` + два CJK (`§XY`) → найти код в словаре, рекурсивно раскрыть его поле `value`, вывести результат.
- Изолированный CJK из Extension A → найти код Ступени 1 в словаре, рекурсивно раскрыть, вывести результат.
- Любой другой символ → вывести как есть.

_Ограничение рекурсии:_ Глубина вложенности раскрытия ≤ 1000. При превышении или ошибке парсера — очистить поле вывода и выбросить модальное окно ошибки.

---

## 5. СТРУКТУРА ХРАНЕНИЯ ДАННЫХ (JSON)

Словарь и метаданные хранятся в одном файле `.saberw.json`.

Корневой объект:

```json
{
  "format": "saberw-dictionary",
  "formatVersion": 1,
  "id": "СтрокаИз6СимволовA-Za-z0-9",
  "createdAt": "ISO-8601-Timestamp",
  "dictionary": {
    "updatedAt": "ISO-8601-Timestamp",
    "measureUnit": "characters",
    "entries": [
      {
        "code": "㐀",
        "value": [{"type": "text", "text": "экранированная_строка"}],
        "level": 1,
        "frequency": 12,
        "codeAppliedCount": 12,
        "addedAt": "ISO-8601-Timestamp",
        "sentToBot": true
      }
    ]
  },
  "metadata": {
    "note": "",
    "chatLinks": [],
    "appliedDates": [],
    "totalCompressions": 0,
    "totalDecompressions": 0,
    "supplementCount": 0,
    "mergeCount": 0,
    "exportCount": 0,
    "lastExportIncludedMetadata": false,
    "sizeBytes": 0,
    "entriesCount": 0,
    "totalValueChars": 0
  }
}
```

---

## 6. ТРЕБОВАНИЯ К ИНТЕРФЕЙСУ И ПОТОКАМ

1. Запрет блокировки EDT: Все операции сжатия, поиска, парсинга больших файлов и декомпрессии должны запускаться асинхронно (например, через `SwingWorker`). Основной поток интерфейса (EDT) не должен замерзать ни при каких условиях.
2. Элементы управления:
    
    - Текстовые поля: Исходный текст, Сжатый текст, Просмотр словаря.
    - Кнопки: «Сжать», «Распаковать», «Для бота» (копирует в буфер инструкцию + словарь + сжатый текст), «Очистить».
    - Чекбокс «Первое сообщение» (управляет тем, добавлять ли полную текстовую инструкцию для ИИ из ресурсов при экспорте, или только новые записи с `sentToBot == false`).
    - Кнопка «Остановить поиск» и обработка `Esc`: Должны мгновенно прерывать фоновый поток сжатия на границе текущей итерации и сохранять файл `interrupted-*.saberw.json`.
    - Прогресс-бар: Появляется динамически, если расчет длится более 10 секунд.
    

---

## 7. РЕЖИМЫ СЖАТИЯ (НАСТРОЙКИ ПО УМОЛЧАНИЮ)

- max-cjk: minWordLength = 3, minFrequency = 2, minFrequencyForStep2 = 6, minPhraseLength = 7, нормализация регистра = true.
- code: minWordLength = 4, minFrequency = 2, minFrequencyForStep2 = 6, minPhraseLength = 7, нормализация регистра = false.
- chat: minWordLength = 3, minFrequency = 2, minFrequencyForStep2 = 6, minPhraseLength = 5, нормализация регистра = true.

---

## ИНСТРУКЦИЯ ДЛЯ ГВЕН:

1. Реализуй архитектуру строго по слоям: `core` (логика), `model` (POJO данные), `gui` (Swing компоненты).
2. Напиши юнит-тесты для проверки инварианта round-trip: `decompress(compress(text)) == text`.
3. Убедись, что буквенно-цифровой ИД словаря (6 символов, без спецсимволов) генерируется случайно и проверяется на уникальность в рабочей папке.

---



## ДОПОЛНЕНИЕ №1: Шаблоны блоков инструкции для бота

Программа должна автоматически собирать текст для нейросети из следующих текстовых ресурсов (файлов в `src/main/resources/templates/`), подставляя их в зависимости от условий.

## Блок 1: `bot-block-base.ru.txt` (Базовый, идет всегда)

```text
Ты — декомпрессор текста SaberW 2. Ниже тебе передан словарь замен и сжатый текст. 
Каждая строка словаря содержит уникальный символ-код и через табуляцию (\t) его исходное значение.
Символ "§" является системным префиксом. 
Последовательность "§§" означает один литеральный символ "§".
Последовательность "§#<символ>" означает один литеральный CJK-символ.
Твоя задача: полностью распаковать текст, заменив все коды на их значения из словаря, обработать экранирование и вернуть МНЕ ТОЛЬКО ЧИСТЫЙ РАСПАКОВАННЫЙ ТЕКСТ без твоих комментариев, пояснений и вводных фраз.
```

## Блок 2: `bot-block-normalization.ru.txt` (Добавляется, если включена нормализация)

```text
Внимание: при сжатии была включена нормализация регистра. Весь исходный текст был приведен к нижнему регистру. Восстанавливай текст в нижнем регистре.
```

## Блок 3: `bot-instruction-minimal.ru.txt` (Используется вместо Блоков 1–2 при снятом чекбоксе «Первое сообщение»)

```text
Продолжение сессии SaberW 2. Ниже переданы только НОВЫЕ записи словаря и следующее сжатое сообщение. Обнови свой контекстный словарь новыми кодами и выдай только чистый распакованный текст.
```

---

## ДОПОЛНЕНИЕ №2: Эталонная реализация Токенизатора (Java 21)

Передай Гвен этот код как жесткий ориентир для реализации слоя `core`. Он учитывает эмодзи (графемные кластеры через `BreakIterator`), нижнее подчеркивание в словах и защищает атомарные единицы экранирования (`§§` и `§#`).

```java
package com.saberw.core;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

public class SaberTokenizer {

    public enum TokenType {
        ESCAPE_ATOM, // §§ или §#<CJK>
        WORD,        // Буквы, цифры, подчёркивание
        EMOJI,       // Графемные кластеры (эмодзи, флаги, модификаторы)
        SEPARATOR    // Все остальные символы
    }

    public static record Token(TokenType type, String text) {}

    public static List<Token> tokenize(String shieldedText) {
        List<Token> tokens = new ArrayList<>();
        if (shieldedText == null || shieldedText.isEmpty()) {
            return tokens;
        }

        // Шаг 1: Разрезаем текст на истинные графемные кластеры (обрабатывает сложные эмодзи)
        List<String> clusters = new ArrayList<>();
        BreakIterator boundary = BreakIterator.getCharacterInstance();
        boundary.setText(shieldedText);
        int start = boundary.first();
        for (int end = boundary.next(); end != BreakIterator.DONE; start = end, end = boundary.next()) {
            clusters.add(shieldedText.substring(start, end));
        }

        // Шаг 2: Жадный лево-правый сборщик токенов с приоритетами
        int i = 0;
        int n = clusters.size();

        while (i < n) {
            String current = clusters.get(i);

            // 1. Проверка атомарных единиц экранирования
            if (current.equals("§")) {
                if (i + 1 < n && clusters.get(i + 1).equals("§")) {
                    tokens.add(new Token(TokenType.ESCAPE_ATOM, "§§"));
                    i += 2;
                    continue;
                }
                if (i + 1 < n && clusters.get(i + 1).equals("#") && i + 2 < n) {
                    String cjkCandidate = clusters.get(i + 2);
                    if (isCjkExtensionA(cjkCandidate.codePointAt(0))) {
                        tokens.add(new Token(TokenType.ESCAPE_ATOM, "§#" + cjkCandidate));
                        i += 3;
                        continue;
                    }
                }
            }

            // 2. Проверка класса "Слово" (Буквы, цифры, '_', исключая CJK Ext A)
            int firstCodePoint = current.codePointAt(0);
            if (isWordPart(firstCodePoint) && !isCjkExtensionA(firstCodePoint)) {
                StringBuilder sb = new StringBuilder();
                while (i < n) {
                    String wordChar = clusters.get(i);
                    int cp = wordChar.codePointAt(0);
                    // Прерываем слово, если наткнулись на префикс экранирования
                    if (wordChar.equals("§")) break; 
                    if (isWordPart(cp) && !isCjkExtensionA(cp)) {
                        sb.append(wordChar);
                        i++;
                    } else {
                        break;
                    }
                }
                tokens.add(new Token(TokenType.WORD, sb.toString()));
                continue;
            }

            // 3. Проверка класса "Эмодзи" (Если это не слово, не разделитель и состоит из суррогатов/Emoji свойств)
            if (isEmojiCluster(current)) {
                tokens.add(new Token(TokenType.EMOJI, current));
                i++;
                continue;
            }

            // 4. Класс "Разделитель" (Собираем максимальную последовательность)
            StringBuilder sepSb = new StringBuilder();
            while (i < n) {
                String sepChar = clusters.get(i);
                int cp = sepChar.codePointAt(0);
                
                // Триггеры прерывания разделителя: начало экранирования, слово или эмодзи
                if (sepChar.equals("§")) break;
                if ((isWordPart(cp) && !isCjkExtensionA(cp)) || isEmojiCluster(sepChar)) break;

                sepSb.append(sepChar);
                i++;
            }
            tokens.add(new Token(TokenType.SEPARATOR, sepSb.toString()));
        }

        return tokens;
    }

    private static boolean isCjkExtensionA(int codePoint) {
        return codePoint >= 0x3400 && codePoint <= 0x4DBF;
    }

    private static boolean isWordPart(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static boolean isEmojiCluster(String cluster) {
        // Базовая детекция: спецсимволы Unicode для эмодзи или суррогатные пары, не являющиеся знаками пунктуации
        int cp = cluster.codePointAt(0);
        return (cp >= 0x1F000 && cp <= 0x1FAFF) || cluster.length() > 1 && Character.isSurrogate(cluster.charAt(0));
    }
}
```

---
