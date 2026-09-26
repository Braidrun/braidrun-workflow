# Workflow YAML

Every workflow has a name, optional agents, and a list of steps.

```yaml
name: example
version: 1.0.0
agents:
  analyst:
    preset: data_analyst
workflow:
  - step: summarize
    agent: analyst
    input: "Summarize the data in ./data/input.csv."
```

## Display Translations

Use `translations` for user-visible workflow and agent metadata. The canonical
`name` and `description` remain English defaults. Runtime fields such as step
inputs, system prompts, code, variables, state names, and extraction markers are
not localized.

```yaml
name: code-review
version: 1.0.0
description: Generic code review workflow for repository changes.
translations:
  en:
    name: Code Review
    description: Generic code review workflow for repository changes.
  zh:
    name: 代码审查
    description: 面向仓库改动的通用代码审查工作流。
agents:
  reviewer:
    preset: coder
    description: Reviews changes for correctness and regressions.
    translations:
      en:
        description: Reviews changes for correctness and regressions.
      zh:
        description: 审查改动的正确性与回归风险。
workflow:
  - step: review
    agent: reviewer
    input: "Review the current repository changes."
```

Bundled templates provide `en`, `zh`, `zhHant`, `ja`, `ko`, `es`, `fr`, `de`,
`ar`, `pt`, and `vi`. Consumers should fall back to the canonical fields when a
custom workflow does not provide the requested locale.

## Code Step

```yaml
name: hello-code
version: 1.0.0
agents: {}
workflow:
  - step: hello
    code:
      language: bash
      script: |
        echo "Hello from Braidrun Workflow"
```

Supported code languages are `python`, `javascript`, `typescript`, `bash`, `ruby`, `lua`, and `cli`.

## Agent Step

```yaml
agents:
  coder:
    preset: coder
    overrides:
      max_iterations: 32
workflow:
  - step: inspect
    agent: coder
    input: "Inspect this repository and list the most important tests."
```

## Parallel Steps

Use `depends_on` to make independent steps run when their dependencies are satisfied.

```yaml
workflow:
  - step: collect
    agent: researcher
    input: "Collect source material."
  - step: analyze
    agent: analyst
    depends_on: [collect]
    input: "Analyze the collected material."
  - step: write
    agent: writer
    depends_on: [analyze]
    input: "Write the final report."
```

## Classifier Step

A `classifier` step puts its `input` into exactly one of the `categories`, writes
the category name to `output_variable`, and outputs `classification: <category>`.
Downstream steps branch with a plain `condition`. Skipped branches do not block
steps that depend on them.

```yaml
agents:
  triager:
    preset: universal
  billing_agent:
    preset: universal
workflow:
  - step: triage
    classifier:
      agent: triager
      input: "{{var:ticket_text}}"
      instructions: "Which team should handle this support ticket?"   # optional
      categories:
        - {name: billing, description: "Payments, invoices, refunds"}
        - {name: technical, description: "Bugs, outages, integrations"}
        - {name: general, description: "Anything else"}
      output_variable: team        # default: classification
      default_category: general    # optional; used when the agent fails or names no category
  - step: handle_billing
    agent: billing_agent
    depends_on: [triage]
    condition: "team == 'billing'"
    input: "Resolve this billing ticket: {{var:ticket_text}}"
```

- A classifier needs at least 2 categories, and category names must be unique.
- `instructions` (optional, templates allowed) describes the classification task.
  The agent receives it as a `TASK:` section of its prompt.
