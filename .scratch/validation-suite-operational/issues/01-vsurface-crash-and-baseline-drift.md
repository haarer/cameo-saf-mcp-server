# vsurface crashes on bare invocation; baseline has drifted from the surface

Status: needs-triage

Two independent defects in the merged validation suite. Neither blocks the
description-trimming pass, but the suite cannot currently verify it.

## 1. `vsurface` crashes with no arguments

```
$ ./validation/bin/vsurface
Traceback (most recent call last):
  File "<stdin>", line 119, in <module>
AttributeError: 'NoneType' object has no attribute 'strip'
```

`validation/bin/vsurface` is a bash script that pipes a Python program to
`python3 -`. The usage path ends at line 133:

```python
print(__doc__.strip(), file=sys.stderr)
```

When Python executes a program supplied on stdin there is no module
docstring, so `__doc__` is `None` and `.strip()` raises. Every bare
invocation — which is the first thing anyone runs — fails before doing any
work.

## 2. `surface-v1.json` has drifted from the current tool surface

`validation/lib/surface.py:242` treats `description` as a critical field
and `verify_live` reports `in_sync` only on exact match:

```python
_TOOL_CRITICAL = ("name", "description", "arguments")
```

Three tools differ from the baseline, before any intentional edits:

| tool | baseline | current |
| --- | --- | --- |
| `saf_query_viewpoint` | 491 | 1,038 |
| `saf_get_viewpoint_views` | 406 | 522 |
| `saf_export_viewpoint` | 352 | 374 |

A fourth, `create_part` (1,052 vs 1,054), differs by two characters and is
probably an extraction nuance rather than a real edit — worth confirming
when the baseline is regenerated.

Known drift, accepted for now. Recorded so the next person does not
re-investigate it.

## 3. Not a defect: the 75th tool is not missing

`surface-v1.json` lists 75 tools and the source tree declares 74, which
looks like a removed tool. It is not. `find_elements` is declared with
**single-quoted** arguments:

```groovy
// scripts/model_find.groovy:52
@McpTool(
    name = 'find_elements',
    description = '''Search the entire model ...''')
```

Any extractor that assumes `name = "..."` with double quotes silently
misses it. Two such extractors were written during the description review
before this was caught. If the suite's own parser shares that assumption,
it is a latent bug in the same family as defect 1.

## Regeneration policy

Agreed: `surface-v1.json` is regenerated when tool descriptions change
intentionally.

**Do not regenerate until the trimming pass is finished.** Regenerating
now would write the three drifted descriptions above into the new baseline
and silently promote them to "correct", destroying the evidence that the
drift exists.

Order of operations:

1. Finish the description trimming.
2. Regenerate the baseline once, at the end.
3. Update `validation/tasks/T04-conceptual-system-ambiguity.json` and
   `validation/models/ffds.json` if any trimmed description is asserted
   there.

## Related

The concept-vocabulary rename filed in
`.scratch/saf-concept-vocabulary/issues/01-align-kind-vocabulary-with-concept.md`
also changes the surface, and will require its own baseline regeneration.
Doing the trim first and the vocabulary change after keeps that to two
regenerations rather than three.
