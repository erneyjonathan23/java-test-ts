# AI guidelines — Payments Integration squad (proposal)

Principle: **AI assistants are welcome as tools; the engineer who merges is accountable for every line, as if they had written it.** In payments, "it compiled and the tests were green" is not evidence (see PR-214).

## 1. What is allowed and what must never be shared

**Allowed**, with company-approved tools only (enterprise tenant, no training on our data, data residency agreed with Legal):
- Writing and refactoring code, tests, IaC, queries, documentation, ADR drafts, commit and PR descriptions.
- Explaining unfamiliar code, specs or errors; drafting runbooks; reviewing a diff as a *second* reviewer (never the only one).
- Generating **synthetic** test data.

**Never shared with any AI tool** (prompt, file, screenshot or log excerpt):
- Secrets of any kind: provider webhook secrets, Salesforce credentials, tokens, connection strings, `.env` files — even "test" ones from production systems.
- Personal data: payer names, national IDs (RUT, CC, CURP), e-mails, phones, addresses, policy numbers linked to people, real payloads or logs from production. Mask or synthesize first.
- Payment data covered by contracts with providers, non-public provider documentation where its licence forbids it, security findings not yet fixed, and incident details with customer data.
- Unapproved tools (personal accounts, browser extensions, free online "JSON formatters" with AI) are not allowed for company code.

## 2. Validating AI-generated code and tests before merge

The same bar as any code, plus:
1. **Read every line.** If you cannot explain it in review, it does not go in.
2. **Tests must prove behaviour**, not coverage: each AI-written test must fail when the behaviour it claims to protect is broken (mutate the code or the assertion and watch it go red). No `assertNotNull(service)`, no `atMost(1)` where `never()` is meant, no tests that assert a bug (`assertDoesNotThrow` around a swallowed error).
3. **Check against the source of truth**: provider specs and their **test vectors**, Salesforce limits, our ADRs. AI tools routinely invent API fields, defaults and library methods that look plausible.
4. **Security-sensitive code** (signature verification, authn/authz, crypto, secret handling, logging of payloads, money arithmetic) requires a second human reviewer from the squad and is never accepted as generated without a test against known vectors.
5. **Dependencies suggested by AI** are verified to exist (typosquatting) and pass the dependency-review gate. Licences are checked.
6. CI gates (tests, CodeQL, secret scanning, IaC lint, image scan) apply unchanged; AI is never a reason to bypass them.

## 3. Disclosure in pull requests

The PR template gets a mandatory **"AI assistance"** section:
- *None* / *Assisted* (suggestions, completions) / *Generated* (substantial blocks, tests or files).
- Which parts, which tool, and **how they were validated** (e.g. "signature tests use the provider's vectors; I broke the comparison and saw them fail").
- Disclosure is neutral: using AI is fine; undisclosed AI-generated code found later is treated as a process issue.

## 4. When one of our services calls an LLM (e.g. "summarize dead-lettered notifications for operations")

Minimum controls before such a feature can go to production:

| Area | Control |
|---|---|
| Purpose and approval | Written use case, owner, and a privacy/security review (DPIA where required by the country's law). The LLM **advises**; it never takes an action (no replay, no status change, no customer message) without a human decision. |
| Data minimisation | Send only what the summary needs, **after** masking: no payer personal data, no secrets, no raw payloads; use our canonical events/status records (which have no PII by design). Pseudonymize identifiers if the provider is outside approved data residency. |
| Approved endpoint | Azure OpenAI (or another approved model) in our tenant/region, private endpoint, managed identity, no data retention for training, content logging configured per Legal. Model and version pinned. |
| Prompt injection | Notification fields are **untrusted input** (a payer can type anything into `referencia`). Delimit them as data, never as instructions; the model has no tools that act; output is rendered as text, never executed or used as a query. |
| Output validation | Structured output with a schema; counts and ids in the summary are re-checked against the database; the UI shows sources (links to the notifications) so operators verify instead of trusting. |
| Access control and audit | Same roles as the ops API (`payments.ops.read`); every prompt/response is logged **without** sensitive data, with the user and correlation id. |
| Reliability and cost | Timeouts, rate limits, token budget per day, a fallback without the LLM (the plain list); the feature is never on the critical path of payment processing. |
| Quality | An evaluation set of real (masked) DLQ situations reviewed before release and after each model change; a feedback button for operators. |
| Transparency | The UI states that the content is AI-generated and may be wrong. |