- With an `agent`, the classifier writes only `output_variable`. To classify with the
  TypeSafe Jev decision model instead, see
  [Jev (TypeSafe) Decisions](#jev-typesafe-decisions).

## Runtime Variables

CLI variables passed with `--var key=value` are available to the workflow execution context.

```bash
braidrun-workflow run workflow.yaml --var topic="agent workflow runtime"
```

## Sub-Workflows

Use `sub_workflow` when one workflow should call another workflow as a reusable module. The CLI uses a file-system resolver rooted at the parent workflow directory and the current working directory.

## Models and LLM Settings

A preset agent picks its model through `llm_config` in `overrides`. The model
catalog lives in `src/main/resources/models/*.yaml` (one file per provider);
use a key from those files as `model`, or any other id, which is sent as-is.
First-party Anthropic ids are dashed (`claude-opus-5`, `claude-opus-4-8`);
OpenRouter ids are dotted (`claude-sonnet-4.5` → `anthropic/claude-sonnet-4.5`).
The tier aliases `claude-opus` and `claude-sonnet` currently point at Opus 4.8
and Sonnet 4.6.

```yaml
agents:
  writer:
    preset: writer
    overrides:
      llm_config:
        models:
          - provider: anthropic
            model: claude-opus-5
        cascade_fallbacks:
          - provider: open_router
            model: claude-sonnet-4.5
        temperature: 0.7
      cascade_fallback_enabled: true
workflow:
  - step: draft
    agent: writer
    input: "Draft the release announcement."
```

- `llm_config` accepts snake_case and camelCase keys: `base_url` / `baseUrl`,
  `max_token` / `maxToken`, `is_vision` / `isVision`, `display_name`,
  `custom_models`, `cascade_fallbacks`, and in custom models `model_id`,
  `context_length`, `max_output_tokens`. A blank `base_url` means the provider
  default. Before 1.3.0 the snake_case spellings were silently ignored.
- Parameters are fitted to each model on every request, including fallback and
  cascade tiers. `temperature` is left out for models that reject it (Claude
  Opus 4.7+, Sonnet 5, Fable 5.x, Kimi K3, GPT-5.x / 6 and any model without the
  `temperature` capability); other tiers still get it. A forced tool choice
  becomes `auto` for models that do not support it.
- Direct Anthropic requests without `max_tokens` default to 16,000 output
  tokens (64,000 when streaming), capped by the model's limit.
- Models on OpenAI-compatible providers (OpenAI, DashScope, Kimi, MiniMax, Z.ai,
  NVIDIA, LM Studio) need an `openai.completions` or `openai.responses`
  capability; catalog entries declare it, and custom models get
  `openai.completions` when they declare neither.
- `num_choices` greater than 1 is not metered (token usage and cost are not
  reported for those rounds). Hosts that bill per token cap it at 1, so on the
  hosted Braidrun service it has no effect.

## Tool Result Images

`browser_screenshot` and MCP tools that return images send them to the model as
images when the model can view them: Anthropic vision models, Gemini 3+ vision
models, and OpenAI models (not Azure) that use only the Responses API (e.g. the
`-pro` and `-codex` entries). GPT models that also declare `openai.completions`
use Chat Completions and get placeholders, as do OpenRouter and other
OpenAI-compatible providers. Other models get a
short text placeholder with the image type, size and the reason. Screenshots
are also saved to disk as before. Browser contexts belong to the run that opened
them (see [Security](SECURITY.md#run-scoped-tool-state)), so a screenshot always
shows the current run's own page.

Each attached image is re-sent on every later round (roughly 1.2–1.6K input
tokens per image per round). Three agent parameters bound the cost:

| Parameter | Default | Meaning |
| --- | --- | --- |
| `tool_result_images_enabled` | `true` | `false` sends placeholders only. Accepts booleans or `true`/`false`/`1`/`0`/`yes`/`no`/`on`/`off` strings. |
| `tool_result_images_max_per_request` | `4` | Most recent images kept per request, clamped to 0–12. |
| `tool_result_images_max_per_run` | `24` | Images attached per agent run, clamped to 0–200; `0` turns images off. |

```yaml
agents:
  operator:
    preset: computer_operator
    overrides:
      tool_result_images_max_per_request: 2
      tool_result_images_max_per_run: 10
workflow:
  - step: check_page
    agent: operator
    input: "Open https://example.com and describe the page layout."
```

Images are shrunk to at most 1568 px and 1 MB; WebP larger than 1568 px is not
attached. Agents with durable persistence (`enable_persistence` with a
non-memory `persistence_storage_type`) stay text-only, and prompts that carry
images skip the prompt cache.

## Skills

Agents with the `skill_tools` group see an `<available_skills>` catalog and load
a skill with `useSkill`, which returns the full instructions every time it is
called. Without `skill_tools` in an explicit tool list, or with
`disable_skills: true`, the catalog is not added to the prompt. Per-skill MCP
servers do not start unless the host opts in. See [Skills](SKILLS.md) for the
`SKILL.md` format and discovery rules.

## Jev (TypeSafe) Decisions

[Jev](https://docs.typesafe.ai) is TypeSafe AI's decision model. It does not write
text. It answers typed questions about a piece of text (the *state*) with calibrated
probabilities:

- `choice`: pick one option.
- `score`: rate on an ordered scale.
- `noul`: yes or no.

Many questions can share one request, and Jev reads the state only once.

Braidrun Workflow uses Jev in two places:

| Where | What Jev does |
| --- | --- |
| `classifier.jev` | Answers the classifier instead of an agent, and can answer extra typed questions in the same request. |
| `repeat_until.jev` | Grades each loop iteration instead of an `evaluate_agent` and regex extraction. |

Jev is not a chat model. An agent with `llm.provider: typesafe` (also `typesafe_ai`
or `jev`) fails when it is created with:

> TypeSafe Jev is a decision model, not a chat model. Use it via classifier.jev or repeat_until.jev.

Runnable examples:

- [`examples/workflows/jev-support-triage.yaml`](../examples/workflows/jev-support-triage.yaml)
  needs only `TYPESAFE_API_KEY`.
- [`examples/workflows/jev-quality-loop.yaml`](../examples/workflows/jev-quality-loop.yaml)
  pairs a writer agent with Jev grading.

### Classifier with Jev (`classifier.jev`)

A `jev` block turns a classifier into a Jev classifier. `jev: {}` is enough. The
classifier then must not have an `agent`. A Jev-only workflow still needs an
`agents: {}` line.

```yaml
agents: {}
workflow:
  - step: triage
    classifier:
      input: "{{var:ticket_text}}"      # sent to Jev as the state
      instructions: "Which team should handle this support ticket?"
      categories:
        - {name: billing, description: "Payments, invoices, refunds"}
        - {name: technical, description: "Bugs, outages, integrations"}
        - {name: general, description: "Anything else"}
      output_variable: team
      default_category: general
      jev:
        model: jev-latest               # optional
        min_confidence: 0.6             # optional, 0..1
        questions:                      # optional extra typed questions
          - id: is_urgent
            type: noul
            instructions: "Does the customer say the problem is time-sensitive?"
            threshold: 0.7
        composites: []                  # optional weighted scores
  - step: escalate
    depends_on: [triage]
    condition: "team_confidence >= 0.8 && is_urgent_yes == 'true'"
    code:
      language: bash
      script: echo "Escalating a ${WF_VAR_TEAM} ticket"
```

How a Jev classifier runs:

1. `input` is resolved and sent as the Jev `state`.
2. The main question is a `choice` over `categories`. Each option is described by its
   category description. `instructions` is the question text. Without
   `instructions` it is "Classify the input into the single best-matching category."
3. Jev answers every extra question in the same request, so each step run makes one
   request (plus retries).
4. If `min_confidence` is set and Jev's confidence is below it, the classifier uses
   `default_category`. Without a `default_category` the step fails, for example:
   `Classifier step 'triage': Jev confidence 0.42 is below min_confidence 0.6 and no default_category is set`.
   A missing confidence counts as below the threshold.
5. The step output always starts with `classification: <category>`. When the category
   is Jev's own answer, a second line gives its confidence:
   ```text
   classification: billing
   confidence: 0.81
   ```
   After any fallback there is no `confidence:` line.

Jev classifiers also work as `state_machine` states. Their events are recorded under
the state's sub-step name, such as `flow.route`. `dry-run` labels a Jev classifier
`classifier(jev)`. The monitor shows `classifier(jev)`, or `classifier(jev:<model>)` when
`jev.model` is set.

### Extra Questions and Composites

`questions` is available on both `classifier.jev` and `repeat_until.jev`. Every
question has an `id`, a `type` and non-blank `instructions`. Instructions may use
`{{...}}` templates.

| `type` | Fields | Jev answers |
| --- | --- | --- |
| `choice` | `options` (2..255 `{name, description}`, unique names); optional `default_option` (one of the options) and `min_confidence` (0..1) | the chosen option, its confidence and a probability per option |
| `score` | `levels`: 2..10 non-blank descriptions, lowest first | a probability-weighted score between 0 and n-1, with confidence and a probability per level |
| `noul` | optional `yes_description`, `no_description` (non-blank when set) and `threshold` (0..1, default 0.5) | the probability of "yes" |

A field that belongs to another type is rejected. For example, `levels` on a `choice`
question fails validation.

For an extra `choice` question, `min_confidence` works like the classifier's. Below
it, the question uses `default_option`. Without a `default_option` the step fails.

`composites` combine `score` and `noul` answers into one 0..1 number:

```yaml
composites:
  - name: priority
    weights: {frustration: 0.4, is_urgent: 0.6}
```

The value is the weighted mean `Σ w·v / Σ w`. For a `score` question, `v` is its
normalized value; for a `noul` question, `v` is the probability of "yes". Weights must
be finite and greater than 0. A composite may reference only `score` and `noul`
questions of the same block.

### repeat_until with Jev (`repeat_until.jev`)

```yaml
- step: draft
  agent: writer
  input: |
    Write the announcement. Fix the weakest points in this feedback first:
    {{steps.draft:evaluate.output}}
  repeat_until:
    condition: "quality >= 0.75"
    max_iterations: 3
    jev:
      model: jev-latest                         # optional
      state: "{{steps.draft.output}}"           # optional; this is the default
      questions:                                # required, at least one
        - {id: accuracy, type: score, instructions: "How factually accurate is the draft?", levels: ["Clear errors", "Minor imprecision", "Accurate"]}
        - {id: on_brand, type: noul, instructions: "Does the draft use a friendly, plain-language tone?"}
      composites:
        - {name: quality, weights: {accuracy: 0.7, on_brand: 0.3}}
```

After each successful iteration, the runtime:

1. resolves `state` (default `{{steps.<this step>.output}}`);
2. asks all questions in one request;
3. writes the variables and composites;
4. evaluates `condition` as usual.

A deterministic English critique goes to `{{steps.<step>:evaluate.output}}`. It has
one line per question, weakest `score`/`noul` answer first, then `choice` answers, then
composites:

```text
Jev evaluation (iteration 2, model jev-1.13.0):
- on_brand: yes (p=0.9)
- accuracy: level 2/2 "Accurate" (score 2, confidence 0.84)
- quality (composite): 0.97
```

Things to know:

- `jev` cannot be combined with `evaluate_agent`, `evaluate_prompt`, `extract_pattern`
  or `extract_variable`. Blank values count as absent.
- In the first iteration, `{{steps.<step>:evaluate.output}}` does not exist yet, and
  the placeholder text stays as it is. This is the same as with `evaluate_agent`.
- Jev cannot read files or see other steps. Put everything it needs to judge into
  `state`, for example the source notes and the draft.
- If `max_iterations` is reached without meeting the condition, the step still
  succeeds with its last output. Branch on the variables downstream, for example
  `quality < 0.75 || quality == ''`.
- `repeat_until.jev` works on every step that accepts `repeat_until`, such as agent
  and `group_chat` steps. It is still rejected on `agent_based`, `code` and
  `sub_workflow` steps and together with `iterate_over`. State-machine states have no
  `repeat_until`.

### Written Variables

Jev writes ordinary workflow variables. Code steps see them as upper-case
`WF_VAR_<NAME>` environment variables, for example `WF_VAR_TEAM_CONFIDENCE`.
`<out>` is the classifier's `output_variable`, and `<id>` is a question id.

| Variable | Written by | Value |
| --- | --- | --- |
| `<out>` | classifier | final category: Jev's choice, or `default_category` after a fallback |
| `<out>_confidence` | classifier | Jev's confidence in the main choice |
| `<out>_probabilities` | classifier | JSON object category → probability |
| `<id>` | `choice` | chosen option, or `default_option` below `min_confidence` |
| `<id>_confidence`, `<id>_probabilities` | `choice` | confidence; JSON object option → probability |
| `<id>` | `score` | raw score between 0 and n-1 (n = number of levels) |
| `<id>_level` | `score` | score rounded to the nearest level index, 0..n-1 |
| `<id>_normalized` | `score` | score / (n-1), between 0 and 1 |
| `<id>_confidence`, `<id>_probabilities` | `score` | confidence; JSON object `"0"`..`"n-1"` → probability |
| `<id>` | `noul` | probability of "yes", between 0 and 1 |
| `<id>_yes` | `noul` | `"true"` when `<id>` ≥ `threshold` (default 0.5), else `"false"` |
| `<name>` | composite | weighted mean between 0 and 1 |

An agent classifier (no `jev`) still writes only `<out>`.

Validation rejects a Jev block that would write the same variable twice. For example,
it rejects:

- two questions with the same id;
- a composite named like a question;
- a question id equal to `output_variable`;
- a question `team_confidence` in a classifier whose `output_variable` is `team`.
Jev variables are global like any other variable. A later step that writes the same
name overwrites them.

Formatting rules:

- Numbers are plain decimal strings with at most 4 fraction digits and no trailing
  zeros. They never use scientific notation: `0.81`, `0.525`, `1`, `0`.
- Probability objects are compact JSON with the same number format. Keys follow the
  configured order, and missing keys are 0: `{"billing":0.88,"technical":0.12,"general":0}`.
- Booleans are the strings `"true"` and `"false"`.
- Conditions compare numbers with `>=`, `<` and the other numeric operators, for
  example `team_confidence >= 0.8`.

### Errors and Fallbacks

The client retries HTTP 429, 529, 500, 502, 503 and 504 responses, network errors
and timeouts. It makes up to 4 attempts with exponential backoff and honors
`retry-after` up to 20 s. Each request times out after 30 s. It never retries
400, 401, 403, 404 or 422.

| Situation | `classifier.jev` | `repeat_until.jev` |
| --- | --- | --- |
| No API key; HTTP 401/403 (bad key); HTTP 400/404/422 (invalid request) | Step fails, even with a `default_category` | Step fails at once. No further iterations run, and `on_success` does not fire. |
| Rate limit, overload, server or network error after retries; invalid response | With `default_category`: fallback values and a warning event. Without one: step fails. | Fallback values and a warning event; the loop continues |
| Main confidence below `jev.min_confidence` | `default_category`, keeping Jev's real confidence and probabilities; without one the step fails | — |
| Extra `choice` question below its `min_confidence` | `default_option`; without one the step fails | same |
| Execution cancelled | cancellation propagates | cancellation propagates |

Fallback values overwrite every variable, so no stale value from an earlier run of the
same step survives:

- Classifier error fallback:
  - `<out>` is `default_category`.
  - `<out>_confidence` is `"0"`.
  - `<out>_probabilities` is `"{}"`.
  - Every other variable is `""`, except `<id>_yes`, which is `"false"`.
- `repeat_until` error fallback: every variable is `""`, except `<id>_yes`, which is
  `"false"`. The previous critique in `<step>:evaluate` is kept.

An empty string never satisfies a numeric comparison: both `quality >= 0.75` and
`quality < 0.75` are false. Test for `quality == ''` when the fallback matters. After a
classifier fallback, `<out>_confidence` is below `min_confidence` in both cases:

- low confidence: the real value;
- error: `"0"`.

So `team_confidence < 0.6` catches both.

Jev is never called again when an execution resumes. A classifier's variables come
back from the step's saved results. `repeat_until.jev` refreshes the saved results
after every evaluation, so a restart restores the final values.

### Credentials and Settings

The API key is resolved for every Jev call. The first match wins:

1. The host's `JevCredentials`, passed to the `WorkflowExecutor` constructor.
2. Runtime parameters, in this order:
   - `typesafe_api_key`
   - `typesafe_ai_api_key`
   - `jev_api_key`
   - the `typesafe`, `typesafe_ai` or `jev` entry of the `llm_provider_keys` map
     parameter.

   For example, pass `--param typesafe_api_key=...` on the CLI.
3. The `TYPESAFE_API_KEY` environment variable, read only when the executor's
   `jevEnvKeyFallback` is `true`. That is the default, and what the CLI uses.

Without a key the step fails with:

> TypeSafe API key is not configured. Set TYPESAFE_API_KEY, pass --param typesafe_api_key=..., or connect "TypeSafe (Jev)" in the Braidrun credential center.

Other settings, first match wins:

| Setting | Resolution order |
| --- | --- |
| Model | `jev.model` → `JevCredentials.defaultModel` → parameter `typesafe_model` → env `TYPESAFE_DEFAULT_MODEL` → `jev-latest` |
| Base URL | env `TYPESAFE_BASE_URL` (http or https; a trailing `/v1` is fine) → `https://api.typesafe.ai` |

Model aliases are `jev-latest` and `jev-preview`. Pinned versions such as `jev-1.13.0`
also work.

The base URL is never read from workflow YAML or runtime parameters, so a workflow
cannot send the key to another host. Jev requests use the executor's `HttpAccess`
client: the same egress proxy and SSRF guard as other workflow HTTP traffic. The key
never appears in workflow variables, step outputs, events or log messages.

Hosts that embed the executor pass their own key and turn off the environment
fallback:

```kotlin
val executor = WorkflowExecutor(
    httpAccess = HttpAccess(),
    baseParameters = parameters,
    jevCredentials = JevCredentials(apiKey = userKey, defaultModel = "jev-latest"),
    jevEnvKeyFallback = false   // an operator's TYPESAFE_API_KEY never pays for users' steps
)
```

`TYPESAFE_DEFAULT_MODEL` and `TYPESAFE_BASE_URL` are operator settings. They still
apply when `jevEnvKeyFallback` is `false`. Tests can replace the HTTP client with the
`jevClientFactory` constructor parameter.

### Events

Jev events are recorded under the step's own name.

A `classifier.jev` step records, in order:

1. `classifier_started`;
2. one `llm_call_completed` event per Jev request;
3. `classifier_completed`.

A `repeat_until.jev` step records one `llm_call_completed` event and one
`repeat_until_evaluate` event per iteration, next to the usual `repeat_until_*` events.

`llm_call_completed` events:

- have category `llm` and sub-category `call`;
- carry token counts;
- have the detail `model=<model>, provider=TypeSafe; input=N, output=M`.

Hosts can use the `provider=TypeSafe` marker to meter Jev tokens separately.

The `detail` of `classifier_completed` and `repeat_until_evaluate` is a compact JSON
decision with snake_case keys:

- always `engine`, `kind`, `model`, `fell_back`, `fallback_reason`, `answers`,
  `composites` and `usage`;
- for classifiers, also `output_variable`, `category`, `confidence`,
  `probabilities` and `min_confidence`;
- for loops, also `iteration`.

A fallback after an error also records a `jev_call_failed` warning.

### Limits

Checked by the validator:

- At most 255 classifier categories or `choice` options, and at least 2.
- 2 to 10 `score` levels.
- Question ids and composite names must match `^[A-Za-z_][A-Za-z0-9_]*$`.
- `min_confidence` and `threshold` must be between 0 and 1.
- `jev.model` must not contain spaces.

Checked by the API, not the engine:

- A request may use up to 64k tokens: the state plus all questions.
- The state plus the longest single question may use up to 32k tokens.

The engine does not count tokens. The API rejects oversized requests, and the step
fails.

### Language Support

English is Jev's primary language and where it is most accurate. It accepts CJK and
other languages with lower accuracy. For non-English content:

- Write `instructions`, category descriptions and levels in English where you can.
- Set `min_confidence` together with a `default_category` or `default_option`, so
  uncertain answers take a safe route instead of a wrong one.

Jev is also weak at numeric precision and arithmetic. Ask it about meaning, and
compute numbers in a `code` step.

### Why Not an AI Condition?

`condition:` stays a plain comparison, and Jev decisions live in steps:

- **Conditions run many times.** The scheduler evaluates conditions synchronously,
  every time it scans for runnable steps. A model call there would be billed on
  every scan, and nothing would record it for resume or audit.
- **Conditions resist injection.** They are parsed before variables are substituted,
  so a step output cannot change a condition's structure. Free-form model prompts
  inside conditions would undo that.

Make the decision once with `classifier.jev` or `repeat_until.jev`. It is recorded as
variables, events and a resumable step output. Then branch on those variables with
ordinary conditions.
