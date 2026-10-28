# Contributing to Muralis

Thank you for wanting to help. Muralis is **source available**, not open source, under the
PolyForm Shield License 1.0.0 in `LICENSE.md`: anyone may read the code, use it, change it and send
changes back, but nobody may use it for a product that competes with Muralis or with Muralis Pro and
Business. The app's terms do not allow removing or working around the Pro and Business lock.

## How

1. Open an issue first for anything larger than a small fix, so we agree on the shape before code.
2. Fork the repository, make your change on a branch, and open a pull request against `main`.
3. Build and test as the README says (`scripts/test-host.sh`, `scripts/build-app.sh`); the CI
   build must pass.
4. Write the pull request's title as what the code will do; it becomes a line in the release notes.

## Adding an integration

An integration brings data from another service to the panel. Each one is a folder under
`app/src/main/integrations/<id>/`, built into the app and shipped with its next release; nothing is
ever downloaded to a panel (Google Play forbids downloaded code, and a panel without a Google
account gets only what the app carries).

A folder holds:

- `integration.json`: `id` (the folder's name, lowercase letters and digits), `class`, `name`,
  `kind` (`local` or `cloud`), `audience` (`home`, `business` or `both`: who it is for, which
  groups it under Integrations offered) and `codeowners` (the GitHub names that look
  after it);
- the class, a public `Integrations.Source` with a public `DEFINITION` (settings, values, interval
  and floor), in package `org.spazio17.muralis.integrations`. It uses only the engine
  (`Integrations`, `TinyJson`), `java.util` and a few plain classes: the engine asks the service
  for it, with its timeouts and limits, and hands it only its own settings and secrets. The build
  refuses a folder that names the network, files, threads, reflection or Android directly. That
  check stops mistakes, not intent: Java on Android has no sandbox inside an app, so the review of
  every pull request is what protects the panels;
- `<Class>Test.java`, run by `scripts/test-host.sh`;
- `README.md`, its help page on GitHub: what it does, what you need, the settings, what it gives
  Muralis, limits, troubleshooting, removing it.

The app finds the folder by itself at build time (`scripts/integration-registry.py`), so a
contribution touches no other file; a pull request from a fork that changes anything outside one
integration's folder is refused by the check. `webvalue` is the example to start from. What every
integration keeps: a Try that asks the service before saving, "unavailable" rather than an old
value when the service fails, keys never shown or logged, and a refusal that says why.

## Contributor agreement

By opening a pull request you agree to the following, for everything you contribute:

1. You wrote the contribution yourself, or you have the right to give it under these terms.
2. You keep the copyright of your contribution.
3. You give Juri Calleri (spazio17), who publishes Muralis, a perpetual, worldwide, non-exclusive,
   free, irrevocable, transferable licence to use, change, copy, publish, distribute and sublicense
   your contribution under any licence terms, including the PolyForm Shield License, other
   source-available or open-source licences, and proprietary or commercial terms such as those of
   the paid Muralis Pro and Muralis Business.
4. You give a licence to any patent of yours that your contribution needs, on the same terms.
5. Your contribution comes as it is, without any warranty.

If you contribute for your employer, make sure your employer agrees to these terms first.
