(require '[nex.generator.java :as n2j])
(require '[clojure.string :as str])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║              NEX TO JAVA TRANSLATOR DEMO                   ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Example 1: Simple class
(println "═══════════════════════════════════════════════════════════")
(println "Example 1: Simple Class with Fields and Methods")
(println "═══════════════════════════════════════════════════════════")
(println)

(def example1 "class Person
  feature
    name: String
    age: Integer

  feature
    greet() do
      print(\"Hello\")
    end

    birthday() do
      let age: Integer := age + 1
    end
end")

(println "NEX CODE:")
(println example1)
(println)
(println "JAVA CODE:")
(println (n2j/translate example1))
(println)
(println)

;; Example 2: Class with constructor
(println "═══════════════════════════════════════════════════════════")
(println "Example 2: Class with Constructor")
(println "═══════════════════════════════════════════════════════════")
(println)

(def example2 "class Point
  create
    make(x, y: Integer) do
      let x: Integer := x
      let y: Integer := y
    end

  feature
    x: Integer
    y: Integer

  feature
    distance() do
      print(x * x + y * y)
    end
end")

(println "NEX CODE:")
(println example2)
(println)
(println "JAVA CODE:")
(println (n2j/translate example2))
(println)
(println)

;; Example 3: Class with inheritance
(println "═══════════════════════════════════════════════════════════")
(println "Example 3: Class with Inheritance")
(println "═══════════════════════════════════════════════════════════")
(println)

(def example3 "class Animal
  feature
    speak() do
      print(\"Animal speaks\")
    end
end

class Dog inherit Animal
feature
  bark() do
    print(\"Woof!\")
  end
end")

(println "NEX CODE:")
(println example3)
(println)
(println "JAVA CODE:")
(println (n2j/translate example3))
(println)
(println)

;; Example 4: Class with Design by Contract
(println "═══════════════════════════════════════════════════════════")
(println "Example 4: Design by Contract (Preconditions & Postconditions)")
(println "═══════════════════════════════════════════════════════════")
(println)

(def example4 "class Account
  feature
    balance: Integer

  feature
    deposit(amount: Integer)
      require
        positive: amount > 0
      do
        balance := balance + amount
      ensure
        increased: balance >= 0
      end

    withdraw(amount: Integer)
      require
        sufficient: balance >= amount
        positive: amount > 0
      do
        balance := balance - amount
      ensure
        decreased: balance >= 0
      end
end")

(println "NEX CODE:")
(println example4)
(println)
(println "JAVA CODE:")
(println (n2j/translate example4))
(println)
(println)

;; Example 5: Control Flow
(println "═══════════════════════════════════════════════════════════")
(println "Example 5: Control Flow (If-Then-Else, Loops, Scoped Blocks)")
(println "═══════════════════════════════════════════════════════════")
(println)

(def example5 "class Calculator
  feature
    max(a, b: Integer) do
      if a > b then
        print(a)
      else
        print(b)
      end
    end

    sum(n: Integer) do
      let total: Integer := 0
      from
        let i: Integer := 1
      until
        i > n
      do
        let total: Integer := total + i
        i := i + 1
      end
      print(total)
    end

    demo() do
      let x: Integer := 10
      do
        let x: Integer := 20
        print(x)
      end
      print(x)
    end
end")

(println "NEX CODE:")
(println example5)
(println)
(println "JAVA CODE:")
(println (n2j/translate example5))
(println)
(println)

;; Example 6: GCD Algorithm
(println "═══════════════════════════════════════════════════════════")
(println "Example 6: GCD Algorithm with Loop Contracts")
(println "═══════════════════════════════════════════════════════════")
(println)

(def example6 "class Math
  feature
    gcd(a, b: Integer) do
      from
        let x: Integer := a
        let y: Integer := b
      invariant
        x_positive: x > 0
        y_positive: y > 0
      variant
        x + y
      until
        x = y
      do
        if x > y then
          x := x - y
        else
          y := y - x
        end
      end
      print(x)
    end
end")

(println "NEX CODE:")
(println example6)
(println)
(println "JAVA CODE:")
(println (n2j/translate example6 {:skip-type-check true}))
(println)
(println)

;; Example 7: Multiple Inheritance
(println "═══════════════════════════════════════════════════════════")
(println "Example 7: Multiple Inheritance")
(println "═══════════════════════════════════════════════════════════")
(println)

(def example7 "class Flyable
  feature
    fly() do
      print(\"Flying...\")
    end
end

class Swimmable
  feature
    swim() do
      print(\"Swimming...\")
    end
end

class Duck inherit Flyable, Swimmable
feature
  quack() do
    print(\"Quack!\")
  end
end")

(println "NEX CODE:")
(println example7)
(println)
(println "JAVA CODE:")
(println (n2j/translate example7))
(println)
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                   TRANSLATION COMPLETE                     ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "Key Features Translated:")
(println "  ✓ Classes with fields and methods")
(println "  ✓ Constructors")
(println "  ✓ Single and multiple inheritance")
(println "  ✓ Design by Contract (require/ensure/invariant)")
(println "  ✓ Control flow (if-then-else, loops, scoped blocks)")
(println "  ✓ Local variables (let)")
(println "  ✓ Method calls and expressions")
(println)
(println "Notes:")
(println "  • Multiple inheritance uses composition with delegation")
(println "    (each parent becomes a private field with forwarding methods)")
(println "  • Contracts translated to Java assert statements")
(println "  • Loop invariants and variants included as comments")
(println "  • Nex types mapped to Java primitives/classes")
