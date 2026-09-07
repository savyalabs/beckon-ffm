# Changelog

All notable changes to this project are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.7.0] - 2026-09-07

### Fixed

- `FfmKqueueBackend`/`FfmSignalfdBackend` declared `supportedSignals()` as
  `public static`, which cannot implement `SignalBackend`'s `default`
  (instance) method - this never actually satisfied the interface, it just
  wasn't caught before JDK 22's stricter compiler exercised it. Renamed the
  static, stateless computation to `staticSupportedSignals()` and added a
  proper instance override that delegates to it; updated the one reflective
  call site (`beckon-ffm/capabilities`) to match.

### Changed

- Bump `net.clojars.savya/beckon` to **0.7.0**.

## [0.6.0] - 2026-08-28

### Added

- Added lifecycle and native error regression coverage, including bounded
  dispatcher shutdown, repeated construction, concurrent registration/reset,
  startup guards, and platform-specific external-signal skips.

## [0.5.0] - 2026-08-27

### Added

- Expanded platform-specific signal coverage, added the `beckon-ffm/capabilities`
  preflight query, and added macOS kqueue CI coverage.

## [0.4.0] - 2026-08-27

### Fixed

- Isolated Linux and BSD native ABI layouts, validating supported platform sizes
  and field offsets at backend startup with actionable diagnostics.

## [0.3.0] - 2026-08-27

### Added

- Added `beckon-ffm/close!` to stop the native dispatcher, restore signal
  dispositions, and release FFM descriptors and shared memory. This supports
  clean REPL reloads, test teardown, and supervised restarts.

### Fixed

- The Linux `signalfd` backend no longer changes `SIGUSR2`'s process-wide
  disposition. HotSpot uses `SIGUSR2` internally for thread suspend/resume, so
  installing `SIG_DFL` there could terminate the JVM. The dispatcher still
  blocks the signal and consumes its own thread-directed raises via `signalfd`.
- Corrected the second `struct pollfd` entry's `revents` offset in the
  dispatcher poll loop, which previously read back the `events` field and so
  never observed the shutdown wakeup.
- The `signalfd` descriptor is opened with `SFD_NONBLOCK` and `raise!` posts the
  eventfd wakeup, so a post-`poll` read can never block the dispatcher.

## [0.2.0] - 2026-08-27

### Added

- Added the opt-in Linux `beckon-signal-launcher`, which pre-blocks an explicit
  signal allowlist before starting the JVM so `signalfd` can reliably receive
  external signals. `USR2` and `CHLD` are rejected by default, and `-Xrs` is
  required for HotSpot-managed termination signals.
- Added Linux subprocess integration tests for launcher and no-launcher modes.

### Fixed

- Linux registration now installs `SIG_IGN` only for signals in the launcher's
  pre-blocked external allowlist, preserving the registration race protection
  there while retaining `SIG_DFL` in default mode so externally-delivered
  signals are not silently swallowed.

## [0.1.8] - 2026-08-17

### Fixed

- Native call return codes (`signal`, `kevent`, `kill`, `sigemptyset`,
  `sigaddset`, `pthread_sigmask`, `pthread_kill`, and the signalfd mask update)
  are checked and throw with the return value and captured errno instead of
  being ignored.

## [0.1.7] - 2026-07-16
### Fixed
- Both signal backends (kqueue on macOS, signalfd on Linux) restore the previously-installed signal disposition on `reset` instead of forcing `SIG_DFL`.

## [0.1.5] - 2026-07-16
### Changed
- Updated the beckon dependency to 0.4.2.

## [0.1.4] - 2026-07-12
### Changed
- Migrated the build, tests, CI, and release workflow to deps.edn and tools.build with JDK 22 Java FFM compilation.

## [0.1.3] - 2026-07-06
### Changed
- Updated the beckon dependency to 0.4.1.
- Bumped outdated dependencies.

## [0.1.2] - 2026-06-26
### Added
- Added tag-triggered Clojars release workflow and environment-credential deploy configuration.

### Changed
- Standardized documentation, badge labels, community health files, and license metadata.

## [0.1.1] - 2026-06-14
### Changed
- Standardized the README structure, cljdoc badge, status badges, and CI workflow name.

## [0.1.0] - 2026-06-06
### Added
- Initial release.
- Added Foreign Function & Memory signal backends for beckon: Linux `signalfd` and macOS/BSD `kqueue`.
- Added dependency on beckon 0.4.0 for the OS-aware backend selector.
