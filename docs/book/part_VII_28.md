# Part VII — Making Software Trustworthy — Invariants: Rules That Must Never Break

## 28. Invariants: Rules That Must Never Break

Preconditions and postconditions protect individual routine calls.

Invariants protect object validity across its whole lifetime.

An invariant is a truth that must hold whenever an object is externally observable.

---

## Why Invariants Matter

Without invariants, systems can enter invalid intermediate states that later code assumes are impossible.

Invariants provide persistent integrity constraints:

- valid state domains
- relationship consistency
- safety bounds

They reduce bug surface by making illegal states hard to represent.

---

## Invariant Scope

Typical invariant categories:

- field-level: `id /= ""`
- relationship-level: `in_transit -> assigned_robot_id /= ""`
- range-level: `x >= 0 and x <= max_x`

Invariants should be strong enough to protect integrity but not so strict that normal transitions become impossible.

---

## Worked Design Path

Requirement:

> "World objects move each tick but must always stay in legal bounds."

### Step 1: Identify always-true rules

- object id must exist
- position must remain within global limits

### Step 2: Keep transition behavior flexible

- updates may change velocity and position
- invariants should constrain outcomes, not forbid normal movement

### Step 3: Enforce invariants at class level

- all routines must preserve those truths

---

## Nex Implementation Sketch

```nex
class World_Object
feature
  object_id: String
  x: Integer
  vx: Integer
  max_x: Integer

  step()
    require
      max_valid: max_x >= 0
    do
      let next: Integer := x + vx
      if next < 0 then
        x := 0
      elseif next > max_x then
        x := max_x
      else
        x := next
      end
    ensure
      bounded_after_step: x >= 0 and x <= max_x
    end
invariant
  id_present: object_id /= ""
  max_non_negative: max_x >= 0
  x_bounded: x >= 0 and x <= max_x
end
```

Invariants and routine contracts reinforce each other: state never escapes legal bounds.

---

## Invariants Across The Three Systems

### Delivery

- task status must be one of declared statuses
- in-transit tasks must have assigned robot

### Knowledge

- document ids are non-empty and unique in scope
- links cannot self-reference when forbidden

### Virtual World

- entity positions within world bounds
- illegal state combinations disallowed

---

## Common Mistakes

### Mistake 1: Invariants too weak

Symptom:

- invalid states still possible

Recovery:

- add relationship-aware constraints

### Mistake 2: Invariants too strict

Symptom:

- legitimate transitions blocked

Recovery:

- move transition-specific checks into routine contracts

### Mistake 3: Duplicate rule definitions

Symptom:

- invariant and routine rules drift apart

Recovery:

- define core truths once at class level

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

For one class, define:

1. two field invariants
2. one relationship invariant
3. one routine that could violate them
4. one test that confirms invariants hold after routine execution

Then intentionally break one rule and observe failure diagnostics.

---

## Connection to Nex

Nex class invariants are a direct mechanism for persistent state integrity and align naturally with contract-based design.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Invariants protect state integrity across object lifetime.
- They complement preconditions/postconditions at routine boundaries.
- Good invariants balance strength and evolvability.
- Integrity rules belong near the data they protect.

---

In Chapter 29, we use tests to explore system behavior beyond obvious scenarios.
