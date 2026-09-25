package io.ntole.wyr.server.db

/**
 * The seeds the server tests seed: the first ones, `seed-1` to `seed-24`, which every build wrote
 * before the second seeds came (CLAUDE.md §8d, *Seeds*), filed under V6's categories alone.
 *
 * The tests were written against a pool this small. A feed's batch holds every question in it, which
 * the tests that answer a whole cycle rely on, and a database seeded with them holds exactly what a
 * database built before migrations holds, which MigrationsTest compares after its boots. So seeds added
 * later change no test but SeedTest, which holds every seed to its rules, and the tests that boot on
 * every seed.
 */
internal val TEST_SEEDS: List<Pair<String, Seed.Starter>> = Seed.SEEDS.take(24)
