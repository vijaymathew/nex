# A Complete Program

The earlier chapters introduced individual ideas one at a time. This chapter puts them together in one small but complete program.

The goal is not to build something large. It is to show the full arc of development:

1. state the problem clearly
2. choose the data model
3. write small routines with contracts
4. test the result

The example will be a small task manager that stores tasks, marks them complete, and reports progress. By the end of the chapter, every major idea from the book will have appeared in one place: classes, queries, commands, contracts, and tests.


## The Problem

We want a program that can:

- create tasks with a title
- mark a task complete
- report how many tasks are done
- report whether all tasks are complete

Even in a small program like this, good design matters. We do not begin by typing methods at random. We begin by identifying the concepts in the problem itself.

The obvious concepts are:

- a `Todo_Item`
- a `Task_List`


## The First Class: `Todo_Item`

A task needs:

- a title
- a completion flag

Here is the class:

```
nex> class Todo_Item
       create
         make(t: String) do
           title := t
           done := false
         end
       feature
         title: String
         done: Boolean
         mark_done()
           do
             done := true
           ensure
             now_done: done
           end
         is_done(): Boolean do
           result := done
         end
       invariant
         title_not_empty: title.length > 0
     end
```

The invariant says a task must have a non-empty title. The method `mark_done` has a simple postcondition. Already the class has a clear meaning.


## The Second Class: `Task_List`

Now we need a collection of tasks and a few operations over it.

```
nex> class Task_List
       create
         make() do
           tasks := []
         end
       feature
         tasks: Array[Todo_Item]
         add_task(title: String)
           require
             title_not_empty: title.length > 0
           do
             tasks.add(create Todo_Item.make(title))
           ensure
             added_title_visible: tasks.get(tasks.length - 1).title = title
           end
         task_at(index: Integer): Todo_Item
           require
             index_in_range: index >= 0 and index < tasks.length
           do
             result := tasks.get(index)
           end
         size(): Integer do
           result := tasks.length
         end
       invariant
         storage_exists: tasks /= nil
     end
```

This is enough to create and access tasks, but not yet enough to report progress.


## Queries That Summarize State

Add routines that count completed tasks and decide whether all tasks are complete:

```
nex> class Task_List
       create
         make() do
           tasks := []
         end
       feature
         tasks: Array[Todo_Item]
         add_task(title: String)
           require
             title_not_empty: title.length > 0
           do
             tasks.add(create Todo_Item.make(title))
           ensure
             added_title_visible: tasks.get(tasks.length - 1).title = title
           end
         task_at(index: Integer): Todo_Item
           require
             index_in_range: index >= 0 and index < tasks.length
           do
             result := tasks.get(index)
           end
         completed_count(): Integer
           do
             result := 0
             across tasks as task do
               if task.is_done() then
                 result := result + 1
               end
             end
           ensure
             count_in_range: result >= 0 and result <= tasks.length
           end
         all_done(): Boolean do
           result := completed_count = tasks.length
         end
         size(): Integer do
           result := tasks.length
         end
       invariant
         storage_exists: tasks /= nil
     end
```

Notice the style:

- commands change state
- queries summarize state
- contracts explain routine boundaries


## A First Manual Run

```
nex> let todo := create Task_List.make
nex> todo.add_task("write chapter draft")
nex> todo.add_task("check examples")
nex> todo.add_task("revise wording")

nex> todo.size
3

nex> todo.completed_count
0

nex> todo.mark_task_done(0)
nex> todo.mark_task_done(1)

nex> todo.completed_count
2

nex> todo.all_done
false
```

The program already works. But we can still improve the interface.


## Raising the Level of the Interface

Calling `task_at(i).mark_done()` looks attractive, but it is not the best interface here. In Nex, if an object is fetched out of a collection, updated, and not written back, the collection still holds the older value. The list itself should offer a routine that performs the full update:

```
nex> mark_task_done(index: Integer)
       require
         index_in_range: index >= 0 and index < tasks.length
       do
         let item := tasks.get(index)
         item.mark_done()
         tasks.put(index, item)
       ensure
         selected_done: tasks.get(index).done
       end
```

Add it to `Task_List`:

```
nex> class Task_List
       create
         make() do
           tasks := []
         end
       feature
         tasks: Array[Todo_Item]
         add_task(title: String)
           require
             title_not_empty: title.length > 0
           do
             tasks.add(create Todo_Item.make(title))
           ensure
             added_title_visible: tasks.get(tasks.length - 1).title = title
           end
         task_at(index: Integer): Todo_Item
           require
             index_in_range: index >= 0 and index < tasks.length
           do
             result := tasks.get(index)
           end
         mark_task_done(index: Integer)
           require
             index_in_range: index >= 0 and index < tasks.length
           do
             let item := tasks.get(index)
             item.mark_done()
             tasks.put(index, item)
           ensure
             selected_done: tasks.get(index).done
           end
         completed_count(): Integer
           do
             result := 0
             across tasks as task do
               if task.is_done() then
                 result := result + 1
               end
             end
           ensure
             count_in_range: result >= 0 and result <= tasks.length
           end
         all_done(): Boolean do
           result := completed_count = tasks.length
         end
         size(): Integer do
           result := tasks.length
         end
       invariant
         storage_exists: tasks /= nil
     end
```

The class now offers a cleaner interface.


## Tests for the Program

Before considering the program finished, write tests.

```
nex> function test_task_list()
     do
       let todo := create Task_List.make
       todo.add_task("one")
       todo.add_task("two")
       todo.add_task("three")

       if todo.size /= 3 then
         raise "test failed: size after add"
       end

       if todo.completed_count /= 0 then
         raise "test failed: initial completed count"
       end

       todo.mark_task_done(1)

       if todo.completed_count /= 1 then
         raise "test failed: completed count after mark"
       end

       if todo.all_done then
         raise "test failed: all_done too early"
       end

       todo.mark_task_done(0)
       todo.mark_task_done(2)

       if not todo.all_done then
         raise "test failed: all_done should be true"
       end

       print("task list tests passed")
     end
```

The tests exercise:

- creation
- adding tasks
- marking completion
- summary queries

That is enough to give confidence in a small program.


## What the Example Shows

The finished program is not complicated, but it demonstrates the whole method of development:

Start from concepts.

`Todo_Item` and `Task_List` emerged from the problem statement.

Use contracts to shape the interface.

Invalid indices and empty titles became preconditions. Important effects became postconditions.

Use invariants to capture class meaning.

Tasks always have non-empty titles.

Prefer higher-level routines.

`mark_task_done` is a better public routine than exposing raw storage details to callers.

Test the finished behavior.

The tests operate through the public interface, which is exactly how clients will use the program.


## Summary

- A complete program should be developed from concepts, not from isolated code fragments
- Small classes with clear responsibilities make the rest of the design easier
- Contracts help turn vague intentions into precise interfaces
- Good public routines hide representation details
- A few focused tests can validate the full flow of a small program
- The same process scales upward: problem, model, contract, implementation, test


## Exercises

**1.** Extend `Todo_Item` with a `description: ?String` field and a routine for setting it. Decide whether an invariant is needed.

**2.** Add a routine `remaining_count(): Integer` to `Task_List` and write a postcondition for it.

**3.** Prevent a task from being marked done twice by strengthening the interface. Decide whether this should be a precondition or simply an idempotent operation.

**4.** Split the chapter's program into two or three files using `intern`.

**5.\*** Replace the task manager with a different complete miniature program of your own, such as a library checkout tracker or a grade book, and follow the same process from problem statement through tests.
