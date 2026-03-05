# AI REPL Integration (OpenAI and Anthropic)

Nex REPL can generate Nex code via OpenAI or Anthropic APIs.

## Requirements

Set API keys before starting the REPL:

- OpenAI: `OPENAI_API_KEY`
- Anthropic: `ANTHROPIC_API_KEY`

You can set one or both keys.

## Optional Environment Variables

- `NEX_AI_PROVIDER`  
  Default AI provider at REPL startup. Values: `openai`, `anthropic`.  
  If omitted, Nex auto-selects: `openai` if `OPENAI_API_KEY` is set, else `anthropic` if `ANTHROPIC_API_KEY` is set.

- `NEX_AI_MODEL`  
  Overrides model for both providers.

- `NEX_OPENAI_MODEL`  
  Default model when provider is `openai`.  
  Default fallback: `gpt-4o-mini`

- `NEX_ANTHROPIC_MODEL`  
  Default model when provider is `anthropic`.  
  Default fallback: `claude-3-5-sonnet-latest`

- `NEX_AI_TIMEOUT_SECONDS`  
  Request timeout for `:ai` calls (minimum honored value is 30).  
  Default: `180`

- `NEX_AI_MAX_REPAIRS`  
  Maximum number of repair retries after the first generated output fails parse/type validation.  
  Range: `0..8`, default: `3`

- `NEX_OPENAI_TIMEOUT_SECONDS` / `NEX_ANTHROPIC_TIMEOUT_SECONDS`  
  Provider-specific timeout override when `NEX_AI_TIMEOUT_SECONDS` is not set.

- `NEX_OPENAI_BASE_URL`  
  Override OpenAI base URL. Default: `https://api.openai.com/v1`

- `NEX_ANTHROPIC_BASE_URL`  
  Override Anthropic base URL. Default: `https://api.anthropic.com`

Example:

```bash
export OPENAI_API_KEY=...
export ANTHROPIC_API_KEY=...
export NEX_AI_PROVIDER=openai
export NEX_OPENAI_MODEL=gpt-4o-mini
export NEX_AI_TIMEOUT_SECONDS=240
```

## REPL Commands

- `:ai <prompt>`  
  Generate Nex code from your prompt. By default, generated code is executed.

- `:ai-provider`  
  Show active provider.

- `:ai-provider <openai|anthropic>`  
  Set active provider for current REPL session.

- `:ai-model`  
  Show active model.

- `:ai-model <model-name>`  
  Set active model for current REPL session.

- `:ai-dry`  
  Show dry-run status.

- `:ai-dry on|off`  
  When ON, generated code is shown but not executed.

## How It Works

1. REPL sends your prompt to the selected provider with a Nex-focused system prompt.
2. The system prompt is built dynamically from repository references, including:
   - `docs/SYNTAX.md`
   - selected `.nex` examples under `examples/`
3. REPL strips markdown fences if present.
4. REPL validates generated code:
   - parse validation always
   - typecheck validation when `:typecheck on`
5. If validation fails, REPL runs one repair pass with parse/type errors.
6. If valid:
   - prints generated code
   - executes it unless `:ai-dry on`

## Example Session

```text
nex> :ai-provider openai
AI provider set to: openai
AI model set to: gpt-4o-mini

nex> :typecheck on
Type checking enabled. Code will be validated before execution.

nex> :ai write a Stack class for Integer with push, pop and top
AI provider: openai
AI model: gpt-4o-mini
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

- `Missing OPENAI_API_KEY` / `Missing ANTHROPIC_API_KEY`  
  Export the required key before starting REPL.

- `request timed out`  
  Use a smaller prompt/model, or increase `NEX_AI_TIMEOUT_SECONDS`.

- `request failed (...)`  
  Check model name, account access, network, and base URL overrides.
