#!/usr/bin/env clojure

(require '[nex.parser :as p])
(require '[nex.generator.java :as java])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║           ARRAYS AND MAPS DEMONSTRATION                    ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Example 1: Array Declaration and Default Value
(println "═══ Example 1: Array Declaration ═══")
(println)

(def example1
  "class Container
  feature
    items: Array [String]
    numbers: Array [Integer]
end")

(println "NEX CODE:")
(println "─────────")
(println example1)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example1))
(println)

;; Example 2: Array Literal
(println "═══ Example 2: Array Literal ═══")
(println)

(def example2
  "class Demo
  feature
    demo() do
      let numbers := [1, 2, 3, 4, 5]
      let names := [\"Alice\", \"Bob\", \"Charlie\"]
      print(numbers)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example2)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example2))
(println)

;; Example 3: Array Access
(println "═══ Example 3: Array Subscript Access ═══")
(println)

(def example3
  "class Test
  feature
    items: Array [Integer]

    demo() do
      let first := items[0]
      let second := items[1]
      let x := items[i]
      print(first)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example3)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example3))
(println)

;; Example 4: Map Declaration
(println "═══ Example 4: Map Declaration ═══")
(println)

(def example4
  "class Store
  feature
    prices: Map [String, Decimal]
    inventory: Map [String, Integer]
end")

(println "NEX CODE:")
(println "─────────")
(println example4)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example4))
(println)

;; Example 5: Map Literal
(println "═══ Example 5: Map Literal ═══")
(println)

(def example5
  "class Demo
  feature
    demo() do
      let ages := {\"Alice\": 30, \"Bob\": 25}
      let config := {name: \"App\", version: 1}
      print(ages)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example5)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example5))
(println)

;; Example 6: Map Access
(println "═══ Example 6: Map Subscript Access ═══")
(println)

(def example6
  "class Test
  feature
    data: Map [String, Integer]

    demo() do
      let value := data[\"key\"]
      let x := data[keyVar]
      print(value)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example6)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example6))
(println)

;; Example 7: Arrays and Maps Together
(println "═══ Example 7: Arrays and Maps Together ═══")
(println)

(def example7
  "class Store
  feature
    items: Array [String]
    prices: Map [String, Decimal]

  constructors
    make() do
      items := [\"apple\", \"banana\"]
      prices := {\"apple\": 1.50, \"banana\": 0.75}
    end

  feature
    get_item_price(index: Integer) do
      let item := items[index]
      let price := prices[item]
      print(price)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example7)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example7))
(println)

;; Example 8: Nested Arrays
(println "═══ Example 8: Nested Arrays (Matrix) ═══")
(println)

(def example8
  "class Matrix
  feature
    data: Array [Array [Integer]]

  constructors
    make() do
      data := [[1, 2, 3], [4, 5, 6]]
    end

  feature
    get_cell(row, col: Integer) do
      let value := data[row][col]
      print(value)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example8)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example8))
(println)

;; Example 9: Map of Arrays
(println "═══ Example 9: Map of Arrays ═══")
(println)

(def example9
  "class Categories
  feature
    items: Map [String, Array [String]]

  constructors
    make() do
      items := {
        \"fruits\": [\"apple\", \"banana\"],
        \"vegetables\": [\"carrot\", \"broccoli\"]
      }
    end

  feature
    get_item(category: String, index: Integer) do
      let item := items[category][index]
      print(item)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example9)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example9))
(println)

;; Example 10: Method Parameters
(println "═══ Example 10: Arrays and Maps as Parameters ═══")
(println)

(def example10
  "class Processor
  feature
    process_array(items: Array [String]) do
      let first := items[0]
      print(first)
    end

    process_map(data: Map [String, Integer]) do
      let value := data[\"key\"]
      print(value)
    end
end")

(println "NEX CODE:")
(println "─────────")
(println example10)
(println)
(println "JAVA CODE:")
(println "──────────")
(println (java/translate example10))
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║   KEY FEATURES:                                            ║")
(println "║   • Array [T] → ArrayList<T> in Java                       ║")
(println "║   • Map [K, V] → HashMap<K, V> in Java                     ║")
(println "║   • Default values: Array → [], Map → {}                   ║")
(println "║   • Subscript access: arr[i], map[key]                     ║")
(println "║   • Nested structures supported                            ║")
(println "╚════════════════════════════════════════════════════════════╝")
