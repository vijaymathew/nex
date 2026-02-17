# Class: Stack [T]
A generic stack data structure with Design by Contract

## Class Invariants

- **valid_capacity**: `capacity > 0`
- **items_within_capacity**: `count() <= capacity`

## Fields


### Public Fields

- **items**: `Array` 🌐 Public
  Internal storage for stack items
- **capacity**: `Integer` 🌐 Public
  Maximum number of items


### Private Fields

- **internal_capacity**: `Integer` 🔒 Private
  Internal capacity tracker


### Selectively Visible Fields

- **debug_info**: `String` 🔑 Visible to: StackDebugger
  Debug information for stack


## Constructors

### make(`max_size: Integer`)
**Parameters:**
- `max_size`: `Integer`

**Pre-conditions:**
- **positive_size**: `max_size > 0`

**Post-conditions:**
- **capacity_set**: `capacity = max_size`



## Methods


### Public Methods

### push(`item: T`)
🌐 Public
Add an item to the top of the stack

**Parameters:**
- `item`: `T`

**Pre-conditions:**
- **not_full**: `count() < capacity`

**Post-conditions:**
- **added**: `count() = old count() + 1`


### pop(): `T`
🌐 Public
Remove and return the top item

**Returns:** `T`

**Pre-conditions:**
- **not_empty**: `count() > 0`

**Post-conditions:**
- **removed**: `count() = old count() - 1`


### count(): `Integer`
🌐 Public
Return the number of items in the stack

**Returns:** `Integer`



### Private Methods

### resize()
🔒 Private
Resize the internal storage



### Selectively Visible Methods

### dump_state()
🔑 Visible to: StackDebugger
Dump stack state for debugging

