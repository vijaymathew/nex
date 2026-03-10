# Generic Classes

Exercise 5 in Chapter 13 asked you to define `IntegerStack`, `StringStack`, and `RealStack` alongside each other. If you did it, you noticed something uncomfortable: the three classes are identical except for the element type. Every method has the same structure; only the type annotations differ. Any bug fixed in one must be fixed in all three. Any new method added to one should be added to all three.

This is exactly the problem that generic classes solve. A generic class is parameterised by a type: you write the class once, and the type is supplied when the class is used. `Stack[Integer]`, `Stack[String]`, and `Stack[Real]` are all the same class, instantiated with different type arguments.

This is also how Nex's standard collections work. `Array[T]` and `Set[T]` each take one type argument, and `Map[K, V]` takes two. Once you understand `Stack[G]`, you understand the core idea behind the standard collection library as well.


## A Generic Class

The type parameter is declared in square brackets after the class name:

```
nex> class Stack [G]
       create
         make() do
           items := []
         end
       feature
         items: Array[G]
         push(value: G) do
           items.add(value)
         end
         pop(): G do
           result := items.get(items.length - 1)
           items.remove(items.length - 1)
         end
         peek(): G do
           result := items.get(items.length - 1)
         end
         is_empty(): Boolean do
           result := items.is_empty
         end
         size(): Integer do
           result := items.length
         end
     end
Class(es) registered: Stack
```

`G` is the type parameter — a placeholder for whatever type will be used when the class is instantiated. `items` is an `Array[G]`; `push` takes a `G`; `pop` and `peek` return a `G`. Everything that was `Integer` in the original `Stack` is now `G`.

The type parameter name is a convention. Single uppercase letters are common: `G` for a generic element, `T` for a type, `K` and `V` for key and value. The name does not matter — what matters is that it is used consistently throughout the class.



## Using a Generic Class

When creating an instance, supply the concrete type in square brackets:

```
nex> let int_stack := create Stack[Integer].make
nex> int_stack.push(10)
nex> int_stack.push(20)
nex> int_stack.push(30)
nex> print(int_stack.pop)
30

nex> let str_stack := create Stack[String].make
nex> str_stack.push("hello")
nex> str_stack.push("world")
nex> print(str_stack.peek)
world
```

`Stack[Integer]` is a stack whose element type is `Integer`. `Stack[String]` is a stack whose element type is `String`. Both are produced by the same class definition — only the type argument differs.

Nex enforces type safety: pushing an `Integer` onto a `Stack[String]` is a type error caught before the program runs. The generic mechanism provides both reuse and safety.



## Multiple Type Parameters

A class can have more than one type parameter:

```
nex> class Pair [F, S]
       create
         make(first_val: F, second_val: S) do
           first := first_val
           second := second_val
         end
       feature
         first: F
         second: S
         get_first(): F do
           result := first
         end
         get_second(): S do
           result := second
         end
         describe(): String do
           result := "(" + first.to_string + ", " + second.to_string + ")"
         end
     end
Class(es) registered: Pair
```

```
nex> let p1 := create Pair[String, Integer].make("age", 30)
nex> print(p1.get_first)
age

nex> print(p1.get_second)
30

nex> let p2 := create Pair[Real, Boolean].make(3.14, true)
nex> print(p2.describe)
(3.14, true)
```

`Pair[F, S]` holds a value of type `F` and a value of type `S`. The two types are independent — `Pair[String, Integer]`, `Pair[Real, Boolean]`, and `Pair[String, String]` are all valid instantiations.



## Type Constraints

Sometimes a generic class needs to call methods on its type parameter — and not all types support all methods. If `Stack` needed to sort its elements, `G` would need to support comparison. You cannot sort arbitrary types; you can only sort types that implement `Comparable`.

Type constraints restrict which types can be used as a type argument. The constraint is written with `->`:

