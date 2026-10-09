# Beginner error-message regression suite

Each `.nex` file here is one mistake a beginner is likely to make.
`test/nex/beginner_errors_test.clj` runs its program part (everything before
`-- Mistake:`) through the same path as `nex file.nex`, so line numbers and
end-of-file errors are the ones a learner would see. The `--` comments at the
end say what the run must produce:

- `-- Mistake:` what the learner did wrong.
- `-- status: fixed` — the message is good; the test fails if it regresses.
- `-- status: pending` — the message is not good yet. The expectations record
  the message we want. The test reports pending cases without failing, but
  fails once a pending case passes, so it gets marked `fixed`.
- `-- expect:` text that must appear in the output (one per line).
- `-- reject:` text that must not appear in the output.
- `-- exit:` the expected exit code (default 1).
