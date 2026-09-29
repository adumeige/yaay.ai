package ai.yaay.documents

/**
 * Seeds for randomized tests: fixed by default so failures replay, widened on demand, e.g.
 * `./gradlew jvmTest -Pyaay.test.seeds=500 -Pyaay.test.seedStart=$RANDOM`. Failures report the seed.
 */
internal fun testSeeds(default: Int): IntRange {
    val count = System.getProperty("yaay.test.seeds")?.toInt() ?: default
    val start = System.getProperty("yaay.test.seedStart")?.toInt() ?: 0
    return start until start + count
}