```
nex> class Sorted_List [G -> Comparable]
       create
         make() do
           items := []
         end
       feature
         items: Array[G]
         insert(value: G) do
           items.add(value)
           items := items.sort
         end
         max(): G do
           result := items.get(items.length - 1)
         end
         min(): G do
           result := items.get(0)
         end
         size(): Integer do
           result := items.length
         end
     end
Class(es) registered: Sorted_List
```

`[G -> Comparable]` means: `G` can be any type that implements `Comparable`. Inside the class, Nex knows that `G` values can be compared, so `items.sort` — which requires `Comparable` elements — is valid.

```
nex> let nums := create Sorted_List[Integer].make
nex> nums.insert(5)
nex> nums.insert(2)
nex> nums.insert(8)
nex> nums.insert(1)
nex> print(nums.min)
1

nex> print(nums.max)
8
```

Attempting `create Sorted_List[Array[Integer]].make` would be a type error at instantiation, because `Array[Integer]` does not implement `Comparable`.

The built-in constraints available in Nex include:
- `Comparable` — supports ordering (`<`, `<=`, `>`, `>=`)
- `Hashable` — can be used as a map key



## Constrained Multiple Parameters

Type constraints and multiple parameters combine naturally:

```
nex> class Dictionary [K -> Hashable, V]
       create
         make() do
           entries := {}
         end
       feature
         entries: Map[K, V]
         put(key: K, value: V) do
           entries.put(key, value)
         end
         get(key: K): V do
           result := entries.get(key)
         end
         try_get(key: K, default: V): V do
           result := entries.try_get(key, default)
         end
         contains_key(key: K): Boolean do
           result := entries.contains_key(key)
         end
         size(): Integer do
           result := entries.size
         end
     end
Class(es) registered: Dictionary
```

`K` must be `Hashable` because map keys require hashing. `V` is unconstrained — values can be any type. This mirrors the design of the built-in `Map` type, which is itself a generic class with exactly these constraints.

```
nex> let dict := create Dictionary[String, Integer].make
nex> dict.put("apples", 5)
nex> dict.put("oranges", 3)
nex> print(dict.get("apples"))
5

nex> print(dict.try_get("bananas", 0))
0
```



## Generic Classes and Inheritance

A generic class can inherit from another class, and a concrete class can inherit from an instantiated generic:

```
nex> class Bounded_Stack [G] inherit Stack[G]
       create
         make(max: Integer) do
           super.make
           max_size := max
         end
       feature
         max_size: Integer
         is_full(): Boolean do
           result := size = max_size
         end
         push(value: G) do
           if not is_full then
             super.push(value)
           end
         end
     end
Class(es) registered: Bounded_Stack
```

`Bounded_Stack[G]` inherits from `Stack[G]` and adds a `max_size` field and an `is_full` check. The `push` override silently ignores pushes when the stack is full (a real implementation might signal this — we will see how with contracts in Part V).

```
nex> let s := create Bounded_Stack[Integer].make(3)
nex> s.push(1)
nex> s.push(2)
nex> s.push(3)
nex> s.push(4)     -- ignored: stack is full
nex> print(s.size)
3
```



## The Standard Collections as Generic Classes

The built-in `Array[T]`, `Set[T]`, and `Map[K, V]` that you have been using throughout the book are generic classes. `Array[Integer]`, `Array[String]`, and `Array[Real]` are all instances of the same `Array` class with different type arguments. `Set[Integer]` and `Set[String]` are instances of `Set` with different element types. `Map[String, Integer]` and `Map[Integer, String]` are instances of `Map` with different key and value types.

This is why the methods work uniformly across element types: `add`, `get`, `remove`, `contains`, `sort` are defined once on `Array[T]`, and work for any `T`. Similarly, `contains`, `union`, `intersection`, and `difference` are defined once on `Set[T]`, and work for any element type `T`. The `sort` method requires `T -> Comparable`, which is why sorting an `Array[Integer]` works but sorting an `Array[Map[String, Integer]]` would not.

The generic mechanism also explains why `across` infers element types automatically: an `Array[Integer]` knows its element type is `Integer`, so the loop variable is inferred as `Integer` without annotation.

