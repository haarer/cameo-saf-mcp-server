# MCP-Surface für kleine LLMs: Designregeln, Do's & Don'ts

## Ziel

Eine MCP-Surface sollte so gestaltet sein, dass kleine, kostengünstige lokale LLMs sie zuverlässig verstehen, die richtigen Tools auswählen und mit möglichst wenigen Tool-Calls erfolgreich nutzen können.

Der zentrale Gedanke:

> Eine MCP-Surface ist nicht nur eine API für Software, sondern eine Entscheidungsoberfläche für ein LLM.

Optimiert werden sollte daher insbesondere die kognitive Komplexität der Tool-Auswahl und -Nutzung.

## Designregeln

### 1. Tool-Anzahl klein halten

- Nur Tools anbieten, die für die Zielaufgaben tatsächlich benötigt werden.
- Ähnliche Funktionen möglichst zusammenführen oder semantisch klar trennen.
- Häufige Workflows gegebenenfalls als ein höherwertiges Tool anbieten.

### 2. Tool-Namen eindeutig machen

Namen sollten die Absicht direkt ausdrücken.

**Gut:**
- `search_code`
- `read_file`
- `git_diff`
- `git_log`

**Schlecht:**
- `execute`
- `run`
- `perform`
- `operation`

### 3. Tool-Beschreibungen kurz und handlungsorientiert halten

Eine gute Beschreibung beantwortet möglichst direkt:

- Was macht das Tool?
- Wann sollte es verwendet werden?
- Wann sollte es nicht verwendet werden?

Beispiel:

```text
search_code:
Find code matching a text or regex pattern.
Use this when you need to locate code before reading a file.
```

### 4. Parameter reduzieren

- Wenige Parameter bevorzugen.
- Pflichtparameter klar definieren.
- Optionale Parameter sparsam verwenden.
- Semantisch ähnliche Parameter vermeiden.
- Enums und Wertebereiche möglichst klein und eindeutig halten.

### 5. Rückgaben klein und task-spezifisch halten

- Nur Informationen zurückgeben, die für die nächste Entscheidung relevant sind.
- Große, generische JSON-Strukturen vermeiden.
- Unnötige Metadaten nicht standardmäßig zurückgeben.

### 6. Fehler für das Modell aufbereiten

Technische Fehlermeldungen sind für kleine Modelle oft wenig hilfreich.

Statt:

```text
RPC_INVALID_PARAMS: validation failed at ...
```

besser:

```text
The file does not exist.
Check the path and try again.
```

### 7. Häufige Workflows vereinfachen

Wenn ein typischer Task regelmäßig dieselbe Tool-Kette benötigt, sollte geprüft werden, ob daraus ein höherwertiges Tool werden kann.

Beispiel:

```text
search_code -> read_file -> get_context
```

kann gegebenenfalls zu:

```text
find_code_context
```

werden.

### 8. Semantische Überschneidungen vermeiden

Tools sollten möglichst klar voneinander unterscheidbar sein.

Wenn mehrere Tools dieselbe Nutzerabsicht erfüllen können, steigt die Schwierigkeit der Tool-Auswahl.

## Do's

- Eindeutige Tool-Namen verwenden.
- Tool-Beschreibungen kurz halten.
- Entscheidungsregeln explizit machen.
- Wenige Parameter verwenden.
- Häufige Workflows vereinfachen.
- Rückgaben auf relevante Informationen reduzieren.
- Fehler verständlich formulieren.
- Wiederholbare Benchmarks für Änderungen an der Surface verwenden.
- Surface-Versionen reproduzierbar testen.
- Tool-Auswahl und Tool-Ausführung getrennt evaluieren.

## Don'ts

- Nicht für jede interne Funktion ein eigenes Tool anbieten.
- Keine generischen `execute`-/`run`-Tools ohne klare Semantik.
- Keine langen Beschreibungen mit viel irrelevanter Dokumentation.
- Keine unnötigen optionalen Parameter.
- Keine großen Rohdatenmengen zurückgeben.
- Keine technischen Stacktraces als primäre Fehlermeldung.
- Nicht mehrere semantisch nahezu identische Tools anbieten.
- Nicht nur große Modelle als Referenz testen.
- Nicht ausschließlich subjektive LLM-Judge-Bewertungen verwenden.

## Leitprinzip

> Jede zusätzliche Entscheidung, die das Modell treffen muss, ist potenziell eine Fehlerquelle.

Die Surface sollte daher nicht maximal flexibel, sondern für die relevanten Aufgaben möglichst eindeutig sein.
