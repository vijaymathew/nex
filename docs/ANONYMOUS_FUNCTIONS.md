# Anonymous Functions in Nex

Nex supports anonymous functions (also known as lambdas or closures) using the `fn` keyword. Anonymous functions can capture variables from their surrounding lexical scope and can be passed as arguments or stored in variables.

## Syntax

The basic syntax for an anonymous function is:

```nex
fn (params) : ReturnType do body end
```

Example:
```nex
let square := fn(x: Integer): Integer do result := x * x end
print(square(5))  -- Outputs: 25
```

### Parameters and Return Types
- Parameters are defined just like in regular methods: `(name: Type, name2: Type)`.
- The return type is optional if the function doesn't return a value.
- The `result` (or `Result`) variable is used to set the return value.

## Lexical Closures

Anonymous functions capture variables from their defining scope. This allows for powerful functional programming patterns.

```nex
class ClosureExample
  feature
    run() do
      let offset: Integer := 10
      let adder: Function := fn(x: Integer): Integer do 
        result := x + offset 
      end
      print(adder(5))  -- Outputs: 15
    end
end
```

## Function Type

Anonymous functions (and regular global functions) are instances of the built-in `Function` class. You can use `Function` as a type annotation for variables that hold functions.

```nex
let f: Function := fn(x: Integer) do print(x) end
```

## Calling Functions

You can call a function object using the natural function call syntax:

```nex
f(arg1, arg2)
```

Internally, this is translated to a call to one of the `callN` methods (where `N` is the number of arguments) on the `Function` class. You can also call these methods explicitly:

```nex
f.call1(5)
f.call2("hello", true)
```

## Code Generation

### JavaScript Translation

Anonymous functions are translated to inline class expressions that extend the `Function` class. This preserves lexical scope and Nex's method-based calling convention.

**Nex:**
```nex
let f := fn(x: Integer): Integer do result := x + 1 end
```

**JavaScript:**
```javascript
let f = (new class extends Function {
  call1(x) {
    let result = 0;
    result = (x + 1);
    return result;
  }
});
```

### Java Translation

Anonymous functions are translated to anonymous inner classes that extend the `Function` base class.

**Nex:**
```nex
let f := fn(x: Integer): Integer do result := x + 1 end
```

**Java:**
```java
Function f = new Function() {
    @Override public Object call1(Object arg1) {
        return this.call1((Integer)arg1);
    }
    public int call1(int x) {
        int result = 0;
        result = (x + 1);
        return result;
    }
};
```

## Examples

### Passing to Methods
```nex
my_list.for_each(fn(x: Integer) do print(x) end)
```

### Returning from Methods
```nex
make_multiplier(factor: Integer): Function do
  result := fn(x: Integer): Integer do result := x * factor end
end
```
