# Workflow Capability Reference

Supported top-level fields include:

- `name`
- `version`
- `description`
- `agents`
- `workflow`
- `variables`
- `timeout`
- `error_handling`
- `directory_isolation`
- `concurrency`
- `knowledge_base`
- `global_agent`
- `tags`
- `code_preamble`
- `category`
- `module`

Supported step modes:

- `agent` + `input`
- `code`
- `group_chat`
- `agent_based`
- `classifier`: an LLM agent, or the TypeSafe Jev decision model through `classifier.jev`
- `state_machine`
- `sub_workflow`

Supported code languages:

- `python`
- `javascript`
- `typescript`
- `bash`
- `ruby`
- `lua`
- `cli`

## Classifier Step

```yaml
- step: triage
  classifier:
    agent: triager                    # LLM engine; omit it when `jev` is set
    input: "{{var:ticket_text}}"
    instructions: "Which team should handle this ticket?"   # optional, templates allowed
    categories:                       # at least 2, unique names
      - {name: billing, description: "Payments, invoices, refunds"}
      - {name: general, description: "Anything else"}
    output_variable: team             # default: classification
    default_category: general         # optional
```

- Branch downstream with `condition: "team == 'billing'"`.
- The step output starts with `classification: <category>`.
- An agent classifier writes only `output_variable`.

### Jev Engine (`classifier.jev`)

A `jev` block (even `jev: {}`) switches the classifier to TypeSafe Jev. With `jev`,
`agent` must be absent, and a Jev-only workflow still needs `agents: {}`. The
`jev` block accepts:

- `model`: optional, for example `jev-latest`.
- `min_confidence`: optional, between 0 and 1. Below it the classifier uses
  `default_category`; without one the step fails.
- `questions`: optional extra typed questions, answered in the same request.
- `composites`: optional, `- {name, weights: {question_id: weight}}`. Each is a weighted
  mean over `score` and `noul` answers, between 0 and 1.

Question types. Every question has `id` (matching `^[A-Za-z_][A-Za-z0-9_]*$`),
`type` and `instructions`.

| `type` | Fields |
| --- | --- |
| `choice` | `options` (2..255 `{name, description}`); optional `default_option`, `min_confidence` |
| `score` | `levels` (2..10 descriptions, lowest first) |
| `noul` | optional `yes_description`, `no_description`, `threshold` (default 0.5) |

Written variables. `<out>` is `output_variable` and `<id>` is a question id. Numbers
are plain decimal strings (at most 4 decimals), booleans are `"true"`/`"false"`, and
probability maps are JSON.

| Written by | Variables |
| --- | --- |
| classifier | `<out>`, `<out>_confidence`, `<out>_probabilities` |
| `choice` | `<id>`, `<id>_confidence`, `<id>_probabilities` |
| `score` | `<id>` (0..n-1), `<id>_level`, `<id>_normalized` (0..1), `<id>_confidence`, `<id>_probabilities` |
| `noul` | `<id>` (probability of yes), `<id>_yes` |
| composite | `<name>` |

The derived names must not collide within one `jev` block.

Errors:

- A missing key, HTTP 401/403, or HTTP 400/404/422 always fails the step.
- Rate-limit, overload, server and network errors (after retries) fall back:
  - With a `default_category`, `<out>` is the default category and
    `<out>_confidence` is `"0"`.
  - Every other variable is `""`, except `<id>_yes`, which is `"false"`.
  - Without a `default_category` the step fails.
- `""` never satisfies a numeric comparison.

## repeat_until with Jev

`repeat_until.jev` grades each iteration with Jev instead of `evaluate_agent`.

```yaml
repeat_until:
  condition: "quality >= 0.75"
  max_iterations: 3
  jev:
    state: "{{steps.draft.output}}"   # optional; this is the default
    questions:                        # required, at least one
      - {id: accuracy, type: score, instructions: "How accurate is the draft?", levels: ["Wrong", "Minor issues", "Accurate"]}
      - {id: on_brand, type: noul, instructions: "Is the tone friendly and plain?"}
    composites:
      - {name: quality, weights: {accuracy: 0.7, on_brand: 0.3}}
```

- `jev` cannot be combined with `evaluate_agent`, `evaluate_prompt`, `extract_pattern`
  or `extract_variable`.
- It writes the same variables as the classifier's extra questions.
- The critique, weakest dimension first, goes to `{{steps.<step>:evaluate.output}}`.
- Configuration errors fail the step at once. Transient errors write the fallback
  values, and the loop continues.
- `repeat_until` is rejected on `agent_based`, `code` and `sub_workflow` steps and
  together with `iterate_over`.

## Jev Credentials and Limits

API key, first match wins:

1. host `JevCredentials`;
2. parameters `typesafe_api_key`, `typesafe_ai_api_key` or `jev_api_key`, then
   `llm_provider_keys[typesafe|typesafe_ai|jev]`;
3. env `TYPESAFE_API_KEY`, only when the executor allows the env fallback (the CLI
   does).

Model: `jev.model` → host default → parameter `typesafe_model` → env
`TYPESAFE_DEFAULT_MODEL` → `jev-latest`. Base URL: env `TYPESAFE_BASE_URL` only.

- Jev is not a chat model. `typesafe`, `typesafe_ai` and `jev` are rejected as agent
  LLM providers.
- A request may use up to 64k tokens, and 32k for the state plus the longest question.
- English is most accurate. CJK works with lower accuracy, so set `min_confidence`
  together with a default.
- Workflow `condition` expressions stay plain comparisons: decide with Jev in a
  step, then branch on its variables.

Use `braidrun-workflow validate workflow.yaml` as the authority for whether a workflow is accepted by the current parser.
