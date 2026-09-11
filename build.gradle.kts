// ponytail: AGP 9.x brings Kotlin with it, so no Kotlin plugin is declared here. Play asked
// for 9.0 or later; this is the current stable. Optimized resource shrinking is the default
// from 9.0 on, which is the whole reason for the move - no flag to set.
plugins {
    id("com.android.application") version "9.4.0" apply false
}
