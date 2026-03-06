# Icon / Callout Usage

The HTML theme includes four styled callout blocks using icons:

- `.note-lab`
- `.note-studio`
- `.note-exercise`
- `.note-takeaways`

Use in Markdown with fenced divs:

```markdown
::: {.note-lab}
**Algorithm Lab**
Compare BFS and DFS on the same graph and record hop counts.
:::

::: {.note-studio}
**Studio Milestone**
Refactor the service boundary and preserve behavior parity.
:::

::: {.note-exercise}
**Exercise**
Write one property test for route validity.
:::

::: {.note-takeaways}
**Takeaways**
Contracts plus invariants reduce debug surface area.
:::
```

These styles are intended for HTML/ePub output. Print styling remains text-first for readability.
