#!/usr/bin/env python3
"""Write the FFDS task dataset.

Generated rather than hand-edited so that every oracle clause sits next to the
`bin/vmodel fact` it was grounded in. The fact ids in `grounded_in` are keys in
models/ffds.json, which is produced from the live model by `bin/vmodel save ffds`.

Re-run after changing a fact; the linter will catch a task that references a fact id
the baseline no longer has.
"""

import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
TASKS = ROOT / "tasks"
FFDS = "SAF_FFDS.mdzip"
FFDS_SCRATCH = "SAF_FFDS.scratch.mdzip"   # disposable copy; never the pristine sample

TASKS_BY_ID = {}


def task(tid, title, text, first_tool, budget, reps, read_only, oracle, rationale,
         grounded_in, outcomes=None, preconditions=None, needs_calibration=None,
         model=None):
    d = {
        "id": tid, "title": title, "task": text, "readOnly": read_only,
        "expected_first_tool": first_tool, "call_budget": budget,
        "repetitions": reps, "rationale": rationale,
        "model": model or (FFDS if read_only else FFDS_SCRATCH),
        "preconditions": preconditions or ["SAF_Profile applied", "SAF_FFDS loaded"],
        "grounded_in": grounded_in,
        "oracle": oracle,
    }
    if outcomes:
        d["outcomes"] = outcomes
    if needs_calibration:
        d["needsCalibration"] = needs_calibration
    TASKS_BY_ID[tid] = d
    return d


def mentions(*pairs, minimum=1):
    """answer_mentions_all over (fact-id, [accepted surface forms])."""
    return {"kind": "answer_mentions_all", "min": minimum,
            "facts": [{"id": i, "any_of": forms} for i, forms in pairs]}


UNCHANGED = {"kind": "model_unchanged"}


# ---------------------------------------------------------------- T01
task(
    "T01-function-count",
    "How many SAF_Function elements does this model contain?",
    "Count the SAF_Function elements in this model. Be precise about what you counted: "
    "the stereotype search also matches SAF_FunctionAction, SAF_FunctionAsset and the "
    "SAF_FunctionContribution relationships, so the raw number of search hits is not the "
    "number of functions. If the model makes the definition genuinely ambiguous, say so "
    "and give both readings rather than picking one silently.",
    "saf_find_elements_by_type", budget=8, reps=3, read_only=True,
    grounded_in=["saf_function_count_by_type", "saf_function_count_by_stereotype",
                 "saf_function_substring_hits", "saf_function_overlap"],
    rationale="The most common way to get this wrong is to report the search hit count: "
              "SAF_Function is a substring of SAF_FunctionAction, SAF_FunctionAsset and "
              "SAF_FunctionContribution, so the search returns 105 rows for 39 functions. "
              "The model also contains one element ('Analyze FF data') carrying both "
              "SAF_Function and SAF_FunctionAsset, which makes 39-vs-40 a real definitional "
              "split. A task that accepts only one number rewards guessing; this one rewards "
              "reading the rows and explaining the split.",
    oracle=[
        mentions(("count", ["39", "40"])),
        mentions(("overlap-named", ["Analyze FF data", "analyze ff data", "FF data"]),
                 ("overlap-kind", ["FunctionAsset", "Function Asset", "function asset"])),
        {"kind": "answer_forbids",
         "none_of": ["105", "105 functions", "105 elements", "105 rows"],
         "note": "the substring-inflated hit count is never the answer"},
        UNCHANGED,
    ],
    outcomes=["Reports 39 or 40 and names the element or the reason for the split",
              "Reports 105 and fails"],
)

# ---------------------------------------------------------------- T02
task(
    "T02-hardware-enumeration",
    "Which physical hardware items does the model define?",
    "List the SAF_PhysicalHardware elements in this model. Give the names as the model "
    "spells them, including any typos -- a name that looks misspelled is evidence you read "
    "the model rather than recalled a catalogue.",
    "saf_find_elements_by_type", budget=10, reps=3, read_only=True,
    grounded_in=["saf_physical_hardware_count"],
    rationale="Enumeration is the cheapest honest check that an agent read rows rather than "
              "pattern-matched a plausible-sounding answer. The model contains a misspelled "
              "name ('HmIP-VDMOT modiefied Long Cable'), which a reciting model will silently "
              "correct and a reading model will reproduce.",
    oracle=[
        mentions(("known-hardware-1", ["Arduino Mega 2560 R3", "Arduino Mega 2560"]),
                 ("known-hardware-2", ["AAA Battery", "CPU Board", "ValveBlock5"]),
                 minimum=2),
        UNCHANGED,
    ],
    outcomes=["Names at least two real hardware items"],
)

