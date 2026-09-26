package io.ntole.wyr.server.home

import com.zaxxer.hikari.HikariDataSource
import io.ntole.wyr.core.home.HomePicksDto
import io.ntole.wyr.core.vote.OptionSide
import io.ntole.wyr.server.config.ServerConfig
import io.ntole.wyr.server.db.DatabaseFactory
import io.ntole.wyr.server.db.Migrations
import io.ntole.wyr.server.db.h2Url
import io.ntole.wyr.server.db.raceBehindFirst
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Home screen's two counts (CLAUDE.md §8d, *Home picks*), on a database the migrations built. */
class HomePickStoreTest {
    @Test
    fun `the migration starts both counts at zero`() =
        withMigratedDatabase("home-picks-zero") { database, _ ->
            assertEquals(HomePicksDto(picksA = 0, picksB = 0), transaction(database) { HomePickStore.counts() })
        }

    @Test
    fun `a pick counts one tap of its side and answers both counts after it`() =
        withMigratedDatabase("home-picks-one") { database, _ ->
            assertEquals(
                HomePicksDto(picksA = 1, picksB = 0),
                transaction(database) { HomePickStore.pick(OptionSide.A) },
            )
            assertEquals(
                HomePicksDto(picksA = 1, picksB = 1),
                transaction(database) { HomePickStore.pick(OptionSide.B) },
            )
            assertEquals(
                HomePicksDto(picksA = 2, picksB = 1),
                transaction(database) { HomePickStore.pick(OptionSide.A) },
            )
            assertEquals(HomePicksDto(picksA = 2, picksB = 1), transaction(database) { HomePickStore.counts() })
        }

    /**
     * Every tap queued on one button's row lock builds on the one before, at the server's own isolation
     * level. A read then a write would start several from the same count and lose all but one.
     */
    @Test
    fun `a burst of taps on one button all count`() =
        withMigratedDatabase("home-picks-burst") { database, url ->
            val tap = { HomePickStore.pick(OptionSide.B).picksB }

            val counts = raceBehindFirst(url, database, *Array(BURST) { tap })

            assertEquals((1L..BURST).toList(), counts.sorted(), "each tap must build on every one before it")
            assertEquals(
                HomePicksDto(picksA = 0, picksB = BURST.toLong()),
                transaction(database) { HomePickStore.counts() },
            )
        }

    /** An H2 database the server's own migrations built, through a pool set up as the server's is. */
    private fun withMigratedDatabase(
        name: String,
        block: (Database, String) -> Unit,
    ) {
        val url = h2Url(name)
        val settings =
            DatabaseFactory.poolConfig(ServerConfig.fromEnvironment { null }.copy(jdbcUrl = url)).apply {
                // Wide enough for every tap in the burst to hold a connection at once. The rest is the server's.
                maximumPoolSize = BURST
            }
        HikariDataSource(settings).use { dataSource ->
            Migrations.migrate(dataSource)
            block(Database.connect(dataSource), url)
        }
    }

    private companion object {
        const val BURST = 8
    }
}
