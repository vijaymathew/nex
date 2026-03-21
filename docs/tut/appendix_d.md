# The Debugger

Nex includes an interactive debugger in the CLI REPL. This appendix condenses the main commands from `docs/md/DEBUGGER.md` into a tutorial-oriented quick reference.


## Starting the Debugger

Start the REPL:

```bash
clojure -M:repl
```

Enable debugging:

```text
:debug on
```

Check status:

```text
:debug status
```

Disable:

```text
:debug off
```


## Breakpoints

Create breakpoints:

```text
:break <spec>
:break <spec> if <expr>
:tbreak <spec>
```

Common breakpoint forms:

- `Class.method`
- `Class.method:42`
- `file.nex:42`
- `field:status`
- `Order#status`

List breakpoints:

```text
:breaks
```

Remove them:

```text
:clearbreak <id>
:clearbreak <spec>
:clearbreak all
```

Enable or disable without removing:

```text
:enable <id>
:disable <id>
```


## Watchpoints

Watchpoints pause when an expression's value changes.

```text
:watch <expr>
:watch <expr> if <expr>
:watches
:clearwatch <id|all>
:enablewatch <id|all>
:disablewatch <id|all>
```

Useful for fields or derived state that change across a long execution.


## Break-On Policies

Pause automatically on failures:

```text
:breakon exception on
:breakon contract on
```

Show status:

```text
:breakon status
```

Filters:

```text
:breakon exception on <substring>
:breakon contract on <pre|post|invariant>
```

These are particularly helpful in a contract-heavy language because they let you stop exactly when a precondition, postcondition, or invariant fails.


## Debug Prompt Commands

When execution pauses, the prompt changes to `dbg>`.

Execution control:

- `:continue` or `:c`
- `:step` or `:s`
- `:next` or `:n`
- `:finish` or `:f`

Inspection:

- `:where`
- `:frames`
- `:frame <n>`
- `:locals`
- `:print <expr>`

The most useful first commands at a breakpoint are usually:

1. `:where`
2. `:locals`
3. `:print <expr>`


## Typical Workflow

```text
nex> :debug on
nex> :break Wallet.spend
nex> :breakon contract on
nex> :load examples/wallet.nex
nex> run_wallet_demo()
"dbg> :where"
"dbg> :locals"
"dbg> :print money"
"dbg> :next"
"dbg> :continue"
```

This is enough for most day-to-day debugging:

- stop at a routine
- inspect the active frame
- step through the logic
- continue once the cause is understood


## Hit-Frequency Controls

Breakpoints can be tuned:

```text
:ignore <id> <n>
:every <id> <n>
```

Use these when a loop or frequently called routine hits too often to inspect comfortably.


## Saving and Scripting Debug State

Persist breakpoints and watchpoints:

```text
:breaksave path/to/debug_state.edn
:breakload path/to/debug_state.edn
```

Drive the debugger from a command file:

```text
:debugscript path/to/commands.dbg
:debugscript status
:debugscript off
```


## Limits to Remember

- Stepping is statement-level, not expression-level.
- `file:line` breakpoints are most useful for code loaded from files.
- Breakpoints are session-local unless saved.
- `:print <expr>` runs in the paused context and may have side effects.


## Worked Session

Suppose `Wallet.spend` has a precondition and an invariant:

```nex
class Wallet
  feature
    money: Real

    spend(amount: Real)
      require
        enough: amount <= money
      do
        money := money - amount
      ensure
        decreased: money = old money - amount
      end

  invariant
    non_negative: money >= 0.0
end
```

If you enable:

```text
:debug on
:breakon contract on
```

and then violate the contract, the debugger will stop at the failure point. At that moment:

- `:where` shows the current routine and source location
- `:locals` shows `amount`, `money`, and any locals
- `:print amount <= money` checks the failing condition directly

This is often faster than reading the whole routine from the top.


## Further Reading

For the complete command set and current implementation notes, see `docs/md/DEBUGGER.md`.
