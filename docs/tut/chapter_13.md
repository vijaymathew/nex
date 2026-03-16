# Designing Classes Well

Chapter 12 showed the mechanics of defining a class. This chapter is about judgment — the harder question of what a class should contain and why. Knowing how to write a class is a matter of an hour. Knowing how to design one well is a matter of years. This chapter cannot compress those years, but it can name the principles that guide good decisions and show what those principles look like in practice.


## One Class, One Responsibility

The most reliable principle in class design is also the simplest to state: a class should have one responsibility. It should model one concept, manage one piece of state, and give callers one reason to use it.

A class that has one responsibility is easy to name. If you find yourself reaching for a name like `UserManagerAndFormatter` or `OrderProcessorWithLogging`, the class is doing too much. A class that is hard to name is usually a class that has not yet been designed — it is a collection of code that happens to share a file.

Consider the difference between these two designs for a student record:

```
nex> -- one class doing too much
nex> class Student
       create
         make(n, e: String) do
           name := n
           email := e
           scores := []
         end
       feature
         name: String
         email: String
         scores: Array[Integer]
         add_score(s: Integer) do
           scores.add(s)
         end
         average(): Real do
           result := 0.0
           across scores as s do
             result := result + s.to_real
           end
           result := result / scores.length.to_real
         end
         send_report() do
           -- connects to email server, formats HTML, sends message
         end
         export_to_csv(): String do
           -- formats a CSV row
         end
     end
```

This class manages student data, computes statistics, sends email, and exports to CSV. These are four different responsibilities. A change to the email sending logic requires touching the student class. A change to the CSV format requires touching the student class. Every part of the system that needs to change has found its way into one place.

The better design:

```
nex> class Student
       create
         make(n, e: String) do
           name := n
           email := e
           scores := []
         end
       feature
         name: String
         email: String
         scores: Array[Integer]
         add_score(s: Integer) do
           scores.add(s)
         end
         average(): Real do
           result := 0.0
           across scores as s do
             result := result + s.to_real
           end
           result := result / scores.length.to_real
         end
     end
```

`Student` manages student data and computes statistics intrinsic to a student. Sending email belongs to an email service. Exporting to CSV belongs to a report generator. Each class has one reason to exist.



## What Belongs Inside a Class

A method belongs inside a class when it needs access to the class's private fields to do its work, or when it represents an operation intrinsic to the concept the class models.

`average` belongs inside `Student` because it operates on `scores`, a private field. No code outside `Student` can access `scores` directly. More importantly, "a student's average score" is an intrinsic property — it is something a student *has*, not something done *to* a student from outside.

A method does not belong inside a class when:

- It does not need access to any private fields and could be written as a free function
- It represents an operation from an external perspective — formatting for display, persisting to a database, sending over a network
- It introduces a dependency on an external system that the class itself should not know about

The last point deserves emphasis. A `Student` class that sends email must depend on an email library. A `Student` class that exports CSV must know the CSV format. These dependencies belong to the systems that perform those operations, not to the data model they operate on. Keeping them out of `Student` means `Student` can be used, tested, and changed without any knowledge of how it is displayed, exported, or communicated.



## Data and Behaviour Together

The insight that motivates object-oriented design is that data and the behaviour that naturally acts on it should live together. A `BankAccount` does not just hold a balance — it holds the balance and the rules for modifying it. Those rules are encoded in the methods. The data and its constraints are inseparable.

This distinguishes a well-designed class from a raw map. A map `{"owner": "Alice", "balance": 1000.0}` holds the same data as a `BankAccount`, but nothing prevents external code from setting the balance to a negative number. The class enforces its rules by controlling what operations are possible:

```
nex> class BankAccount
       create
         make(name: String, initial: Real) do
           owner := name
           balance := initial
           overdraft_limit := 0.0
         end
       feature
         owner: String
         balance: Real
         overdraft_limit: Real
         deposit(amount: Real) do
           balance := balance + amount
         end
         withdraw(amount: Real): Boolean do
           if balance - amount >= -overdraft_limit then
             balance := balance - amount
             result := true
           else
             result := false
           end
         end
         get_balance(): Real do
           result := balance
         end
     end
```

`withdraw` returns `false` when the withdrawal would exceed the limit rather than silently allowing an invalid state. The rule lives once, inside the class, and applies everywhere. No external code can bypass it.



## Classes as Models

A well-designed class is a model of something: a real-world entity, a domain concept, or an abstraction. The fields are the properties that matter. The methods are the operations the model supports. Everything else is left out.

Consider modelling a playing card:

```
nex> class Card
       create
         make(r: Integer, s: String) do
           rank := r
           suit := s
         end
       feature
         rank: Integer
         suit: String
         name(): String do
           let rank_names := ["2","3","4","5","6","7","8","9","10","J","Q","K","A"]
           result := rank_names.get(rank - 2) + " of " + suit
         end
         beats(other: Card): Boolean do
           result := rank > other.rank
         end
     end

nex> let ace   := create Card.make(14, "Spades")
nex> let seven := create Card.make(7, "Hearts")
nex> ace.name
Ace of Spades

nex> ace.beats(seven)
true
```

`Card` does not include methods for shuffling (that belongs to `Deck`), dealing (that belongs to `Game`), or rendering to a screen (that belongs to a display layer). It models what a card *is* and what a card *does* in isolation.



