# Fix Plan: [RP-BUG-XXX] Short Title

**Bug ID(s):** RP-BUG-XXX
**Author:** [name]
**Date:** YYYY-MM-DD
**State:** draft | in_review | approved | shipped

---

## Summary

Brief description of the bug and proposed fix.

## Affected Code

| File | Change |
|------|--------|
| `path/to/file.kt` | What changes |

## Implementation Notes

### Step 1: [Title]
```kotlin
// code before
val result = // ...
```

### Step 2: [Title]
...

## Test Plan

- [ ] Unit tests added/updated
- [ ] Manual QA steps:
  1. Prereq:
  2. Action:
  3. Expected:

## Rollback Plan

How to safely revert if issues arise in production.

## Dependencies

- Requires: API change from server (if applicable)
- Blocking: None / RP-BUG-XXX

## Changelog Entry

```markdown
## [1.0.XX] - YYYY-MM-DD

### Fixed
- [RP-BUG-XXX] Brief description of the fix
```