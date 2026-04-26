# The Debugger

Nex includes an interactive debugger in the CLI REPL. This appendix condenses the main commands from `docs/md/DEBUGGER.md` into a tutorial-oriented quick reference.


## Starting the Debugger

Start the REPL:

```bash
nex
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

For code defined as standalone functions rather than class methods, use either:

- a `file.nex:line` breakpoint when the function comes from a loaded file
- a hit-count breakpoint such as `:break 12` during one REPL evaluation run


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


## Example Sessions

The quickest way to learn the debugger is to see a few realistic sessions.

### Session 0: Debug A Standalone Function In The REPL

Standalone functions do not currently have a `:break function_name` form. In REPL work, the practical approach is to use a hit-count breakpoint and then step once execution pauses.

Suppose you define:

```nex
function square_plus_one(n: Integer): Integer
do
  let squared: Integer := n * n
  result := squared + 1
end
```

Then a simple debugging session looks like:

```text
nex> :debug on
nex> :break 2
nex> print(square_plus_one(4))
dbg> :where
dbg> :locals
dbg> :print n
dbg> :next
dbg> :locals
dbg> :continue
```

The important point here is:

- hit-count breakpoints count debuggable statements in one evaluation run
- they are useful in the REPL when there is no file-based line location to target
- once paused, the normal debugger commands work the same way as for methods

### Session 1: Stop At A Routine And Step Through It

Suppose you define:

```nex
class Counter
  create
    make() do
      total := 0
    end

  feature
    total: Integer

    add(n: Integer) do
      let old_total: Integer := total
      total := old_total + n
      let new_total: Integer := total
    end
end
```

Then a simple stepping session looks like:

```text
nex> :debug on
nex> :break Counter.add
nex> let c := create Counter.make
nex> c.add(5)
dbg> :where
dbg> :locals
dbg> :print total
dbg> :next
dbg> :locals
dbg> :next
dbg> :print total
dbg> :continue
```

What this tells you:

- `:where` confirms that you stopped in `Counter.add`
- `:locals` shows the argument `n` and the current `this`
- `:print total` before `:next` shows the current field binding
- the first `:next` advances to the next statement in the routine
- `:locals` then shows `old_total` before the assignment is applied
- the second `:next` advances past the assignment
- `:print total` now shows the updated field value

One subtle point: inside a paused method, bare field names such as `total` refer to the live field bindings in the method environment. By contrast, `this.total` reads from the object value itself, which is not rebuilt until the routine returns unless you explicitly assign through `this.field := ...`.

This is the basic "what changed on this line?" workflow.

### Session 2: Stop On A Contract Failure

Suppose `Wallet.spend` has a precondition and an invariant:

```nex
class Wallet
  create
    make(initial_money: Real) do
      money := initial_money
    end

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

Now enable contract breaks and trigger a failure:

```text
nex> :debug on
nex> :breakon contract on
nex> let w := create Wallet.make(10.0)
nex> w.spend(25.0)
dbg> :where
dbg> :locals
dbg> :print w.money
dbg> :print 25.0 <= w.money
```

This is a good pattern when you already know the failure is a contract problem and want to confirm the caller-side state:

- `:where` shows the paused call site
- `:locals` shows the caller bindings that led to the failure
- `:print w.money` lets you inspect the receiver state directly
- `:print 25.0 <= w.money` lets you test the failing condition from the caller context

One current limitation is important here: for a precondition failure raised by a call such as `w.spend(25.0)`, the debugger pauses at the caller context, not inside `Wallet.spend`. That means names such as `amount` are not available at this pause point.

If you need to inspect callee-side names before the precondition fails, combine contract breaking with a normal method breakpoint:

```text
nex> :debug on
nex> :break Wallet.spend
nex> :breakon contract on
nex> let w := create Wallet.make(100.0)
nex> w.spend(25.0)
dbg> :print amount
dbg> :print money
dbg> :continue
```

This lets you inspect the method arguments and field bindings at routine entry, before the precondition violation is reported.

### Session 3: Watch A Value Across Several Calls

Watchpoints are useful when the suspicious state changes gradually rather than at a single obvious line.

Using the same `Counter` class:

```text
nex> :debug on
nex> let c := create Counter.make
nex> :watch c.total
nex> c.add(2)
dbg> :print c.total
dbg> :continue
nex> c.add(3)
dbg> :print c.total
dbg> :continue
nex> :watches
```

This is useful when:

- one field changes in many places
- you care about the moment its value changes
- setting many separate breakpoints would be noisy

### Session 4: Inspect The Stack And Move Between Frames

When one routine calls another, the most useful question is often not "where am I?" but "who called me, and with what values?"

Suppose:

```nex
class Pricing
  feature
    discount(price: Real): Real do
      result := price * 0.9
    end

    checkout(subtotal: Real): Real do
      result := this.discount(subtotal)
    end
end
```

Then a stack-oriented session looks like:

```text
nex> :debug on
nex> :break Pricing.discount
nex> let p := create Pricing
nex> print(p.checkout(80.0))
dbg> :frames
dbg> :locals
dbg> :frame 1
dbg> :locals
dbg> :print subtotal
dbg> :frame 0
dbg> :print price
dbg> :finish
```

Here:

- frame `0` is the current routine
- frame `1` is its caller
- `:frame <n>` changes which context `:locals` and `:print` use
- `:finish` is often faster than repeated `:next` when you only care about the return from the current routine

These four sessions cover most day-to-day debugging:

- stop at a routine
- inspect the active frame
- step through state changes
- stop automatically on contract failures
- watch one changing value
- move up and down the call stack

### Session 5: Break At A Specific File Line

For code loaded from a file, line breakpoints are useful when execution is still associated with that file's source location.

Suppose `examples/math_demo.nex` contains:

```nex
function adjust(n: Integer): Integer
do
  let doubled: Integer := n * 2
  result := doubled + 3
end
```

Then you can break by line number while evaluating that file, as long as the line is part of code that is actually executed during `:load`:

```text
nex> :debug on
nex> :break examples/math_demo.nex:3
nex> :load examples/math_demo.nex
dbg> :where
dbg> :locals
dbg> :print doubled
dbg> :continue
```

Practical notes for `file.nex:line` breakpoints:

- they are most useful for code loaded from files, not ad hoc multi-line REPL input
- the path must match the debugger's recorded source path for the loaded code
- `:load some_file.nex` executes top-level statements, but it does not execute function bodies just because they are defined in the file
- so a breakpoint on a line inside a function body will not fire during `:load` unless the file itself also calls that function while loading
- for a standalone function defined in a file and then called later from the REPL, a `file.nex:line` breakpoint may not fire, because the current debug source can be the REPL call site rather than the original defining file
- use `:where` after a pause if you need to confirm the source path and line shape the debugger sees


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
- Standalone functions do not currently have a dedicated named breakpoint form such as `:break foo`; use `file:line` or a hit-count breakpoint instead.
- Breakpoints are session-local unless saved.
- `:print <expr>` runs in the paused context and may have side effects.


## Further Reading

For the complete command set and current implementation notes, see `docs/md/DEBUGGER.md`.
