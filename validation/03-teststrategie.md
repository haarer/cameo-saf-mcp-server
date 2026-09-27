# MCP-Surface für kleine LLMs: Teststrategie

## Ziel

Änderungen an der MCP-Surface sollen reproduzierbar messbar sein.

Nicht das subjektive Gefühl:

> „Das Modell scheint die Tools besser zu verstehen.“

sondern:

> „Auf demselben Task-Datensatz löst die neue Surface mehr Aufgaben mit weniger Fehlern und geringerem Ressourcenverbrauch.“

## 1. Reproduzierbaren Task-Datensatz erstellen

Erstelle zunächst einen festen Satz realer Benutzeraufgaben, beispielsweise 100–500 Tasks.

Beispiele für eine Git-MCP-Surface:

```text
T001:
Finde den Commit, der Funktion X eingeführt hat.

T002:
Zeige die Änderungen zwischen v1.2 und v1.3.

T003:
Welche Dateien wurden im letzten Commit geändert?

T004:
Finde alle Stellen, an denen foo() verwendet wird.

T005:
Erstelle einen Branch aus Commit ABC.

T006:
Warum schlägt Test Y momentan fehl?
```

Tasks sollten echte Nutzerintentionen repräsentieren und nicht nur einzelne Tool-Aufrufe testen.

## 2. Surface-Versionen vergleichen

Jede Änderung bekommt eine reproduzierbare Version:

```text
surface-v1
surface-v2
surface-v3
```

Derselbe Task-Datensatz wird gegen jede Version ausgeführt.

Beispiel:

```text
                    v1       v2       v3
Success             71 %     79 %     86 %
Tool Errors         12 %      8 %      4 %
Calls / Task         5.4      4.1      3.0
Tokens / Task      4200     3400     2700
```

## 3. Kontrollvariablen konstant halten

Für einen fairen A/B-Test möglichst identisch halten:

- Modellversion
- Quantisierung
- System Prompt
- Temperatur / Sampling-Parameter
- Hardware
- Task-Datensatz
- verfügbare Repository-/Testdaten
- Tool-Ausführungsumgebung

Nur die untersuchte Surface sollte sich ändern.

## 4. Zwei Ebenen getrennt testen

### Benchmark A: Tool Selection

Das Modell erhält mehrere Tools und muss das richtige auswählen.

Beispiel:

```text
User:
Finde alle Stellen, an denen foo() verwendet wird.

Tools:
1. read_file
2. search_code
3. git_log
4. git_diff
```

Metrik:

```text
wurde search_code gewählt?
```

### Benchmark B: Tool Execution

Das korrekte Tool wird vorgegeben.

```text
Tool: search_code

User:
Finde alle Stellen, an denen foo() verwendet wird.
```

Wenn Benchmark B gut, A aber schlecht ist, liegt das Problem eher in Tool Discovery bzw. Surface Design als in der eigentlichen Tool-Ausführung.

## 5. Description Ablation Tests

Für ein einzelnes Tool verschiedene Beschreibungen testen:

```text
A: vollständige Beschreibung
B: kurze Beschreibung
C: Beschreibung + Beispiel
D: Beschreibung + Entscheidungsregel
```

Dann messen:

- Task Success
- Tool Selection
- Argument Errors
- Tokens
- Tool Calls

So lässt sich feststellen, welche Informationen kleine Modelle tatsächlich benötigen.

## 6. Mehrere Modellgrößen testen

Beispielsweise:

```text
32B
14B
8B
4B
2B
```

Ergebnis als Scaling Curve darstellen:

```text
Model size -> Task Success
```

Besonders interessant ist die untere Grenze, bei der die Surface noch zuverlässig funktioniert.

## 7. Fehlerklassifikation

Jeder fehlgeschlagene Task sollte möglichst einer Fehlerklasse zugeordnet werden:

```text
TOOL_SELECTION_ERROR
ARGUMENT_ERROR
MISSING_CONTEXT
TOO_MANY_TOOL_CALLS
HALLUCINATED_TOOL
HALLUCINATED_ARGUMENT
WRONG_INTERPRETATION
TOOL_EXECUTION_ERROR
```

Damit wird aus einem bloßen Benchmark ein Diagnosewerkzeug.

## 8. Deterministische Evaluation bevorzugen

Wo möglich, sollte der Erfolg objektiv geprüft werden.

Beispiele:

- erwartete Datei gefunden?
- erwarteter Commit identifiziert?
- erwartete Zeile enthalten?
- erwartete Änderung erzeugt?
- Branch korrekt erstellt?
- JSON-Schema korrekt erfüllt?

LLM-as-a-Judge kann ergänzend eingesetzt werden, sollte aber nicht die einzige Qualitätsmetrik sein.

## 9. Ressourcen messen

Pro Task erfassen:

```text
input_tokens
output_tokens
total_tokens
tool_calls
latency
errors
success
```

Daraus lassen sich Effizienzmetriken berechnen.

## 10. Regression Tests

Jede Verbesserung sollte gegen einen festen Regressionstest laufen.

Eine Änderung gilt nicht automatisch als Verbesserung, wenn beispielsweise:

```text
Success:       +5 %
Tokens:       +80 %
```

oder:

```text
4B model:      +10 %
14B model:      -5 %
```

Die Auswirkungen sollten sichtbar bleiben.

## 11. Empfehlenswertes Benchmark-Format

Ein einzelner Datensatz-Eintrag könnte beispielsweise enthalten:

```json
{
  "id": "T004",
  "task": "Finde alle Stellen, an denen foo() verwendet wird.",
  "expected": {
    "files": [
      "src/a.ts",
      "src/b.ts"
    ]
  },
  "metrics": [
    "tool_selection",
    "argument_accuracy",
    "tool_calls",
    "tokens",
    "latency",
    "success"
  ]
}
```

## 12. Wichtigste Auswertung

Für jede Surface-Version sollten mindestens diese Werte vorhanden sein:

```text
Task Success Rate
Tool Selection Accuracy
Argument Accuracy
Invalid Tool Call Rate
Tool Calls / Task
Tokens / Successful Task
Latency / Successful Task
```

Zusätzlich sollte die Entwicklung über verschiedene Modellgrößen betrachtet werden.

## Kernhypothese

Die zentrale Hypothese für die Optimierung lautet:

> Eine klarere MCP-Surface kann die benötigte Modellgröße, die Anzahl der Tool-Calls und den Token-Verbrauch reduzieren, ohne die Erfolgsrate bei realen Aufgaben zu verschlechtern.

Diese Hypothese sollte durch reproduzierbare A/B-Tests überprüft werden.
