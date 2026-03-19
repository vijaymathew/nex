# Exceptions and Contracts Compiler Plan

This note defines the next compiler milestone for the experimental JVM bytecode backend:

1. exception handling
2. design by contract
3. only then the remaining control-flow gaps built on top of those mechanisms

That order is deliberate. Loop invariants/variants and scoped `do ... rescue ... end` should not be implemented first, because they depend on the same control-transfer and failure machinery as `raise`, `rescue`, `retry`, `require`, and `ensure`.

## Phase 1 Scope

Implement on the compiled path:

- `raise`
- scoped `do ... rescue ... end`
- `retry`
- method/constructor `require`
- method/constructor `ensure`

This phase should be enough to support:

- try/catch-style lowering for ordinary scoped rescue blocks
- contract failure signaling through the same exception path
- `retry` of the nearest compiled rescue boundary

## Explicit Non-Goals

Do not include in this phase:

- `old` capture
- class invariants
- loop invariants / variants
- `select`
- async/task exception semantics
- source-level checked/typed exception hierarchies
- JVM file compilation outside the REPL path

Those should come later, after the basic exception/contract pipeline is stable.

## Runtime Model

Add a small compiled-runtime exception model instead of inventing one per feature.

### Runtime classes/helpers

Add to `src/nex/compiler/jvm/runtime.clj`:

- `raise-compiled!`
- `contract-failure!`
- `make-retry-signal`
- `retry-signal?`

Back them with small JVM-visible exception classes:

- `nex.compiler.jvm.runtime.NexRaise`
- `nex.compiler.jvm.runtime.NexRetry`
- optionally `NexContractViolation` as a specialized subclass

Required properties:

- `raise` throws a real JVM throwable
- `retry` throws a distinct internal throwable used only for restart
- contract failures go through a stable helper with readable messages

## IR Additions

Extend `src/nex/ir.cljc` with:

- `:raise`
- `:try`
- `:retry`
- `:assert`

Suggested shapes:

```clojure
{:op :raise
 :expr ...}

{:op :retry}

{:op :assert
 :kind :require|:ensure
 :label "positive"
 :expr ...
 :message "Precondition violation: positive"}

{:op :try
 :body [...]
 :rescue [...]
 :retry-label "retry_0001"}
```

Notes:

- `:assert` should lower to boolean test + runtime failure helper
- `:try` should be statement-oriented, not expression-oriented, in phase 1
- `:retry` is only valid inside the dynamic scope of an enclosing `:try`

## Lowering Work

### Step 1: scoped rescue

In `src/nex/lower.cljc`:

- stop rejecting `:scoped-block` with `:rescue`
- lower it to `:try`
- lower the rescue body as a scoped statement block
- thread a `:retry-target` marker through the lowering env

Add to the lowering env:

- `:retry-depth`
- `:retry-target?`

### Step 2: raise

Lower:

```nex
raise expr
```

to:

```clojure
{:op :raise :expr lowered-expr}
```

### Step 3: retry

Lower:

```nex
retry
```

only when the current env is inside a retry-capable `:try`.

Outside that scope:

- lowering should fail fast

### Step 4: require / ensure

For methods and constructors:

- lower `require` assertions into leading `:assert` IR nodes
- lower `ensure` assertions into trailing `:assert` IR nodes

For phase 1:

- no `old`
- postconditions may only refer to current locals/`result`, as already supported by the interpreter/typechecker

## Emitter Work

In `src/nex/compiler/jvm/emit.clj`:

### Step 1: `:raise`

Emit:

- evaluation of the raised value
- boxing if needed
- static call to runtime helper that throws

### Step 2: `:assert`

Emit:

- boolean test
- branch around failure helper on success
- runtime contract failure helper on failure

### Step 3: `:try`

Emit JVM try/catch using ASM labels:

- one catch for `NexRetry`
- one catch for raised/contract exceptions as needed

Suggested structure:

1. start label
2. body
3. jump to end
4. rescue label
5. rescue body
6. end label

Retry behavior:

- catch `NexRetry`
- jump back to body start label

### Step 4: `:retry`

Emit static runtime helper that throws `NexRetry`

## Typechecker / Eligibility Work

The typechecker already knows these language constructs semantically. The compiled REPL gate must be widened to match.

In `src/nex/compiler/jvm/repl.clj`:

- treat `:raise` as eligible when its expression is eligible
- treat scoped `:scoped-block` with `:rescue` as eligible
- treat `:retry` as eligible only when the batch context is inside a retry-capable block

Add batch-context tracking for retry legality.

## Tests

Add coverage in:

- `test/nex/compiler/jvm/lower_test.clj`
- `test/nex/compiler/jvm/emit_test.clj`
- `test/nex/compiler/jvm/class_smoke_test.clj`
- a new file if needed:
  - `test/nex/compiler/jvm/contracts_smoke_test.clj`

### Acceptance tests

1. `raise`

```nex
raise "boom"
```

compiled path throws the compiled runtime exception

2. scoped rescue

```nex
do
  raise "boom"
rescue
  print("rescued")
end
```

prints `rescued`

3. retry

```nex
let x := 0
do
  x := x + 1
  if x < 3 then
    retry
  end
rescue
end
x
```

returns `3`

4. precondition

```nex
function f(x: Integer): Integer
  require
    positive: x > 0
  do
    result := x
  end
```

compiled call with `0` raises a contract violation

5. postcondition

```nex
function f(x: Integer): Integer
  do
    result := x + 1
  ensure
    positive: result > 0
  end
```

compiled call enforces the postcondition

## Phase 2 Follow-up

After this phase is stable, implement:

1. `old`
2. class invariants
3. loop invariants / variants
4. loop-contract violation messages through the same contract runtime

That is the correct point to return to the remaining control-flow gaps.
