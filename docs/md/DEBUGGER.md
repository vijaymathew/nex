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
:break <spec> if <expr>
:tbreak <spec>
:tbreak <spec> if <expr>
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
- `field:status`
  Break when any assignment to `*.status` occurs (`obj.status := ...`).
- `Order#status`
  Break when assignment to `status` occurs while executing class `Order`.

Conditional breakpoint:

- `Class.method if x > 0`
- `file.nex:42 if this.count > 10`

`<expr>` is evaluated in the paused runtime context. If it evaluates to true, execution pauses.

List breakpoints:

```text
:breaks
```

Breakpoints are shown with stable IDs (for the current REPL session), for example:

```text
[1] OrderService.place:42
[2] OrderService.place if this.retry_count > 0
```

Clear one breakpoint:

```text
:clearbreak <id>
:clearbreak <spec>
```

Clear all:

```text
:clearbreak all
```

`clearbreak` supports all three forms:

- `:clearbreak <id>` removes one exact breakpoint ID.
- `:clearbreak <spec>` removes all matching breakpoints for that spec.
- `:clearbreak all` removes everything.

Enable/disable breakpoints without removing:

```text
:enable <id>
:enable all
:disable <id>
:disable all
```

Temporary breakpoints:

- `:tbreak ...` creates a one-shot breakpoint.
- It is removed automatically after the first hit.

## 2.1) Watchpoints

Watchpoints pause when a watched expression value changes.

```text
:watch <expr>
:watch <expr> if <expr>
:watches
:clearwatch <id|all>
:enablewatch <id|all>
:disablewatch <id|all>
```

`if <expr>` applies an additional condition for watch-trigger pauses.

Hit-frequency controls for breakpoints:

```text
:ignore <id> <n>   # skip first n hits
:every <id> <n>    # pause every n-th hit
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

Optional filters:

```text
:breakon exception on <substring>
:breakon exception filter <substring>
:breakon exception clear

:breakon contract on <pre|post|invariant>
:breakon contract filter <pre|post|invariant>
:breakon contract clear
```

## 4) Debug Prompt Commands

When a breakpoint is hit, prompt changes to `dbg>`.

Execution control:

- `:continue` or `:c` - Resume normal execution.
- `:step` or `:s` - Stop at next debuggable statement.
- `:next` or `:n` - Step over calls (pause at same depth or shallower).
- `:finish` or `:f` - Run until current frame returns.

Inspection:

- `:help` - Show debugger prompt command help.
- `:where` - Show current location and stack frames.
- `:frames` - Show stack frames with selected frame marker.
- `:frame <n>` - Select active frame (top is `0`).
- `:locals` - Show environment scopes and local variables.
- `:locals` groups current frame as `args`, `fields`, `locals`, and `special` (`this`, `result`).
- `:print <expr>` - Evaluate expression in currently selected frame context.

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

## 5.1) Scripted Debugger Input

You can preload debugger prompt commands from a file:

```text
:debugscript path/to/commands.dbg
:debugscript status
:debugscript off
```

Example `commands.dbg`:

```text
:where
:locals
:next
:continue
```

When loaded, debugger consumes these commands first (`dbg(script)> ...`) before interactive input.

## 5.2) Save/Load Breakpoint Sets

Persist breakpoints + watchpoints:

```text
:breaksave path/to/debug_state.edn
:breakload path/to/debug_state.edn
```

## 6) Notes and Current Limits

- Stepping is statement-level (not expression-level).
- `file:line` breakpoints are most useful for code loaded via `:load`.
- Hit-count breakpoints (for example `:break 12`) are per evaluation run, and reset on each new REPL execution.
- Breakpoints are in-memory for the REPL session; they are not persisted.
- `:print <expr>` evaluates in the paused runtime context and may have side effects.
- `:break <spec> if <expr>` conditions are expression checks, not assignment statements.
- `:where` prints source context when the current source is a file and line info is available.

## 7) Related Test Coverage

Debugger tests:

- `test/nex/repl_debugger_test.clj`
- `test/nex/repl_debugger_integration_test.clj`

Run full tests:

```bash
clojure -M:test test/scripts/run_tests.clj
```
