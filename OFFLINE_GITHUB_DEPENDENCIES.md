# Local dependency merge

The Android build no longer uses JitPack/GitHub-hosted Maven coordinates for the previously failing `tibbi` FastScroller, Reprint, or PatternLockView libraries.

Those libraries are included as source modules under `local-libs/`. Glide remains a normal Maven Central dependency; its Maven group name contains `com.github`, but it is published to Maven Central and does not require GitHub/JitPack at build time.
