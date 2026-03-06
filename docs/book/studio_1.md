# Studio 1 — Our First Tiny System

## Subtitle

Building the smallest working versions of all three systems.

## 1. The Situation

The team has finished Part I and has problem statements, example scenarios, and edge-case notes.
Now they must ship a tiny end-to-end version of each system to test whether the problem framing is actually usable.

Systems in scope:

* delivery scheduler (single robot, tiny map)
* note organizer (small collection, simple lookup)
* virtual world (few entities, deterministic update loop)

## 2. Engineering Brief

Build minimal vertical slices that run from input to output.

Required outcomes:

* define a minimal model for each system
* implement one core operation per system
* encode at least one explicit contract per operation
* demonstrate behavior on nominal and edge inputs

Implementation guidance:

* prefer clarity over generality
* keep architecture deliberately small
* document assumptions that might break at scale

### Implementation In Nex

Use Nex for the studio implementation so contracts, assumptions, and behavior are all visible in one place.

Suggested file split:

* `delivery_tiny.nex`
* `notes_tiny.nex`
* `world_tiny.nex`
* `studio_1_main.nex`

If you are using the web IDE, you can also place everything in one file and run `App.run`.

#### Delivery Tiny (single robot, tiny map)

```nex
class Delivery_Tiny
feature
  next_stop(current, destination: String): String
    require
      current_non_empty: current /= ""
      destination_non_empty: destination /= ""
    do
      if current = destination then
        result := current
      elseif current = "A" and destination = "C" then
        result := "B"
      elseif current = "B" and destination = "C" then
        result := "C"
      else
        result := "UNREACHABLE"
      end
    ensure
      decision_returned: result /= ""
    end
end
```

#### Notes Tiny (simple lookup)

```nex
class Notes_Tiny
feature
  find_by_tag(tag: String): String
    require
      tag_non_empty: tag /= ""
    do
      if tag = "algorithms" then
        result := "note_001"
      elseif tag = "graphs" then
        result := "note_002"
      else
        result := "NOT_FOUND"
      end
    ensure
      response_non_empty: result /= ""
    end
end
```

#### World Tiny (deterministic update step)

```nex
class World_Tiny
feature
  step(position, velocity, max_x: Integer): Integer
    require
      max_positive: max_x > 0
    do
      let next: Integer := position + velocity
      if next < 0 then
        result := 0
      elseif next > max_x then
        result := max_x
      else
        result := next
      end
    ensure
      bounded: result >= 0 and result <= max_x
    end
end
```

#### Studio Driver (nominal + edge inputs)

```nex
class App
feature
  run() do
    let d: Delivery_Tiny := create Delivery_Tiny
    let n: Notes_Tiny := create Notes_Tiny
    let w: World_Tiny := create World_Tiny

    -- Nominal
    print(d.next_stop("A", "C"))        -- expected: "B"
    print(n.find_by_tag("algorithms"))  -- expected: "note_001"
    print(w.step(3, 2, 10))             -- expected: 5

    -- Edge / failure-oriented checks
    print(d.next_stop("C", "C"))        -- expected: "C"
    print(d.next_stop("X", "C"))        -- expected: "UNREACHABLE"
    print(n.find_by_tag("unknown"))     -- expected: "NOT_FOUND"
    print(w.step(9, 5, 10))             -- expected: 10
    print(w.step(1, -5, 10))            -- expected: 0
  end
end
```

You can evolve this starter in three directions:

* add a richer map model for delivery
* replace fixed tag matching with indexed note structures
* extend world updates to multiple entities per tick

## 3. Studio Challenges

### Level 1 — Core Implementation

* Implement the tiny system for one domain.
* Add executable examples and expected results.

### Level 2 — Cross-System Generalization

* Implement tiny versions for all three domains.
* Identify one shared abstraction across them.

### Level 3 — Exploration

* Replace one design choice with an alternative and compare outcomes.
* Record what changed in complexity and failure behavior.

## 4. Postmortem

Discuss:

* Which assumptions were validated?
* Which assumptions were false?
* What information was missing from the original problem statement?
* What should be tightened before model redesign?

## Deliverables

* runnable Nex code for all tiny systems
* short design notes (inputs, outputs, guarantees)
* edge-case checklist with observed behavior
