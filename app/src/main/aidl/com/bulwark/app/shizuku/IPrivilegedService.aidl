package com.bulwark.app.shizuku;

// Runs inside the Shizuku-spawned process at uid 2000 (shell).
//
// Everything Bulwark cannot do as a normal app crosses this boundary and
// nothing else does. Keep it small, and keep it read-only until a method has
// a tested undo - see context/_shared/safety-rules.md rule 3.
//
// Two AIDL constraints learned the hard way:
//  1. Shizuku requires destroy() to carry transaction id 16777114. AIDL then
//     demands an explicit id on EVERY method - "all or none". So every method
//     below is numbered, and numbers must never be reused or reordered.
//  2. Keep this file ASCII. Non-ASCII characters, even inside comments, make
//     the compiler fail with an unrelated-looking error about import paths.
interface IPrivilegedService {

    // Transaction id fixed by the Shizuku server. Do not change.
    void destroy() = 16777114;

    // Package names visible to a uid-2000 caller. Read-only.
    //
    // This is the privileged half of the visibility spike: the result is
    // compared against what the app sees through its own PackageManager
    // under targetSdk 37 filtering.
    List<String> listPackages(boolean includeUninstalled) = 1;
}
