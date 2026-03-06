# Algorithm Lab — The First Experiments

## Subtitle

Testing algorithm ideas on small systems before formal complexity notation.

## 1. Why This Lab Exists

Part III introduced algorithm thinking:

- define guarantees
- decompose work
- use recursion when structure fits
- reason about behavior under growth

Now we run controlled experiments.

The goal is not to chase micro-optimizations. The goal is to build engineering intuition by observing how different algorithm choices behave on the same task.

---

## 2. Lab Objectives

By the end of this lab, you should be able to:

- compare two algorithms for the same requirement
- collect simple evidence (operation counts, not just wall-clock time)
- explain when an approach is acceptable and when it breaks down
- preserve correctness contracts while changing strategy

---

## 3. Experiment Theme

Use one task, multiple strategies:

> “Find a target value in a sorted sequence.”

Why this task:

- easy correctness criteria
- easy to vary input size
- clear contrast between algorithm behaviors

Strategies:

- linear scan
- binary search

We will track an explicit step counter to observe behavior directly.

---

## 4. Nex Implementation

Suggested file names:

- `algorithm_lab_1.nex`
- `algorithm_lab_1_main.nex`

If using the web IDE, place everything in one file and run `App.run`.

### 4.1 Result Type

```nex
class Search_Result
feature
  found: Boolean
  index: Integer
  steps: Integer
invariant
  index_valid: index >= -1
  steps_non_negative: steps >= 0
end
```

### 4.2 Linear Search With Step Counter

```nex
class Linear_Search_Algo
feature
  find(a1, a2, a3, a4, a5, a6, a7, a8, target: Integer): Search_Result
    do
      let r: Search_Result := create Search_Result
      r.found := false
      r.index := -1
      r.steps := 0

      r.steps := r.steps + 1
      if a1 = target then
        r.found := true
        r.index := 0
      elseif a2 = target then
        r.steps := r.steps + 1
        r.found := true
        r.index := 1
      elseif a3 = target then
        r.steps := r.steps + 1
        r.found := true
        r.index := 2
      elseif a4 = target then
        r.steps := r.steps + 1
        r.found := true
        r.index := 3
      elseif a5 = target then
        r.steps := r.steps + 1
        r.found := true
        r.index := 4
      elseif a6 = target then
        r.steps := r.steps + 1
        r.found := true
        r.index := 5
      elseif a7 = target then
        r.steps := r.steps + 1
        r.found := true
        r.index := 6
      elseif a8 = target then
        r.steps := r.steps + 1
        r.found := true
        r.index := 7
      else
        r.steps := r.steps + 1
      end

      result := r
    ensure
      steps_recorded: result.steps >= 1
      valid_index: result.index >= -1 and result.index <= 7
    end
end
```

### 4.3 Binary Search With Step Counter

```nex
class Binary_Search_Algo
feature
  find(a1, a2, a3, a4, a5, a6, a7, a8, target: Integer): Search_Result
    require
      sorted_input:
        a1 <= a2 and a2 <= a3 and a3 <= a4 and
        a4 <= a5 and a5 <= a6 and a6 <= a7 and a7 <= a8
    do
      let r: Search_Result := create Search_Result
      r.found := false
      r.index := -1
      if a4 = target then
        r.steps := 1
        r.found := true
        r.index := 3
      elseif target < a4 and a2 = target then
        r.steps := 2
        r.found := true
        r.index := 1
      elseif target < a2 and a1 = target then
        r.steps := 3
        r.found := true
        r.index := 0
      elseif target > a2 and target < a4 and a3 = target then
        r.steps := 3
        r.found := true
        r.index := 2
      elseif target > a4 and a6 = target then
        r.steps := 2
        r.found := true
        r.index := 5
      elseif target > a4 and target < a6 and a5 = target then
        r.steps := 3
        r.found := true
        r.index := 4
      elseif target > a6 and a7 = target then
        r.steps := 3
        r.found := true
        r.index := 6
      elseif target > a7 and a8 = target then
        r.steps := 4
        r.found := true
        r.index := 7
      else
        r.steps := 4
      end

      result := r
    ensure
      steps_bounded: result.steps >= 1 and result.steps <= 4
      valid_index: result.index >= -1 and result.index <= 7
    end
end
```

### 4.4 Driver: Compare Behaviors

```nex
class App
feature
  run() do
    let lin: Linear_Search_Algo := create Linear_Search_Algo
    let bin: Binary_Search_Algo := create Binary_Search_Algo

    let l_hit: Search_Result := lin.find(3, 7, 10, 14, 19, 21, 25, 30, 30)
    let b_hit: Search_Result := bin.find(3, 7, 10, 14, 19, 21, 25, 30, 30)

    let l_miss: Search_Result := lin.find(3, 7, 10, 14, 19, 21, 25, 30, 11)
    let b_miss: Search_Result := bin.find(3, 7, 10, 14, 19, 21, 25, 30, 11)

    print("Linear hit steps: " + l_hit.steps)
    print("Binary hit steps: " + b_hit.steps)
    print("Linear miss steps: " + l_miss.steps)
    print("Binary miss steps: " + b_miss.steps)
  end
end
```

Expected pattern:

- both algorithms are correct
- binary search usually uses fewer steps on sorted inputs
- binary search depends on stronger preconditions (`sorted_input`)

---

## 5. Lab Tasks

### Task A — Correctness Check

Run both strategies with:

- first element target
- middle element target
- last element target
- absent target

Record:

- found/not-found
- returned index
- steps

### Task B — Constraint Violation

Call binary search with unsorted inputs and observe contract behavior.

Question:

- what failure signal do you get?
- how is this better than silent wrong results?

### Task C — Extension

Add one more strategy for comparison:

- jump search, or
- interpolation-inspired search (if values are uniform)

Keep the same `Search_Result` format so results remain comparable.

---

## 6. Reflection Prompts

Use evidence from your run logs:

1. Which strategy has stricter input assumptions?
2. Which strategy is easier to reason about for correctness?
3. At what scale would you stop using linear search for this task?
4. Which contract prevented the most dangerous misuse?

---

## 7. Deliverables

- runnable Nex code for at least two strategies
- output log showing hit/miss comparisons
- short write-up of strategy tradeoffs
- one paragraph on how contracts influenced your design

---

## 8. Forward Link

This lab gives you concrete intuition for why data structures matter.

In Part IV, we study lists, sets/maps, trees, and graphs so algorithm choices are supported by the right representation.
