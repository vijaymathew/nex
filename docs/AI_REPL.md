# AI REPL Integration (Local Ollama)

Nex REPL can generate Nex code using a local Ollama model, without fine-tuning.

## Requirements

1. Install and run Ollama locally.
2. Pull at least one model (example):

```bash
ollama pull qwen2.5-coder:7b
```

3. Start Ollama server (if not already running):

```bash
ollama serve
```

Default endpoint used by Nex REPL: `http://localhost:11434`.

## Optional Environment Variables

- `NEX_OLLAMA_MODEL`  
  Default model used by `:ai` if `:ai-model` was not set in REPL.  
  Default fallback: `qwen2.5-coder:7b`

- `NEX_OLLAMA_HOST`  
  Override Ollama host URL.  
  Default: `http://localhost:11434`

- `NEX_OLLAMA_TIMEOUT_SECONDS`  
  Request timeout for `:ai` calls (minimum honored value is 30).  
  Default: `180`

Example:

```bash
export NEX_OLLAMA_MODEL=qwen2.5-coder:7b
export NEX_OLLAMA_HOST=http://localhost:11434
export NEX_OLLAMA_TIMEOUT_SECONDS=240
```

## REPL Commands

- `:ai <prompt>`  
  Generate Nex code from your prompt. By default, generated code is executed.

- `:ai-model`  
  Show active model.

- `:ai-model <model-name>`  
  Set active model for the current REPL session.

- `:ai-dry`  
  Show dry-run status.

- `:ai-dry on|off`  
  When ON, generated code is shown but not executed.

## How It Works

1. REPL sends your prompt to Ollama `POST /api/generate` with a Nex-focused system prompt.
   The system prompt is built dynamically from repository references, including:
   - `docs/SYNTAX.md`
   - selected `.nex` examples under `examples/`
2. REPL strips markdown fences if present.
3. REPL validates generated code:
   - parse validation always
   - typecheck validation when `:typecheck on`
4. If validation fails, REPL runs one repair pass by giving the parse/type errors back to the model.
5. If valid:
   - prints generated code
   - executes it unless `:ai-dry on`

## Example Session

```text
nex> :ai-model qwen2.5-coder:7b
AI model set to: qwen2.5-coder:7b

nex> :typecheck on
Type checking enabled. Code will be validated before execution.

nex> :ai write a Stack class for Integer with push, pop and top
AI model: qwen2.5-coder:7b
Generating Nex code...
Generated code:
-----
class Stack
  ...
end
-----
Class(es) registered: Stack
```

## Troubleshooting

- `Cannot connect to Ollama...`  
  Start Ollama (`ollama serve`) and confirm host/port.

- `model not found` / request failure  
  Pull model first (`ollama pull <name>`) and retry.

- Generated code fails repeatedly  
  Use a more constrained prompt (explicit class names, field types, method signatures), or enable `:typecheck on` and keep prompts small.
