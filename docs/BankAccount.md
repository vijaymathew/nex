# Class: BankAccount
A bank account with balance tracking and transaction management

## Class Invariants

- **non_negative**: `balance >= 0`

## Fields


### Public Fields

- **balance**: `Integer` 🌐 Public
  Current account balance in cents
- **owner**: `String` 🌐 Public
  Account owner name


### Private Fields

- **internal_id**: `String` 🔒 Private
  Internal tracking identifier


## Constructors

### make(`initial: Integer`, `name: String`)
**Parameters:**
- `initial`: `Integer`
- `name`: `String`



## Methods


### Public Methods

### get_balance(): `Integer`
🌐 Public
Returns the current balance

**Returns:** `Integer`


### deposit(`amount: Integer`)
🌐 Public
Deposit money into the account

**Parameters:**
- `amount`: `Integer`

**Pre-conditions:**
- **positive**: `amount > 0`

**Post-conditions:**
- **increased**: `balance = old balance + amount`

