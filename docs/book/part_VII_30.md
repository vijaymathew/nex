# Debugging Like an Engineer

Chapter 29 showed how tests discover failures. A failed test is evidence that something is wrong — that some input produces output inconsistent with the contract. Debugging begins where testing ends. Its task is to answer two questions: why does the failure occur, and how can it be corrected without introducing new failures?

These questions admit engineering answers. Debugging is not a search through a codebase for suspicious lines. It is a process of scientific reasoning: observe a failure, form a hypothesis about its cause, design a check that would distinguish the hypothesis from alternatives, execute the check, and revise the hypothesis based on the result. The process is iterative, but each iteration is directed rather than exploratory — each step produces information that narrows the space of possible causes.

The alternative to this discipline is not faster debugging. It is debugging that terminates without certainty — fixes that silence symptoms without addressing causes, changes that introduce new failures while correcting old ones, and a growing accumulation of "fixed" bugs that return in slightly different forms.


## The Six-Step Process

A reliable debugging process has six steps, and each one serves a purpose that the subsequent steps depend on.

**Step 1: Reproduce reliably.** A failure that cannot be reproduced cannot be diagnosed with confidence. If the failure appears intermittently — only under certain timing conditions, only with certain data configurations, only in certain environments — then any fix applied to an unreproduced failure is a guess. Reproducing the failure means: given a specific set of inputs and a specific system state, the failure occurs every time. Minimal reproduction means: finding the smallest input or state that still produces the failure, removing everything that is not necessary. Minimal cases make step 3 easier by narrowing the search space before hypothesis formation begins.

**Step 2: Localize the failure boundary.** The failure is observed at some point in the system — a test assertion fails, a user reports incorrect output, a monitoring alert fires. The cause may be much earlier — a state that was corrupted before the routine that produced the failure was called, or an incorrect assumption in a component that computed an intermediate result. Localization means finding the boundary at which correct behavior stops and incorrect behavior begins. Contracts help here: if a postcondition is violated, the cause is in the routine whose postcondition is violated. If an invariant is violated, the cause is in the last operation that modified the invariant's subject.

**Step 3: Form a hypothesis.** A hypothesis is a specific, testable claim about the cause. Not "something is wrong with the transition logic" but "the `complete` operation does not check whether the task is in `IN_TRANSIT` before setting status to `DELIVERED`." A hypothesis names a specific mechanism by which the observed failure could be produced. It must be specific enough that a single check can distinguish it from alternatives.

**Step 4: Run a targeted check.** Design and execute the minimum check that would produce different results if the hypothesis is correct versus incorrect. For the hypothesis above: call `complete` on a task whose status is `PENDING` and observe whether the status becomes `DELIVERED`. If it does, the hypothesis is confirmed. If it does not, the hypothesis is wrong and a new one must be formed.

**Step 5: Confirm the cause.** A confirmed hypothesis explains not just why the specific failing case fails but why the entire failure pattern occurs. If the hypothesis explains the reported failure but not the full pattern — if it explains why some tasks go directly to `DELIVERED` but not why others do not — the hypothesis is incomplete and must be refined before a fix is applied.

**Step 6: Patch and verify regression safety.** Apply the minimal change that corrects the cause. Minimal means: the change is no larger than the confirmed cause requires. Then run the full existing test suite and add a new test that would have caught the bug before the fix was applied. The new test is not optional — it is evidence that the cause has been corrected and that the same failure cannot recur without detection.


## Reproducibility as a Prerequisite

Every step after step 1 depends on the ability to execute the failure on demand. Without reliable reproduction, hypothesis formation is speculation — there is no way to confirm a hypothesis against a failure that may or may not appear when the check is executed. Without reliable reproduction, regression safety cannot be verified — the fix cannot be confirmed to have eliminated the failure rather than merely failed to trigger it in the verification run.

Reproducibility requires capturing three things: the exact inputs to the failing operation, the system state at the time of the call, and the discrepancy between the observed and expected outputs. The first two are what a minimal test case encodes. The third is the oracle — the statement of what correct behavior would look like, derived from the contract.

When a failure is not immediately reproducible — when it depends on timing, concurrency, or accumulated state — the path to reproducibility is to simplify the context until the variable factors are eliminated. Remove the concurrency and see if the failure persists. Replace the accumulated state with a constructed minimal state that produces the same failure. Reduce the inputs until the smallest failing case is found. Each simplification either preserves the failure — confirming that the removed factor is not the cause — or eliminates it — revealing that the removed factor is the cause.


