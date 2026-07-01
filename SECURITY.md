# Security Policy

## Reporting a vulnerability

OpenMouse is an accessibility tool that runs as an Android AccessibilityService,
so security matters: the service can observe input and inject gestures.

If you find a security issue, please **do not open a public issue**. Instead,
email **Owenpkent@gmail.com** with:

- a description of the issue and its impact,
- steps to reproduce (device, Android version, and any logs),
- and, if you have one, a suggested fix.

You can expect an acknowledgement within a few days. Once a fix is available we
will credit you (unless you prefer to stay anonymous) in the release notes.

## Scope

In scope:

- Ways the service could be used to inject input or capture data beyond its
  stated purpose.
- Overlay or gesture behavior that could be abused to trick a user (tapjacking).
- Leaks of anything sensitive (OpenMouse stores only local settings and collects
  no user data).

Out of scope:

- Issues that require the user to have already granted the accessibility service
  and are inherent to any accessibility tool.
- Vulnerabilities in Android itself or in third-party libraries (please report
  those upstream).

## Supported versions

OpenMouse is pre-1.0; only the latest `main` is supported. Security fixes land
there first.
