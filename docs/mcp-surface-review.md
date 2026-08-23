# MCP-Surface Review aus LLM-Sicht

Stand: 2026-08-23. Verfasst nach einer intensiven Session: Constraints-Inventarisierung
(1202 Stück, Sprach-Tags klassifiziert), OpaqueBehavior-Audit, Jython→Groovy-Konvertierung
zweier SAF-Validierungsregeln (WUCASSCTX, WCTXAGGEL), Modell-Lade/Save-Werkzeuge,
und direkter Regel-Ausführung gegen SAF_FFDS.

Perspektive: Wie fühlt sich diese Tool-Oberfläche für ein LLM an, das sie ohne
Mensch-im-Loop bedienen soll? Was kostet unnötige Roundtrips?

---

## Was gut funktioniert

1. **Hot-Reload der Groovy-Scripts (~2s).** Der größte Enabler der ganzen Session.
   Neues Tool geschrieben → deployed → sofort nutzbar, ohne Cameo-Restart.
   Ermöglicht den "Tool bauen, wenn etwas fehlt"-Workflow.

2. **Lese-Surface ist stark.** `find_elements_by_type` mit Name/Stereotype/Type-Filtern
   und `scope=all|primary`, plus `get_elements_details_batch` — damit ließ sich das
   komplette Constraint-Inventar (inkl. Spezifikations-Sprachtags) in 7 Aufrufen ziehen.
   Das Format `<Sprache>\n<Body>` für Specifications ist einfach zu parsen.

3. **`writableCheck`-Guard mit sprechenden Fehlern.** "Element belongs to used project
   'SAF_Library' which is read-only" führte sofort zur richtigen Strategie
   (Profil standalone laden). Genau so sollen Guard-Fehler aussehen.

4. **Groovy-Runtime-Fehler listen Signaturen.** "Possible solutions: closeProject(),
   closeProject(Project), loadProject(descriptor, boolean)..." — jedes API-Mismatch
   war in einem Roundtrip diagnostiziert.

5. **Admin-Brücke als Workflow-Enabler.** Ohne `admin_load_model` / `admin_reset_model`
   / `admin_save_model` wäre das Editieren des read-only-Moduls gar nicht möglich
   gewesen.

6. **Ausführliche Tool-Beschreibungen** inkl. SAF-Konventionen (z.B. volle
   Stereotype-Namen statt Concept-Kinds).

---

## Wo es hakt: die Trial-and-Error-Bilanz

Rückblickend entfallen ~12 fehlgeschlagene Tool-Calls auf vier Ursachen:

| Ursache | Fälle | Beispiel |
|---|---|---|
| Wissens-/Versionsabstand der LLM-Priors¹ | 5 | `closeProject(p, false)` existiert nicht; `loadProject(desc)` braucht boolean; `saveProject(project)` braucht `(descriptor, boolean)`; Descriptor-Erzeugung will URI-String, keinen Pfad |
| Fehlende generische Introspektion | 3 | Konstruktor-Raten bei `ValidationRunData` (17 Signaturen!); erst ad-hoc-Introspektion im Fehlerpfad brachte Klarheit |
| EMF-Metamodell-Details² | 2 | `OpaqueExpression.setLanguage(List)` existiert nicht (multi-valued Feature ohne Setter) → `eSet(UMLPackage.Literals...)` nötig |
| Validierungs-Engine-Interna | 2+ | `DefaultValidationRuleImpl.run()` → NPE (`filter` null); `ValidationHelper.validateElement` liefert still leer für Script-Regeln → Fallback `rule_eval` per GroovyShell |

¹ Bewusst nicht "API-Drift" genannt: Das Cameo-API selbst hat sich während der Session
nicht geändert. Der Abstand besteht zwischen dem Trainingswissen des LLM (Ältere
Releases, Tutorials) und der installierten 2026x-Instanz. Davon zu trennen sind
halluzinierte Bequemlichkeits-Signaturen (z.B. der spekulative Fallback
`createLocalProjectDescriptor(File)`), die es nie gab.
² Kein Versionsproblem: multi-valued EMF-Features haben nie generierte Setter —
falsche Verallgemeinerung des LLM, keine Drift.

Dazu strukturelle Reibungspunkte:

