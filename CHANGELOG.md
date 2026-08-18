# Changelog

## 0.1.0 - 2026-08-18

Initial controlled workaround release for Gerrit 3.6.1.

- Add `ProjectQueryWorkaroundFilter` based on `javax.servlet.Filter`.
- Add `off`, `dry-run`, and `rewrite` runtime modes.
- Restrict matching to Gerrit project-list GET requests and known state queries.
- Rewrite only request parameter `n` through `HttpServletRequestWrapper`.
- Add runtime properties file with immediate reload on matching requests.
- Add response header and Gerrit log marker for verification.
- Add Maven Java 11 build and GitHub Actions artifact build.
