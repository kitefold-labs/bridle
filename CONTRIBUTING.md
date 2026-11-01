# Contributing to Bridle

Thanks for helping! Bridle is free software from Kitefold Labs under the
[Apache License 2.0](LICENSE). By contributing you agree that your contributions are licensed
under the same terms.

## Ways to help

- **Report a bug or crash:** open a [GitHub issue](../../issues/new/choose). Bridle's in-app
  crash reporter pre-fills one for you and shows you exactly what it will send.
- **Suggest a feature:** use the feature-request template. Explain the workflow, not just the
  button.
- **Send a pull request:** see below.
- **Report a security problem:** **don't open a public issue.** Follow [SECURITY.md](SECURITY.md).

## Pull requests

1. Read [docs/development.md](docs/development.md) and install the pre-commit hooks.
2. Branch from `main`; keep each PR focused on one change.
3. Add tests. CI enforces **≥ 95% line and branch coverage**, ktlint, detekt, license and
   CodeQL checks.
4. Update docs and tutorials when behaviour changes.
5. Fill in the PR template.

Every PR gets an independent review before merge. Reviews look at correctness, security
(shell quoting, host-key handling, secret handling), performance on low-end phones, and tests.

## Code of conduct

We follow the [Contributor Covenant 2.1](https://www.contributor-covenant.org/version/2/1/code_of_conduct/).
Report unacceptable behaviour to the maintainers via the contact in [SECURITY.md](SECURITY.md).