## The Difference Between Data Classes and Behaviour Classes

Not all classes have the same character. Some are primarily containers of data — their fields are the point, and methods exist to access or compute from those fields. Others are primarily engines of behaviour — their fields are implementation details that support the operations they expose.

`Point`, `Card`, and `Student` are data-heavy. `Stack`, `Queue`, and a word frequency counter are behaviour-heavy. Both kinds are legitimate. The mistake is confusing them.

A data class that accumulates behaviour becomes a *god class* — one class that knows and controls too much. A behaviour class that exposes its implementation details loses the encapsulation that made it worth defining.

The diagnostic question: *what does a caller need to know to use this class correctly?* For a data class, the answer is its fields and their meaning. For a behaviour class, the answer is its methods and their contracts. If the answer requires knowing about internal implementation details, the class has not been encapsulated well enough.



## Naming Classes

A class name should be a noun or noun phrase that describes the concept being modelled. `BankAccount`, `Student`, `Card`, `Stack` — each names a thing.

Avoid names that describe what the class does rather than what it is: `AccountManager`, `DataProcessor`, `Helper`. These are symptoms of a class without a clear identity. A class named `Helper` is almost always a collection of unrelated functions that have not found their proper homes.

Avoid generic names that could describe anything: `Manager`, `Handler`, `Controller`, `Utility`. These tell a reader nothing about the class's responsibility.

A good test: read the class name aloud and ask whether a domain expert — someone who knows the problem but not the code — would immediately understand what it represents. `BankAccount` passes. `AccountDataManagerHelper` does not.



## A Worked Example: Redesigning a Class

Consider an initial draft of a `Product` class for an online store:

```
nex> class Product
       feature
         id: Integer
         name: String
         price: Real
         stock: Integer
         description: String
         discount_percent: Real
         category: String
         supplier_email: String
         last_ordered_date: String
         reorder_threshold: Integer
     end
```

Apply the single responsibility question: what is a `Product`? A product has an identity (id, name, category), a price, and a description. Stock management belongs to an `Inventory` concept. Supplier information belongs to a `Supplier`. Last ordered date is an event record, not a product attribute.

The redesigned model:

```
nex> class Product
       create
         make(i: Integer, n, cat, desc: String, price: Real) do
           id := i
           name := n
           category := cat
           description := desc
           base_price := price
         end
       feature
         id: Integer
         name: String
         category: String
         description: String
         base_price: Real
         discounted_price(percent: Real): Real do
           result := base_price * (1.0 - percent / 100.0)
         end
     end

nex> class StockRecord
       create
         make(pid, qty, threshold: Integer) do
           product_id := pid
           quantity := qty
           reorder_threshold := threshold
         end
       feature
         product_id: Integer
         quantity: Integer
         reorder_threshold: Integer
         needs_reorder(): Boolean do
           result := quantity <= reorder_threshold
         end
     end
```

Each class now has one responsibility. `Product` knows what a product is. `StockRecord` knows how much stock exists and when to reorder. The ten-field class was not wrong because it had ten fields — it was wrong because those fields belonged to different concepts.



## Summary

- A class should have one responsibility: one concept to model, one piece of state to manage, one reason to change
- A method belongs inside a class when it operates on private fields or represents an intrinsic operation; not when it introduces external dependencies or could be a free function
- Data and the behaviour that naturally governs it belong together; the class enforces invariants by controlling what operations are possible
- Model only what is needed; speculative fields and methods make classes harder to understand and change
- Data classes are centred on their fields; behaviour classes on their methods; god classes on nothing in particular
- Class names should be nouns a domain expert would recognise; names describing what a class does rather than what it is are a warning sign
- When a class has too many fields, ask which belong to different concepts and split accordingly



## Exercises

**1.** The following class has more than one responsibility. Identify them and sketch a redesign that splits them into two or more classes:

```
class LibraryBook
  feature
    isbn: String
    title: String
    author: String
    is_checked_out: Boolean
    borrower_name: ?String
    borrower_email: ?String
    due_date: ?String
    late_fee_per_day: Real
end
```

**2.** Define a `Money` class with fields `amount: Real` and `currency: String`. Add methods `add(other: Money): Money` and `exchange(rate: Real, target_currency: String): Money`. What preconditions do these methods have? State them as comments.

**3.** A `Deck` class represents a standard 52-card deck. Using `Card` from Section 13.4, define `Deck` with a `cards: Array[Card]` field and methods `make` (constructor building all 52 cards), `size(): Integer`, `draw(): Card`, and `is_empty(): Boolean`. State the precondition for `draw` as a comment.

**4.** Review the `BankAccount` in Section 13.3. Is `overdraft_limit` something all bank accounts should have, or does it belong to a subtype? Sketch two classes — a basic `Account` with no overdraft, and an `OverdraftAccount` with a limit — without worrying about inheritance syntax. Which fields and methods does each have?

**5.\*** The `Stack` from Chapter 12 works only with `Integer` values. Define a `StringStack` and a `RealStack` alongside it. What do you notice? What is the only thing that differs between them? This observation motivates *generic types* — a mechanism for writing a class once and using it with any element type — which we introduce in Chapter 15.
