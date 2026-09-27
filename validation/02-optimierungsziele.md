# MCP-Surface für kleine LLMs: Optimierungsziele

## Primäres Ziel

Die MCP-Surface soll die kleinsten praktikablen lokalen LLMs befähigen, reale Aufgaben zuverlässig zu lösen.

Die wichtigste Kennzahl ist daher:

> **Task Success Rate**

Weitere Kennzahlen erklären, warum eine Surface gut oder schlecht funktioniert.

## Priorisierte Ziele

### 1. Task Success Rate

```text
successful tasks
----------------
all tasks
```

Beispiel:

```text
Surface A: 71 %
Surface B: 86 %
```

Dies sollte die zentrale Qualitätsmetrik sein.

### 2. Tool Selection Accuracy

Misst, ob das Modell das für die Aufgabe geeignete Tool auswählt.

```text
correct tool selections
-----------------------
all tool decisions
```

Besonders wichtig, wenn mehrere Tools ähnliche Funktionen anbieten.

### 3. Argument Accuracy

Bewertet, ob die Tool-Parameter korrekt gesetzt werden.

Mögliche Fehlerklassen:

- fehlender Parameter
- falscher Parameter
- falscher Datentyp
- falscher Pfad
- falscher Enum-Wert
- halluzinierter Parameter
- falscher Wert

### 4. Invalid Tool Calls

Separat erfassen:

- unbekanntes Tool
- ungültige Argumente
- fehlende Pflichtparameter
- nicht erlaubte Werte
- syntaktisch fehlerhafte Tool-Calls

### 5. Tool Calls pro Task

```text
number of tool calls
--------------------
tasks
```

Weniger Calls sind kein Selbstzweck. Eine Reduktion ist besonders wertvoll, wenn die Erfolgsrate mindestens gleich bleibt oder steigt.

### 6. Token-Effizienz

Messen:

- Input-Tokens pro Task
- Output-Tokens pro Task
- Gesamt-Tokens pro Task
- Tokens pro erfolgreichem Task

Besonders relevant für lokale Modelle und begrenzte Hardware.

Eine nützliche Kennzahl:

```text
total tokens
------------
successful tasks
```

### 7. Latenz

Messen:

- End-to-End-Latenz
- Modell-Latenz
- Tool-Latenz
- Anzahl der Round-Trips

Für lokale Nutzung sollte die Modell- und Tool-Latenz getrennt betrachtet werden.

### 8. Kleinste praktikable Modellgröße

Eine wichtige Zielgröße ist die Robustheit über verschiedene Modellgrößen.

Beispiel:

```text
32B  -> 94 %
14B  -> 92 %
8B   -> 88 %
4B   -> 78 %
2B   -> 49 %
```

Eine optimierte Surface sollte insbesondere die Erfolgsrate kleiner Modelle verbessern.

## Zielbild

Eine erfolgreiche Optimierung könnte beispielsweise so aussehen:

```text
                    vorher    nachher
Task Success          71 %      86 %
Tool Errors           12 %       4 %
Calls / Task           5.4       3.0
Tokens / Task        4200      2700
```

Dabei sollte die Verbesserung über reproduzierbare Tests nachgewiesen werden.

## Cognitive Surface Complexity

Als Design-Heuristik kann die Komplexität einer Surface grob betrachtet werden als Kombination aus:

```text
Tool-Anzahl
+ Parameter-Anzahl
+ optionale Parameter
+ semantische Überschneidungen
+ notwendige Tool-Calls
+ benötigter Kontext
```

Dies ist keine universelle standardisierte Metrik, sondern eine praktische Denkweise zur Analyse von Surface-Änderungen.

## Was nicht das primäre Ziel sein sollte

Nicht isoliert optimieren auf:

- minimale Tool-Anzahl
- minimale Tool-Calls
- minimale Tokens
- maximale Flexibilität
- LLM-Judge-Score

Diese Größen müssen immer zusammen mit der tatsächlichen Task-Erfolgsrate betrachtet werden.

## Besonders wertvolle Zielgröße

Die zentrale Forschungsfrage kann lauten:

> Wie weit lässt sich die benötigte Modellgröße durch eine besser gestaltete MCP-Surface reduzieren, ohne die Zuverlässigkeit bei realen Aufgaben zu verlieren?