Understanding that the standard collections are generic classes clarifies the entire type system: `Array[Integer]` is not a special built-in type. It is an instance of a generic class, following exactly the same rules as `Stack[Integer]` or `Sorted_List[Integer]`.



## A Worked Example: A Generic Result Type

A common pattern in robust code is a result type that holds either a successful value or an error description — without raising an exception. This is naturally a two-parameter generic:

```
nex> class Result [V]
       create
         success(val: V) do
           value := val
           error := nil
           ok := true
         end
         failure(msg: String) do
           error := msg
           ok := false
         end
       feature
         value: ?V
         error: ?String
         ok: Boolean
         is_ok(): Boolean do
           result := ok
         end
         describe(): String do
           if ok then
             result := "Success: " + value.to_string
           else
             result := "Error: " + error
           end
         end
     end
Class(es) registered: Result
```

```
nex> function safe_divide(a, b: Real): Result[Real]
     do
       if b = 0.0 then
         result := create Result[Real].failure("division by zero")
       else
         result := create Result[Real].success(a / b)
       end
     end

nex> print(safe_divide(10.0, 2.0).describe)
Success: 5.0

nex> print(safe_divide(10.0, 0.0).describe)
Error: division by zero
```

`Result[V]` has two named constructors — `success` and `failure` — making the two cases explicit. The caller can check `is_ok` and handle each case without catching an exception. This pattern — sometimes called a *result type* or *either type* — appears in many modern languages and libraries. Writing it yourself as a generic class in Nex is a good exercise in combining what this chapter has covered.



## Summary

- A generic class is parameterised by one or more type parameters declared in square brackets: `class Name [T]`
- Type parameters are placeholders; concrete types are supplied at instantiation: `create Stack[Integer].make`
- Multiple type parameters are separated by commas: `class Pair [F, S]`
- Type constraints restrict which types can fill a parameter: `[G -> Comparable]` requires `G` to implement `Comparable`; `[K -> Hashable]` requires hashability for use as a map key
- A generic class can inherit from another generic class using the same type parameter: `class Bounded_Stack [G] inherit Stack[G]`
- The built-in `Array[T]`, `Set[T]`, and `Map[K, V]` are generic classes; understanding this explains why element types are inferred and why collection operations work uniformly across types
- Generic classes provide reuse without duplication and type safety without losing flexibility



## Exercises

**1.** The `Stack[G]` class has implicit preconditions: `pop` and `peek` require the stack to be non-empty. Add a `require` comment to each method stating the precondition. Then test what happens when you call `pop` on an empty stack.

**2.** Define a generic class `Box [T]` with a single field `value: T`, a constructor `make(v: T)`, and methods `get(): T` and `set(v: T)`. Then define a `Logged_Box [T] inherit Box[T]` that also keeps a `change_count: Integer` field, incrementing it each time `set` is called. Add a `changes(): Integer` method.

**3.** Define a generic class `Range [G -> Comparable]` with fields `low: G` and `high: G`, a constructor `make(l, h: G)`, and methods `contains(value: G): Boolean` (returns `true` if `low <= value <= high`) and `overlaps(other: Range[G]): Boolean`. Test with integer and real ranges.

**4.** The `Result[V]` class in Section 15.8 has `value: ?V` as a detachable field. Why is `?V` needed rather than `V`? What would happen in the `failure` constructor if `value` were not detachable?

**5.\*** Define a generic `Queue [G]` class backed by an `Array[G]`, with methods `enqueue(value: G)`, `dequeue(): G`, `front(): G`, `is_empty(): Boolean`, and `size(): Integer`. Then define a `Priority_Queue [G -> Comparable]` that inherits `Queue[G]` and overrides `enqueue` so that elements are always inserted in sorted order (smallest at the front). Verify that dequeuing from a `Priority_Queue[Integer]` after inserting `[5, 2, 8, 1, 9]` produces the elements in ascending order.
