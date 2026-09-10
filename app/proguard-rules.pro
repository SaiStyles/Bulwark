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

# CRITICAL. ShizukuGateway hands Shizuku this class as a STRING:
#   ComponentName(packageName, PrivilegedService::class.java.name)
# Shizuku then loads it by that name inside a separate uid-2000 process. R8
# has no way to know the string and the class are related, so without this the
# release build compiles cleanly and then fails at runtime, on a user's phone,
# at the exact moment they try to use a privileged feature.
-keep class com.bulwark.app.shizuku.PrivilegedService { *; }

# AIDL-generated stubs and proxies are instantiated by the binder machinery.
-keep class com.bulwark.app.shizuku.IPrivilegedService { *; }
-keep class com.bulwark.app.shizuku.IPrivilegedService$* { *; }

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
