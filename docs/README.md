# Nex Documentation

This directory contains comprehensive documentation for the Nex programming language.

## Core Documentation

### Language Features
- **[TYPES.md](TYPES.md)** - Type system: Integer, Real, Boolean, String, Char, etc.
- **[GENERICS.md](GENERICS.md)** - Parameterized classes with constrained genericity
- **[ARRAYS_MAPS.md](ARRAYS_MAPS.md)** - Collection types with subscript access
- **[CREATE.md](CREATE.md)** - Object instantiation with the `create` keyword
- **[PARAMETERLESS_CALLS.md](PARAMETERLESS_CALLS.md)** - Calling methods without parentheses
- **[LET_SYNTAX.md](LET_SYNTAX.md)** - Local variable declarations (typed and untyped)
- **[ANONYMOUS_FUNCTIONS.md](ANONYMOUS_FUNCTIONS.md)** - First-class functions and lexical closures

### Design by Contract
- **[CONTRACTS.md](CONTRACTS.md)** - Preconditions, postconditions, and class invariants
- **[CONTRACTS_SUMMARY.md](CONTRACTS_SUMMARY.md)** - Quick reference for contract features
- **[INVARIANTS.md](INVARIANTS.md)** - Class and loop invariants in detail
- **[INVARIANTS_SUMMARY.md](INVARIANTS_SUMMARY.md)** - Quick reference for invariants

### Code Generation
- **[JAVASCRIPT.md](JAVASCRIPT.md)** - Complete guide to Nex → JavaScript translation
- **[JAVASCRIPT_IMPLEMENTATION.md](JAVASCRIPT_IMPLEMENTATION.md)** - Implementation details of JavaScript translator

### Development Guides
- **[INTERPRETER.md](INTERPRETER.md)** - How the Nex interpreter works
- **[PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md)** - Project organization and architecture
- **[QUICKSTART.md](QUICKSTART.md)** - Quick start guide for new users
- **[WEB_IDE.md](WEB_IDE.md)** - Browser IDE setup and usage

### Editor Support
- **[EMACS.md](EMACS.md)** - Emacs major mode documentation

### Change History
- **[UPDATE_SUMMARY.md](UPDATE_SUMMARY.md)** - Summary of all major language updates
- **[SUMMARY.md](SUMMARY.md)** - General language overview
- **[SYNTAX_UPDATE.md](SYNTAX_UPDATE.md)** - Syntax changes and updates
- **[LET_FEATURE.md](LET_FEATURE.md)** - History of let statement feature

## Documentation Organization

### By Topic

**Getting Started:**
1. [QUICKSTART.md](QUICKSTART.md) - Start here!
2. [WEB_IDE.md](WEB_IDE.md) - Run Nex in the browser
3. [TYPES.md](TYPES.md) - Learn the type system
4. [LET_SYNTAX.md](LET_SYNTAX.md) - Variable declarations

**Object-Oriented Features:**
- [CREATE.md](CREATE.md) - Creating objects
- [PARAMETERLESS_CALLS.md](PARAMETERLESS_CALLS.md) - Method calls
- [GENERICS.md](GENERICS.md) - Generic types

**Collections:**
- [ARRAYS_MAPS.md](ARRAYS_MAPS.md) - Arrays and Maps

**Design by Contract:**
- [CONTRACTS.md](CONTRACTS.md) - Complete contract guide
- [INVARIANTS.md](INVARIANTS.md) - Invariants in depth

**Code Generation:**
- [JAVASCRIPT.md](JAVASCRIPT.md) - JavaScript translator

**Development:**
- [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md) - Project layout
- [INTERPRETER.md](INTERPRETER.md) - Interpreter internals
- [JAVASCRIPT_IMPLEMENTATION.md](JAVASCRIPT_IMPLEMENTATION.md) - JavaScript translator internals

### By Experience Level

**Beginner:**
- [QUICKSTART.md](QUICKSTART.md)
- [WEB_IDE.md](WEB_IDE.md)
- [TYPES.md](TYPES.md)
- [LET_SYNTAX.md](LET_SYNTAX.md)
- [CREATE.md](CREATE.md)

**Intermediate:**
- [CONTRACTS.md](CONTRACTS.md)
- [GENERICS.md](GENERICS.md)
- [ARRAYS_MAPS.md](ARRAYS_MAPS.md)
- [PARAMETERLESS_CALLS.md](PARAMETERLESS_CALLS.md)

**Advanced:**
- [INVARIANTS.md](INVARIANTS.md)
- [INTERPRETER.md](INTERPRETER.md)
- [PROJECT_STRUCTURE.md](PROJECT_STRUCTURE.md)
- [JAVASCRIPT_IMPLEMENTATION.md](JAVASCRIPT_IMPLEMENTATION.md)

## Contributing to Documentation

When adding new documentation:
1. Use Markdown format
2. Include a table of contents for long documents
3. Provide code examples
4. Show both Nex and generated code (Java/JavaScript)
5. Update this README with a link to the new document

## See Also

- [Main README](../README.md) - Project overview
- [Test Documentation](../test/README.md) - Test suite documentation
- [Examples](../examples/README.md) - Example programs
