package io.ntole.wyr.di

import io.ntole.wyr.core.domain.playgames.PlayGames
import io.ntole.wyr.core.domain.push.DevicePush

/**
 * What only a platform's own services can do, handed to [initKoin] by its entry point, since each is
 * made there, before Koin starts, from what the platform alone has (CLAUDE.md §3): Google Play Games
 * Services on an Android build that has it set up (§8a, *Play Games sign-in*). Every other platform,
 * and every test, has [None].
 */
data class DeviceServices(
    val playGames: PlayGames = PlayGames.None,
    val push: DevicePush = DevicePush.None,
) {
    companion object {
        /** No platform services: desktop, iOS and the web for now, and the tests. */
        val None: DeviceServices = DeviceServices()
    }
}
