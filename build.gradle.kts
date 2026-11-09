// Top-level build file. Configuration shared across modules goes here.
plugins {
    alias(libs.plugins.android.application) apply false
}

// Points git at .githooks so the pre-push gate (unit tests and lint, the same as
// the CI fast job) runs before every push. A convenience, not a requirement: the
// hook is a tracked file, and `git config --unset core.hooksPath` turns it off.
// See CONTRIBUTING.md, Gates.
tasks.register<Exec>("installGitHooks") {
    group = "verification"
    description = "Enable the tracked git hooks in .githooks (pre-push: unit tests and lint)."
    commandLine("git", "config", "core.hooksPath", ".githooks")
}