## Contracts as Localization Tools

The most expensive part of debugging is often localization: finding the boundary between where behavior is correct and where it is not. In a system without explicit contracts, this boundary must be found by reading code and reasoning about what each piece should do. In a system with contracts, the boundary announces itself: a violated precondition identifies a caller that failed to satisfy its obligations; a violated postcondition identifies a routine that failed to satisfy its guarantees; a violated invariant identifies the operation that left the object in an inconsistent state.

The worked case in this chapter illustrates this. The bug report is: *"Some delivery tasks jump directly from `PENDING` to `DELIVERED`."* In a system without transition contracts, debugging this requires tracing every code path that sets the `status` field to `"DELIVERED"` and checking whether each one verifies the prior state. There may be many such paths, and the one that skips the check may be obscure.

In a system where `complete` has a precondition `status = "IN_TRANSIT"`, the debugging path is different. The bug indicates that `complete` is being called on tasks that are not `IN_TRANSIT`. The precondition should have prevented this; if it did not, either the precondition is missing or the code does not enforce it. Checking whether the precondition is present and enforced is the targeted check for the most likely hypothesis — and if the precondition is absent, the fix is precisely to add it.


## From Bug Report to Fix

Consider the report:

> *"Some delivery tasks jump directly from `PENDING` to `DELIVERED`."*

**Step 1: Reproduce.** Construct a `Delivery_Task` with status `PENDING`. Call `complete` directly. Observe the status becomes `DELIVERED`. This is the minimal reproducible case.

**Step 2: Localize.** The `complete` operation sets status to `DELIVERED`. The status transitions from `PENDING` to `DELIVERED` in a single call. The boundary is the entry to `complete` — the failure is not in what `complete` does after the check, but in what it fails to check before acting.

**Step 3: Hypothesize.** `complete` does not verify that the task is `IN_TRANSIT` before setting status to `DELIVERED`. The precondition `status = "IN_TRANSIT"` is absent or unenforced.

**Step 4: Check.** Inspect the implementation of `complete`. Confirm that the `require` clause is absent. Call `complete` on a `PENDING` task and observe that it succeeds without error. The hypothesis is confirmed.

**Step 5: Confirm.** The missing precondition explains the full failure pattern: any call to `complete` from a non-`IN_TRANSIT` state will succeed, not just calls from `PENDING`. The `FAILED` state has the same vulnerability.

**Step 6: Patch.** Add the precondition `in_transit: status = "IN_TRANSIT"` to `complete`. Add a regression test that attempts to call `complete` on a `PENDING` task and verifies that it is rejected. Run the full existing test suite to confirm no regression.


## A Patched Contract in Code

```nex
class Delivery_Task
create
  make_pending() do
    status := "PENDING"
  end
feature
  status: String

  start()
    require
      can_start: status = "PENDING" or status = "FAILED"
    do
      status := "IN_TRANSIT"
    ensure
      now_in_transit: status = "IN_TRANSIT"
    end

  complete()
    require
      in_transit: status = "IN_TRANSIT"
    do
      status := "DELIVERED"
    ensure
      delivered: status = "DELIVERED"
    end
invariant
  valid_status:
    status = "PENDING" or
    status = "IN_TRANSIT" or
    status = "DELIVERED" or
    status = "FAILED"
end

class Debug_Smoke_Test
feature
  run(): String
    do
      let t: Delivery_Task := 
	   create Delivery_Task.make_pending
      t.start
      t.complete

      if t.status = "DELIVERED" then
        result := "PASS"
      else
        result := "FAIL"
      end
    ensure
      known_result: result = "PASS" or result = "FAIL"
    end
end
```

The `complete` operation now carries the precondition `in_transit: status = "IN_TRANSIT"`. This precondition is the fix — not a conditional inside the body that handles the error case gracefully, but a boundary condition that rejects the call before any state changes occur. A caller that attempts to complete a `PENDING` task violates the precondition and receives a contract failure immediately, at the point of the illegal call, rather than producing corrupted state that will be discovered later.

`Debug_Smoke_Test.run` exercises the legal transition sequence: `PENDING → IN_TRANSIT → DELIVERED` via `start` followed by `complete`. The test confirms that the corrected `complete` still works correctly on valid inputs. It is not the regression test for the bug — that test would attempt to call `complete` from `PENDING` and confirm it is rejected — but it is evidence that the fix did not break the operation for the inputs it was designed to handle.

