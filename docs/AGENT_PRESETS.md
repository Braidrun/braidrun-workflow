# Agent Presets

Agent presets live in `src/main/resources/agent-presets`.

List them from the CLI:

```bash
braidrun-workflow list-presets
```

Use a preset in workflow YAML:

```yaml
agents:
  coder:
    preset: coder
    overrides:
      max_iterations: 64
workflow:
  - step: inspect
    agent: coder
    input: "Inspect the repository and summarize the build system."
```

Built-in presets:

| Preset | Category | Purpose |
| --- | --- | --- |
| `universal` | general | General tool-using agent for broad workflow tasks |
| `universal_reasoning` | general | General agent with stronger reasoning behavior |
| `lightweight` | general | Minimal agent for small, lower-resource tasks |
| `chat` | chat | Multi-turn conversational assistance |
| `coder` | coding | Code analysis, generation, refactoring, Git, shell, and repository work |
| `devops` | coding | Shell scripting, Git, code execution, database, and system tasks |
| `researcher` | research | Search, browsing, source synthesis, and research notes |
| `writer` | writing | Articles, documents, reports, and polished business writing |
| `data_analyst` | data | CSV, database, transformation, and reporting work |
| `web_scraper` | data | Browser automation, HTTP retrieval, OCR, and structured extraction |
| `communication` | communication | Email, messaging, and multi-platform communication workflows |
| `multimedia_creator` | creative | Image, audio, and media workflows |
| `office_document` | document | Mixed Word, Excel, PowerPoint, and formatted document tasks |
| `word_document` | document | Word documents, reports, proposals, manuals, and layouts |
| `excel_workbook` | document | Spreadsheet modeling, dashboards, formulas, and workbook generation |
| `powerpoint_presentation` | document | Slide decks, training material, and briefings |
| `pdf_processor` | document | PDF parsing, extraction, conversion, forms, and OCR-adjacent tasks |
| `computer_operator` | automation | Browser control, shell commands, file operations, and automation |
| `marketing` | marketing | Market research, campaign analysis, audience insights, and optimization planning |