# ---------------------------------------------------------------- T03
task(
    "T03-function-saf-kind",
    "What SAF kind is the function 'measure heat level'?",
    "Look up the element named 'measure heat level' and report the SAF concept kind and "
    "domain the tooling assigns to it. Report what the tool actually returns, not what the "
    "name suggests.",
    "saf_find_elements_by_type", budget=6, reps=3, read_only=True,
    grounded_in=["probe_function", "probe_function_relationship_count"],
    rationale="The SAF kind comes from a narrow inference over stereotypes, not from the "
              "element's name, so it is the cheapest way to see whether an agent used "
              "saf_get_element_semantics or just guessed from the word 'function'. This "
              "element also reports zero relationships, so an agent that tries to confirm the "
              "kind by following links will find nothing and should say so.",
    oracle=[
        mentions(("kind", ["system_function", "system function", "System Function"])),
        {"kind": "trajectory_used", "tool": "saf_get_element_semantics", "min_calls": 1},
        UNCHANGED,
    ],
    outcomes=["Reports system_function"],
)

# ---------------------------------------------------------------- T04
task(
    "T04-conceptual-system-ambiguity",
    "Is the SAF kind of the conceptual system 'Camera' unambiguous?",
    "The model contains a conceptual system named 'Camera'. Determine whether the tooling "
    "can tell you a single SAF concept kind for it. If it cannot, say which concepts it is "
    "choosing between and why that is not a defect to paper over.",
    "saf_find_elements_by_type", budget=6, reps=3, read_only=True,
    grounded_in=["probe_conceptual_system"],
    rationale="SAF_ConceptualSystem realizes two distinct SAF concepts -- Conceptual System "
              "and Conceptual External System -- so the semantics tool reports the element as "
              "ambiguous and leaves safKind empty. The correct behaviour is to report the "
              "ambiguity and both candidates. An agent that picks one and presents it as "
              "settled is fabricating, and this task is how we catch that without the model "
              "being at fault.",
    oracle=[
        mentions(("ambiguous", ["ambiguous", "not unambiguous", "cannot be determined",
                                "no single", "both"]),
                 ("candidate-1", ["Conceptual System", "conceptual_system"]),
                 ("candidate-2", ["Conceptual External System", "conceptual_external_system"]),
                 minimum=2),
        UNCHANGED,
    ],
    outcomes=["Reports the ambiguity and names both candidate concepts"],
)

# ---------------------------------------------------------------- T05
task(
    "T05-requirement-id-and-text",
    "What does stakeholder requirement CPBLTY-12 actually say?",
    "Find the stakeholder requirement with id CPBLTY-12 and quote its text. The id and text "
    "are not in the element name, so find the requirement by search and read the field that "
    "carries them.",
    "saf_find_elements_by_type", budget=8, reps=3, read_only=True,
    grounded_in=["probe_requirement"],
    rationale="Requirement id and text live on a tagged value reached through the SAF "
              "semantics layer, not on the element name or a generic dump. An agent that "
              "answers from the name alone cannot produce the text at all, which makes this "
              "a clean test of whether the deep-contract path was used.",
    oracle=[
        mentions(("id", ["CPBLTY-12"]),
                 ("text", ["100% of the terrain", "monitor fire areas"])),
        {"kind": "trajectory_used", "tool": "saf_get_element_semantics", "min_calls": 1},
        UNCHANGED,
    ],
    outcomes=["Reports id CPBLTY-12 with the terrain-monitoring text"],
)