The `start` operation also has a precondition: `status = "PENDING" or status = "FAILED"`. This is the transition contract from Chapter 10's model: the complete legal transition graph, encoded as preconditions on each operation. The bug exposed by the failing report was a gap in this graph — `complete` without its precondition allowed a transition the model declared illegal. The fix closes the gap.


## Debugging in the Three Systems

In the delivery system, the most common class of debugging problem is transition violations — operations called from illegal states — and the contract-based localization described in this chapter applies directly. A robot that is dispatched before it is ready, a task that is marked delivered before it was started: these failures are announced by violated preconditions when contracts are in place, and diagnosed by tracing unguarded field mutations when they are not.

In the knowledge engine, the most common debugging challenges are non-determinism — the same query producing different results on consecutive calls — and index staleness — queries that return results inconsistent with the current document collection. Non-determinism is diagnosed by finding the hidden state that varies between calls; contracts that require deterministic outputs make the violation detectable. Index staleness is diagnosed by comparing the index's last-updated timestamp against the collection's last-modified timestamp; an invariant that requires these to agree within a defined tolerance makes the discrepancy checkable.

In the virtual world, the most common debugging challenges are non-deterministic update ordering — two runs of the same scenario producing different outcomes — and invariant violations — entities ending a tick in states that violate the world's rules. Non-determinism is diagnosed by finding the operation whose output depends on execution order; an invariant that requires deterministic tick output makes the violation detectable. Invariant violations are diagnosed by checking the world state after each tick against the declared invariants; violations are caught at the tick boundary rather than at the point where incorrect state later produces incorrect behavior.

In all three systems, contracts and invariants are the primary localization tools. They convert debugging from a search through an unknown codebase into a directed investigation of a known boundary violation.


## Three Ways Debugging Goes Wrong

**Patching before reproducing.** A change applied to an unreproduced failure may correct the behavior in the cases that were visible while leaving the cause unchanged. The failure returns in a slightly different form, or under slightly different conditions, or with a slightly different symptom. The cycle repeats. Each patch narrows the visible failure space without narrowing the actual cause. Reproducibility is not a courtesy — it is the prerequisite for a fix that addresses the cause.

**Multiple simultaneous changes.** When two hypotheses are plausible, the temptation is to apply fixes for both simultaneously. If the failure disappears, the combined fix works — but it is not known which half was necessary, which half was unnecessary, and whether the unnecessary half introduced a regression. Each hypothesis must be tested independently, which means each change must be applied independently. The process is slower per hypothesis but faster overall, because the cause is known when the fix is confirmed.

**No regression safety net.** A fix without a regression test is a fix without evidence of permanence. The same bug can be reintroduced by a future change that does not know the previous fix was needed. The regression test is the record of the bug — its existence says: a failure at this boundary was found here, was fixed here, and must be detected here if it recurs. A codebase whose bug fixes are systematically accompanied by regression tests is a codebase that becomes harder to break over time, rather than one that maintains a constant defect rate despite continuous repair.


## Quick Exercise

Take one recent bug — a failure that was observed, diagnosed, and fixed — and reconstruct the debugging process in six parts: the minimal reproducible case, the localized failure boundary, the specific hypothesis that was formed, the targeted check that confirmed it, the patch that was applied, and the regression test that was added.

If any step is missing from the reconstruction — if the fix was applied without a confirmed hypothesis, or without a regression test — identify what evidence that step would have provided and what risk its absence created. The reconstruction is not retrospective documentation; it is a test of whether the debugging process that was used was engineering-grade or trial-and-error.


## Takeaways

- Debugging is hypothesis-driven engineering. The six steps — reproduce, localize, hypothesize, check, confirm, patch — are not formalities. Each one provides evidence that the subsequent steps depend on.
- Reproducibility is a prerequisite, not a convenience. A fix applied to an unreproduced failure is a guess, not an engineering decision.
- Contracts and invariants are localization tools. A violated precondition identifies the caller; a violated postcondition identifies the routine; a violated invariant identifies the operation. They convert debugging from search to investigation.
- One hypothesis, one change, one check. Multiple simultaneous changes produce results that cannot be interpreted. Each hypothesis must be tested independently.
- Every fix needs a regression test. The test is the record of the bug — the evidence that the same failure cannot recur without detection.


*Part VII has established the complete trustworthiness toolkit: preconditions and postconditions that define behavioral contracts, invariants that enforce object-level consistency, tests that provide evidence that contracts hold, and debugging that addresses causes rather than symptoms. These are the practices that make software dependable not just at completion but over the full arc of its evolution.*