7. **Neue Tools sind in laufenden Sessions unsichtbar — Serverschwäche, keine
   Harness-Eigenschaft.** Der MCP-Standard sieht `notifications/tools/list_changed`
   vor; opencode verarbeitet sie bereits korrekt (Handler auf
   `ToolListChangedNotificationSchema`, Re-Listing + Bus-Event, PR #5913). Unser Server
   sendet sie aber nie: kein `list_changed` im Code, POST-only-Transport ohne
   Downstream-Kanal, Capability `tools.listChanged` wird nicht deklariert. Ich musste
   einen eigenen HTTP-Helper (`curl` auf `/mcp`) bauen, um `admin_*` überhaupt rufen
   zu können.
   *Status (v0.2.2): BEHOBEN und verifiziert.* `initialize` deklariert
   `"tools": {"listChanged": true}`; `StreamableMcpTransportProvider` bietet einen
   SSE-Antwortkanal (`GET /mcp` pro Session, Latch-basierte Lebensdauer,
   15-s-Keepalive); nach Hot-Reload mit Tool-Mengenänderung geht die Notification an
   alle offenen Streams. Dabei zwei Zusatzbugs gefunden: (a) GET-Request-Bodies sind
   sofort am EOF — Disconnect-Erkennung darf nicht über Body-Read laufen;
   (b) `GroovyScriptScanner.hasChanges()` erkannte **Löschungen** nie — gelöschte
   Tools blieben bis zum Restart verfügbar. E2E-verifiziert (Add/Delete → je eine
   Notification, `tools/list` korrekt, Suite 100/100).

8. **Pfad-Chaos Container vs. Host.** Erster `admin_load_model` schlug fehl, weil ich
   den Container-Pfad `/workspace/...` übergab; der Server lebt auf dem Host
   (`/home/mac/opencode/workspace/...`). Die Fehlermeldung ("File not found") half nicht
   beim Umdenken.

9. **Kein serverseitiges Filtern/Paginieren für Massenlese.** Der 1202-Constraints-Sweep
   erforderte manuelles Chunking (200er Batches) wegen Truncation ab ~50 KB. Ein
   `specLanguage`-Filter oder offset/limit hätte den gesamten Sweep auf einen Bruchteil
   reduziert — die Klassifikation (Binary/Groovy/Jython/English/leer) läuft schließlich
   serverseitig in einem Einzeiler.

10. **Tag-Lesen war nur als "Debug"-Tool vorhanden.** Angewandte Stereotype-Properties
    (abbreviation, errorMessage!) konnte kein reguläres Tool lesen — `saf_get_element_details`
    zeigt nur SAF-Tags. Erst `debug_stereotype_tags` (im Zuge der Engine-Fehlersuche
    entstanden) machte z.B. den "Logical vs. Conceptual"-Fehler in WUCASSCTX sichtbar.

11. **Inkonsistente Ergebnisschalen.** `find_elements` liefert keine IDs (nur
    qualifiedNames), `find_elements_by_type` schon. `get_element_info` per qualifiziertem
    Namen schlug für `Model::0-Model Management` fehl. Man wechselt mid-flow das Werkzeug.

12. **Schließen eines dirty Projekts blockiert auf Modal-Dialog.** `admin_reset_model`
    hing >120 s, bis der Mensch am Host "nicht speichern" klickte. Aus LLM-Sicht:
    Call hängt ohne Diagnosemöglichkeit. Dirty-State vorab prüfen/anzeigen fehlt
    (`project.isModified()`), ebenso ein explizites `discard=true`.

13. **Stereotype anwenden ist ein Nebeneffekt von `set_tagged_values`** (leere `values:{}`).
    Funktioniert, aber nichts dokumentiert das; ein explizites `apply_stereotype` wäre
    selbsterklärend.

14. **Aktives Projekt als impliziter Kontext.** Geladene, aber nicht aktivierte Projekte
    sind für alle Finder unsichtbar; es gibt keinen `projectId`-Parameter. Funktioniert,
    aber der Wechsel-Kontext (was ist gerade aktiv? welche Module hängen dran?) muss
    mental nachgehalten werden — `admin_get_model_status` nach jedem Wechsel ist Pflicht.

---

## Wissen priorisieren: Laufzeit & Javadoc vor LLM-Priors

Da die Fehlerursache kein System-, sondern ein Wissensproblem ist, lautet das Ziel:
verifizierte Quellen systematisch über das Vorwissen des LLM stellen. Rangfolge der
Wahrheitsquellen:

1. **Laufzeit-JVM** (`getDeclaredConstructors`/`getDeclaredMethods` am lebenden Objekt) —
   was *ist*.
2. **Indexierter Javadoc** (`md-javadoc-2026` über den `cameo-api`-Server) — was laut
   installierter Version *sein sollte*.
3. **Session-verifiziertes Cheat-Sheet** — was in dieser Umgebung bereits funktioniert hat.
4. **LLM-Prior** — nur als Hypothese; nie als Grundlage für festen Code.

### Maßnahmen

- **M1 — "Javadoc-first"-Regel in AGENTS.md.** Bevor Code gegen eine
  `com.nomagic.*`-Klasse geschrieben wird, die in dieser Session noch nicht verifiziert
  wurde: erst `cameo-api_lookup_symbol`/`search_docs`. Fehlt die Klasse/Methode im Index,
  gilt sie als nicht vorhanden — nicht "vielleicht trotzdem probieren".
- **M2 — FQN-Lint vor dem Deploy.** Skript, das aus zu deployenden Groovy-Dateien alle
  `com.nomagic.*`/`groovy.*`-Referenzen extrahiert und jede gegen den Javadoc-Index und
  (bei Unsicherheit) gegen die Laufzeit prüft. Macht Vorwissen zu einem Build-Fehler
  statt zu einem Runtime-Ratespiel.
- **M3 — Generisches `api_introspect(className)`** als reguläres MCP-Tool (heute nur im
  Fehlerpfad von `validation_run_rules` versteckt). Höchste Priorität, weil es auch dann
  funktioniert, wenn der Javadoc-Index lückenhaft ist.
- **M4 — Javadoc-Index um Member-Signaturen erweitern.** Der aktuelle Index liefert oft
  nur Klassensummaries ohne Parameterlisten (genau daran scheiterte `ValidationRunData`;
  die Konstruktorliste kam letztlich aus der JVM). Vollständige Methoden-/Konstruktorsignaturen
  im Index machen Stufe 2 unabhängig von Stufe 1 brauchbar.
- **M5 — Cheat-Sheet `docs/cameo-2026x-api-notes.md`.** Session-verifizierte Signaturen
  und Fallstricke (EMF-eSet-Muster, Descriptor-via-URI, saveProject(descriptor, true)...)
  werden dort festgehalten; AGENTS.md weist Agenten an, es zu lesen. Wächst mit jeder
  Session und verschiebt häufige Fälle von Stufe 1/2 nach Stufe 3 — schnellster Pfad.

Wirksamkeit: Allein M1+M3 hätten in dieser Session jeden der fünf Priors-Fälle auf einen
Roundtrip reduziert statt auf durchschnittlich zwei bis drei (Fehlschlag → Fehlermeldung
lesen → Fix → Deploy → Test).

---

## Priorisierte Optimierungen

### P1 — hoher Nutzen, kleiner Aufwand

- **Generisches `api_introspect(className)`**: Konstruktoren + Methoden einer Klasse
  aus dem Laufzeit-JVM zurückgeben. Hätte alle fünf Priors-Fälle (¹ oben) auf je einen
  Roundtrip reduziert. (Existiert heute nur versteckt im Fehlerpfad von
  `validation_run_rules`; siehe Maßnahme M3.)
- **`get_stereotype_tags` als erstklassiges Tool** (heißt heute debug_stereotype_tags)
  + **`apply_stereotype` / `remove_stereotype`**.
- **Serverseitiger Spec-Filter**: `find_elements_by_type(..., specLanguage="Groovy")`
  bzw. ein `query_constraints(language=, stereotype=, hasCode=true)`.
- **`find_elements`: id und project mitliefern** — eine Schale für alle Find-Varianten.
- **Pfad-Hinweis bei File-not-found** in Admin-Tools: "note: paths are resolved on the
  host running Cameo".

### P2 — mittlerer Aufwand

- **Dirty-State-Handling**: `admin_get_model_status` um `modified: true/false`
  erweitern; `admin_close_model`/`admin_reset_model` mit `discard=true` Parameter
  (`project.setModified(false)` vor dem Close), damit nie wieder ein Modal blockiert.
- ~~**`notifications/tools/list_changed` serverseitig senden**~~ — erledigt in v0.2.2
  (Capability + SSE-Downstream-Kanal + Broadcast, siehe Punkt 7).
- **`rule_eval` zum offiziellen Validierungswerkzeug ausbauen**: Jython/Rhino zusätzlich
  zu Groovy, Targets automatisch über `constrainedElementsFilter`-Semantik sammeln,
  Ergebnisse als Violation-Objekte (severity aus Tag, errorMessage aus Tag). Dann wäre
  die tote Engine-Route irrelevant.
- **Batch-Lesen mit offset/limit** statt Truncation-by-size.

### P3 — Forschung/Ausrichtung

- **Echte Engine-Integration**: herausfinden, wie MD intern Script-Regeln verdrahtet
  (RuleSelector/Suites), oder akzeptieren, dass `rule_eval` der Weg ist und die UI
  weiter die Referenz bleibt.
- **`projectId`-Parameter** durch die Read-Tools ziehen, um mehrere geladene Modelle
  parallel abfragbar zu machen (aktuell: nur aktiv).
- **Einheitliche Fehlerform** (`{error: ...}` vs. Listen mit eingebetteten Fehlern).

---

## Fazit

Die Surface ist für explorative Arbeit bereits ungewöhnlich gut: heißes Deployen,
starke Lese-Werkzeuge, hilfreiche Guards. Die Kosten entstehen an drei Stellen —
**(a)** die LLM-Priors über die Cameo-API sind veraltet und teils halluziniert; sie
müssen durch Laufzeit-/Javadoc-Abfragen ersetzt werden (Introspektions-Tool + Regeln
nach Abschnitt "Wissen priorisieren" wären die Generallösung), **(b)** Schreib-/Verwaltungsoperationen
wachsen reaktiv statt geplant (jede Lücke = eigener Deploy-Zyklus), **(c)** globale
Zustände (aktives Projekt, dirty flags, Modals) sind aus der Ferne unsichtbar. Mit P1
allein wären rund zwei Drittel der Fehlversuche dieser Session vermieden worden.
