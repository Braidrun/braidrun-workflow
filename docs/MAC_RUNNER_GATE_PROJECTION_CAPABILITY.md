# E gate-projection dependency alignment

Formal raw source: `2adcc5778d5e9d50f1ac3f00b4b7e2050da35776`,
`GATE_PROJECTION_COMPATIBILITY.md`. P/S `gate-projection.schema.json` SHA-256:
`fec8d0e3b9744aa5d370dc82f76b7223b45e3b675c152b2a9e65936bd41836e7`.

The shared E start/resume selector now explicitly requires `control.gateProjection.v1`
to select `control.gateSets.v1`, `artifact.set.v1` and `control.perCall.v1` too.
Missing any direct requirement yields RUNNER_GATE_PROJECTION_DEPENDENCY_REQUIRED.
Inherited set->collection dependency still applies. Missing available capabilities,
unknown selectors and perCall+callback conflicts retain their original refusals.
A controlled available-inventory fixture proves positive selector composition; it
is not proof of an installed guarded provider or normal G40/M30 gate acceptance.

E does not add an IPC frame, embed the projection HTTP schema or dispatch gate helpers.
P/S authenticate the exact runtime carrier, installed schema/source/hash pins and
actual legacy context plus set/projection providers. The legacy context provider is
NOT E's mutually exclusive control.callback.v1 selector: projected and LEGACY_FILE
entries can use their exact P/S per-call dispatch while E selects supervisor perCall.
No global all-typed routing, mode choice after403/412, fake set tuple for a static
publication or private legacy fallback is introduced.

Projection remains absent from E ready inventory until actual gate/source provider
assembly and the corresponding E inventory selection are assigned. Existing six raw
E schema bytes and old immutable packages remain unchanged. Runtime installed schema,
manifest fields and package flags are P/S-owned per the new companion.

Validation: nine RunnerCodeControlNegotiationTest cases (seven existing, two added),
including every missing direct/transitive dependency or available capability, ordinary
mode preservation, callback conflict and unknown capability. No closed matrix rerun,
real account/service acceptance, deployment or existing Runner upgrade.
