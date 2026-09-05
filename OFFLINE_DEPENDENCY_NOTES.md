# Kashem merged source

The original Messages application, plus the three dependency source projects supplied with it, are merged into one Gradle project:

- `app/` - Messages application feature code/resources
- `commons/` - Fossify Commons 6.1.6
- `mmslib/` - MMS library 1.0.0
- `indicator-fast-scroll/` - IndicatorFastScroll source

The app no longer declares the original `com.github.androbox-pro:*` dependencies. Those three are local `project(...)` dependencies.

Note: Fossify Commons itself still declares several legacy third-party libraries (for example PatternLockView/Reprint/RTL ViewPager) that were not included among the supplied ZIPs. Those are not Androbox dependencies and are retained so Commons keeps its full feature set. They may be fetched from the repositories configured in `settings.gradle.kts`.
