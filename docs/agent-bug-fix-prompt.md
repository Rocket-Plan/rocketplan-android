# Agent bug-fix prompt

Paste-ready prompt for handing a single tracked bug (the ID scheme this repo uses in
`docs/BUG_TRACKER.md`) to an agent. **One bug per agent.** Fill in the bug ID at the top; the rest is
generic guardrails — the agent reads this repo's own `CLAUDE.md` and `docs/BUG_TRACKER.md` for the
stack-specific details.

## The prompt

```
Fix <BUG-ID> in this repo. Work end-to-end: confirm, fix, verify, update the tracker.

SETUP
- Read the <BUG-ID> row in docs/BUG_TRACKER.md for the symptom, root cause, and suggested fix.
- Read this repo's CLAUDE.md (and any linked architecture / contributing docs) before changing
  code — it defines the stack, conventions, build/test/deploy commands, and any hard rules. Follow them.
- Isolate your work: if the repo uses git worktrees (a scripts/new-agent-worktree.sh or a documented
  worktree flow), use one named after the bug; otherwise create a branch off the default branch.

CONFIRM FIRST
- Reproduce the defect in the code (read the exact file/lines) before changing anything. If you
  can't confirm it's a real bug, stop and report that instead of forcing a fix.

FIX
- Keep the change minimal and scoped to this one bug. Match the surrounding code's style, naming,
  and idioms. Respect any architecture/lint rules the repo enforces (check for a lint step or
  pre-commit hook). Don't refactor unrelated code.
- If the fix needs a schema/data change, follow the repo's migration convention — never hand-edit
  state the repo manages through migrations.

VERIFY
- Run the repo's gates: lint, typecheck, and tests (find the commands in CLAUDE.md / package.json /
  Makefile / CI config). Add or extend a test that fails before your fix and passes after.
- Where practical, exercise the change at runtime the way the repo documents (local run, deployed
  endpoint, logs) — a passing unit test alone isn't proof the flow works.

TRACKER (last, before committing)
- Edit ONLY the <BUG-ID> row in docs/BUG_TRACKER.md: set its state to fixed, record where it was
  fixed (file or commit), and append a dated fix note. Do not rewrite the table or touch other rows.
- If the repo has a bug-status generator (e.g. scripts/generate_release_bug_status.py), run it as
  the final step and stage its output with your change.
- Commit on your branch/worktree. Match the repo's commit-message convention and include any
  required trailer (check CLAUDE.md / recent git log).

Report back: what you confirmed, the fix, how you verified it, and anything still unverified.
```

## Notes

- One bug per agent, one worktree/branch per bug — keeps parallel agents from colliding.
- Push back on "reviewer-reported" / theory findings: the agent must re-confirm against the code
  before committing a fix.
