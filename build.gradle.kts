// The root project of the shared Gradle build (ADR 29.9.26r D9). It builds
// nothing itself. Loading the convention plugins here, unapplied, puts them
// on the root classloader once, so a build service they register (Spotless's)
// is one service for every project rather than one per sibling.

plugins {
    id("lingara.jvm-conventions") apply false
    id("lingara.publishing-conventions") apply false
    id("lingara.kotlin-conventions") apply false
}
