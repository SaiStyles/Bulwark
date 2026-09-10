# Bulwark R8 / ProGuard rules.
#
# Minification is on for release for two reasons, only one of which is size:
# code that is not in the APK cannot be exploited, and every unused code path
# shipped is attack surface for no benefit. See context/_shared/security.md.
#
# Everything kept below is kept because something resolves it BY NAME at
# runtime, which R8 cannot see. Each keep is a hole in the shrinking, so each
# one states why it exists. Do not add a blanket keep to make a crash go away.

# ---------------------------------------------------------------------------
# Shizuku
# ---------------------------------------------------------------------------

# The Shizuku API and provider are reached reflectively by the Shizuku server
# process, which is not part of our compilation unit.
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# HiddenApiRefine annotations and runtime.
-keep class dev.rikka.tools.refine.** { *; }
-dontwarn dev.rikka.tools.refine.**

# ---------------------------------------------------------------------------
# Our privileged boundary
# ---------------------------------------------------------------------------

# There is deliberately nothing kept here any more.
#
# There used to be three keeps: PrivilegedService, IPrivilegedService and its
# generated stubs. Shizuku loaded PrivilegedService BY NAME in a separate
# uid-2000 process, which R8 cannot see, so the release build would have
# compiled cleanly and failed on a user's phone.
#
# That whole path was deleted on 2026-09-10: Shizuku user services do not work
# on MediaTek (NPE in LoadedApk.makeApplicationInner), and MediaTek is this
# project's target. ShizukuBinderWrapper needs no separate process and no
# class loaded by name, so nothing crosses a boundary R8 cannot follow.
#
# The keeps outlived the classes by one commit. A keep rule for a class that
# does not exist is not harmless: it is a claim, in the file an auditor reads,
# that a boundary is being defended when there is no longer a boundary there.
# If a by-name load is ever reintroduced, the keep comes back with it.

# ---------------------------------------------------------------------------
# Safety-critical logic
# ---------------------------------------------------------------------------

# Not required for correctness, but these carry the never-remove list and the
# input validation from context/_shared/safety-rules.md. Keeping their names
# means a stack trace in a bug report is readable, and an auditor reading a
# release APK can find the guardrails and confirm they are present.
# Bulwark asks for shell access; being auditable is part of the deal.
-keep class com.bulwark.app.shizuku.ProtectedPackages { *; }
-keep class com.bulwark.app.shizuku.CommandSafety { *; }

# ---------------------------------------------------------------------------
# Diagnostics
# ---------------------------------------------------------------------------

# Keep line numbers so crash reports the USER chooses to send are readable.
# Nothing is transmitted automatically - see context/_shared/conventions.md.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
