# Nex REPL Debugger Guide

This guide covers the interactive debugger available in the Nex CLI REPL.

## 1) Enable Debugger

Start REPL:

```bash
clojure -M:repl
```

Enable debugger:

```text
:debug on
```

Check status:

```text
:debug status
```

Disable debugger:

```text
:debug off
```

## 2) Breakpoints

Set a breakpoint:

```text
:break <spec>
```

Supported breakpoint specs:

- `12`  
  Statement hit-count breakpoint (within one REPL evaluation run).
- `Class.method`  
  Break on entry/statement activity in a specific method.
- `Class.method:42`  
  Break when current frame is `Class.method` and line is `42`.
- `file.nex:42` or `path/to/file.nex:42`  
  Break on a specific source line for loaded code.

List breakpoints:

```text
:breaks
```

Clear one breakpoint:

```text
:clearbreak <spec>
```

Clear all:

```text
:clearbreak all
```

## 3) Break-On Policies

Pause automatically on runtime failures:

```text
:breakon exception on
:breakon contract on
```

Disable:

```text
:breakon exception off
:breakon contract off
```

Show policy status:

```text
:breakon status
```

Contract mode pauses on `require` / `ensure` / invariant violations.

## 4) Debug Prompt Commands

When a breakpoint is hit, prompt changes to `dbg>`.

Execution control:

- `:continue` or `:c` - Resume normal execution.
- `:step` or `:s` - Stop at next debuggable statement.
- `:next` or `:n` - Step over calls (pause at same depth or shallower).
- `:finish` or `:f` - Run until current frame returns.

Inspection:

- `:where` - Show current location and stack frames.
- `:locals` - Show environment scopes and local variables.
- `:print <expr>` - Evaluate expression in current paused context.

## 5) Typical Workflow

```text
nex> :debug on
nex> :break OrderService.place:42
nex> :breakon contract on
nex> :load examples/order_service.nex
nex> run_order_flow()
dbg> :where
dbg> :locals
dbg> :print order.status
dbg> :next
dbg> :continue
```

## 6) Notes and Current Limits

- Stepping is statement-level (not expression-level).
- `file:line` breakpoints are most useful for code loaded via `:load`.
- Breakpoints are in-memory for the REPL session; they are not persisted.
- `:print <expr>` evaluates in the paused runtime context and may have side effects.

## 7) Related Test Coverage

Debugger tests:

- `test/nex/repl_debugger_test.clj`
- `test/nex/repl_debugger_integration_test.clj`

Run full tests:

```bash
clojure -M:test test/scripts/run_tests.clj
```