# ---------------------------------------------------------------- T06
task(
    "T06-block-structure",
    "What is inside the Commercial LORAWAN Gateway?",
    "Describe the internal structure of the block 'Commercial LORAWAN Gateway': its parts, "
    "its ports and what types those ports. If a category is empty, say it is empty rather "
    "than omitting it.",
    "get_block_structure", budget=5, reps=3, read_only=True,
    grounded_in=["probe_block_structure"],
    rationale="A structure read is answerable only through the dedicated tool, and this block "
              "is a good shape for it: zero parts, one proxy port, zero connectors. A model "
              "that reports a confident internal architecture here is inventing one.",
    oracle=[
        mentions(("port", ["LORa Waveforms", "LoRa Waveforms", "LORa"]),
                 ("typed-by", ["LoRA EU Waveforms", "LoRa EU Waveforms"]),
                 minimum=1),
        {"kind": "trajectory_used", "tool": "get_block_structure", "min_calls": 1},
        UNCHANGED,
    ],
    outcomes=["Reports one port typed by the LoRA EU Waveforms interface",
              "Reports no parts and no connectors"],
)

# ---------------------------------------------------------------- T07
task(
    "T07-operational-exchanges",
    "Which operational exchange types does the model define?",
    "List the SAF_OperationalExchangeType elements in this model. Beware that the `type` "
    "field of these rows is the bare UML metaclass, not the stereotype, so a filter on the "
    "type field will appear to find nothing.",
    "saf_find_elements_by_type", budget=8, reps=3, read_only=True,
    grounded_in=["saf_operational_exchange_type_count", "saf_operational_exchange_type_names"],
    rationale="Second filter trap, and a nastier one than the substring case: the search "
              "reports type='Class' for these elements, so filtering rows on the type field "
              "returns zero and an agent may conclude the model has no operational exchanges. "
              "Filtering on the stereotypes list finds all nine.",
    oracle=[
        mentions(("exchange-1", ["Operational State", "Reported Condition", "Updated Location"]),
                 ("exchange-2", ["Heat & Smoke", "Fire Spark", "Distress Call",
                                 "Fire Alert Report", "Updated Condition"]),
                 minimum=2),
        UNCHANGED,
    ],
    outcomes=["Names at least two real operational exchange types"],
)

# ---------------------------------------------------------------- T08 (mutating)
task(
    "T08-create-software-block",
    "Add a software block to the physical architecture and link it",
    "Create a new software block named 'ValidationProbeBlock' in the physical architecture "
    "package of this model, then report the id the tool assigned to it.",
    "saf_create_element", budget=12, reps=1, read_only=False,
    model=FFDS_SCRATCH,
    preconditions=[
        "SAF_Profile applied",
        "SAF_FFDS.scratch.mdzip loaded -- a disposable COPY of SAF_FFDS.mdzip",
        "NEVER run this task against the pristine SAF_FFDS.mdzip: it is a shared sample "
        "model from the SAF profile repo, not a scratch file",
    ],
    grounded_in=["saf_physical_system_count"],
    rationale="One mutating task so the suite can measure write behaviour, and it is pointed "
              "at a scratch copy on purpose. The pristine SAF_FFDS.mdzip is a human-maintained "
              "sample from another repository; a validation run that dirties it would destroy "
              "someone else's work to produce a number.",
    oracle=[
        {"kind": "element_exists", "name": "ValidationProbeBlock",
         "note": "created by the run; absence means the create did not happen"},
        {"kind": "count_at_least", "name": "ValidationProbeBlock", "min": 1},
        {"kind": "count_at_most", "name": "ValidationProbeBlock", "max": 1,
         "note": "more than one means a retry created a duplicate"},
    ],
    needs_calibration=[
        "confirm the scratch copy exists and is loaded; `bin/vmodel resolve ffds-scratch`",
        "the create may land in a different package than intended -- check the parentId in "
        "the run output before trusting the count clauses",
    ],
    outcomes=["One ValidationProbeBlock exists with the id the tool returned"],
)


def main() -> None:
    for d in TASKS_BY_ID.values():
        p = TASKS / f"{d['id']}.json"
        p.write_text(json.dumps(d, indent=2, ensure_ascii=False) + "\n")
        print("wrote", p.relative_to(ROOT))
    print(f"{len(TASKS_BY_ID)} tasks, "
          f"{sum(len(t['oracle']) for t in TASKS_BY_ID.values())} clauses")


if __name__ == "__main__":
    main()
