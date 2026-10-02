## What & why
<!-- One or two sentences. Link the issue: "Closes #12" -->

## Track(s)
- [ ] Java  - [ ] Python  - [ ] TypeScript  - [ ] Shared (spec / docs / CI)

## How I tested it
<!-- Commands you ran and their result, e.g. `make test` → 60 passed; smoke test output; curl session -->

## Security and money check (auth, roles, data, prices, stock)
<!-- Does this change who can see or change what? Which test proves a CUSTOMER / another customer / an unsigned webhook is refused?
     "No auth impact" is a fine answer. -->

## AI assistance
<!-- Which tool, what it drafted, what you changed or rejected, how you verified it. "None" is fine.
     Add a matching entry to AI_LOG.md. -->

## Self-review checklist
- [ ] CI is green
- [ ] Tests cover the new behaviour (including at least one failure / forbidden case)
- [ ] Behaviour matches `spec/openapi.yaml` (or the spec is updated in this PR)
- [ ] No secrets, tokens, `.env` files or personal data committed or logged
- [ ] Docs / CHANGELOG updated if users would notice the change
- [ ] I can explain every line of this diff without the AI's help
