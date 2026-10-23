# Math Libraries

This page documents the math libraries shipped under `lib/math`. These are library classes, not core built-in classes. Load them with `intern`.

## `math/Random`

`Random` makes pseudo-random numbers. Made with a seed, it gives the same numbers, in the same order, on every run, so a simulation, a game or a training run can be repeated exactly. It is shipped as a Nex library under `lib/math/random.nex`, built on Java's `SplittableRandom`. It is not meant for cryptography.

### Loading

```nex
intern math/Random
```

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |

### Construction

```nex
let repeatable: Random := create Random.with_seed(42)   -- same numbers every run
let different: Random := create Random.make             -- different numbers every run
```

Any `Integer` is a valid seed. Two generators made with the same seed give the same sequence, on both backends.

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `make` | none | `Random` | A generator that starts somewhere different on every run. |
| `with_seed` | `seed: Integer` | `Random` | A generator that gives the same numbers on every run with the same seed. |
| `uniform` | none | `Real` | A `Real` from `0.0` up to, but not including, `1.0`, every value equally likely. |
| `below` | `n: Integer` | `Integer` | A whole number from `0` to `n - 1`, each equally likely. Requires `n > 0`. |
| `between` | `low: Integer, high: Integer` | `Integer` | A whole number from `low` to `high`, both included, each equally likely. Requires `low <= high`. |
| `gaussian` | none | `Real` | A number from the standard normal distribution (the bell curve): mean 0, standard deviation 1. For mean `m` and spread `s`, use `m + s * gaussian()`. |

### Functions

`math/Random` also brings in one generic function. (Nex methods cannot be generic, so it takes the generator as an argument.)

| Function | Arguments | Returns | Description |
|---|---|---|---|
| `shuffle` | `items: Array[T], random: Random` | none | Put the elements of `items` in a random order, in place, every order equally likely (the Fisher–Yates shuffle). |

### Example

```nex
intern math/Random

let random: Random := create Random.with_seed(42)
print(random.between(1, 6))               -- a die roll: 1 with this seed
print(random.uniform() < 0.25)            -- true a quarter of the time
let height: Real := 170.0 + 8.0 * random.gaussian()

let cards: Array[String] := ["A", "K", "Q", "J"]
shuffle(cards, random)
print(cards)                              -- ["K", "J", "Q", "A"] with this seed
```

For a quick random number without a generator, `n.pick` on an `Integer` gives a whole number from `0` to `n - 1`, but it cannot be seeded.
