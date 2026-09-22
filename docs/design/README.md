# Design notes

One note per module: what it is responsible for, how it keeps its guarantees, and what a reviewer would ask about.
Each note starts as the specification the module was built from and gains an "Implementation notes" section as the
code lands: what was built, where and why it deviates from the specification, configuration, metrics, and the tests
that prove each guarantee. The wave 1 notes were revised after independent review.

| Module | Note | Owner (wave 1) | Status | Tests |
|---|---|---|---|---|
| inventory | [inventory.md](inventory.md) | inventory engineer | Built, reviewed | 147 |
| network + promise | [promise.md](promise.md) | promise engineer | Built, reviewed | 89 + 148 |
| ordering + messaging | [ordering.md](ordering.md) | ordering engineer | Built, reviewed | 127 + 34 |
| catalog | [catalog.md](catalog.md) | architect (reference module) | Built | 10 |

The server application adds 48 more: architecture rules over the whole codebase, a smoke test, and the end-to-end and
concurrency suites that drive every module together over HTTP (629 tests in total).
