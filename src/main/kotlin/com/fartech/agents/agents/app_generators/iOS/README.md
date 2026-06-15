# iOS App Development Agent (README)

This folder contains the iOS App Development agent that turns project properties and design assets into a runnable iOS
app project.

Overview (2025 flow)

1) AskRequirements (optional)
    - Skip via parameter skip_ask_requirements=true (typical for GUI flows)
    - Collects functional/technical requirements only; design files are not uploaded here
2) InitializeProject (by stack, delegated to Sub‑Agents)
    - Native iOS: generate project.yml + run xcodegen
    - Flutter: flutter create + configuration
    - React Native: npx init + configuration
    - Kotlin Multiplatform: Gradle setup + module creation
    - UniApp: minimal directories only
3) GetProjectFiles
    - Read the real directory tree and request the remaining manifest from LLM
    - Upload design files now (if any) to produce detailed asset descriptions
4) DesignArchitecture
    - Per‑file architecture for source code: functions, data structures, globals, dependencies, global principles
5) SetupNode → Batching
    - Split source vs media and create dependency‑aware batches
6) Parallel generation
    - Source files: GenerateSourceFilesParallel (batch size and parallelism are configurable)
    - Media files: GenerateMediaFilesParallel (independent files), errors tolerated and recorded
7) Media retry loop
    - Retry failed media one‑by‑one with a 2s delay to avoid rate limits
    - Max attempts controlled by media_retry_max_attempts
8) Compilation check (lightweight)
    - Non‑macOS: run a static/heuristic check across generated sources
    - macOS: build/test steps are delegated to Sub‑Agents
9) Summary

Key parameters

- project_properties: IOSAppProjectProperties (name, bundle ID, UI framework, stack, design files, etc.)
- skip_ask_requirements: Boolean (default false)
- architecture_design_max_retries: Int (default 3)
- file_generation_batch_size: Int (default 5)
- file_generation_parallelism: Int (default 5)
- media_generation_parallelism: Int (default 2)
- media_retry_max_attempts: Int (default 6)
- retry_cycle: Int (default 20)
- compilation_check_enabled: Boolean (default true)
- compilation_fix_max_retries: Int (default 2)

Media generation details

- Images are generated via MultimediaGenerationTools using OpenRouter chat‑completions wrappers.
- On success, base64 data URIs are decoded and written to target paths.
- Validation/repair: validateAndRepairMedia() checks known binary signatures; for textual outputs it attempts base64
  decode with padding fixes. SVGs are validated via simple tag checks.

macOS vs non‑macOS

- On macOS, build and testing are delegated to specific Sub‑Agents.
- On non‑macOS platforms, the agent stops after file generation + compilation check and produces a summary.

Notes

- The agent logs progress with timestamps via logProgress().
- Failures in parallel media generation are collected and retried sequentially to reduce load.
- All design‑time README documents are in English; user‑visible messages inside code can remain localized.
