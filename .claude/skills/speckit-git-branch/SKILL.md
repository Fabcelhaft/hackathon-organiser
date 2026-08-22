---
name: "speckit-git-branch"
description: "Create a git feature branch for a Spec Kit feature. Derives the branch name from the feature description using the project's numbering scheme, then creates and checks out the branch."
argument-hint: "The feature description (same text passed to /speckit-specify)"
user-invocable: false
disable-model-invocation: false
---

## Purpose

This hook runs automatically before `/speckit-specify`. It creates a git branch named after the
feature and switches to it, so all spec work lives on a dedicated branch from the start.

## Execution

You have the feature description available from the conversation context — it is the text the
user passed to `/speckit-specify`.

Run the git branch script, passing the feature description as the argument:

```bash
python3 .specify/scripts/python/git_branch.py --json "<FEATURE_DESCRIPTION>"
```

Replace `<FEATURE_DESCRIPTION>` with the actual text. Quote it properly so the shell treats it
as a single argument.

The script will:
1. Compute the branch name using the project's sequential numbering (dry-run, no files created).
2. Run `git switch -c <branch_name>` to create and check out the branch.
3. Print a JSON line to stdout: `{"BRANCH_NAME": "...", "FEATURE_NUM": "..."}`.

## Output

After the script completes, report to the user:

```
Created branch: <BRANCH_NAME>
```

Then pass `BRANCH_NAME` and `FEATURE_NUM` back to the calling `/speckit-specify` context so it
can note them in the spec header.

## Error Handling

- If the script exits non-zero, print the error and abort — do not proceed with spec generation.
- If git reports that the branch already exists, the script switches to it; treat this as success
  and continue.
- If the working tree has uncommitted changes that would prevent a branch switch, report the
  error to the user and ask them to stash or commit first.
