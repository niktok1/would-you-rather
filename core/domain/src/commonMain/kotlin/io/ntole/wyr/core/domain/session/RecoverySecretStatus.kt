package io.ntole.wyr.core.domain.session

/**
 * Whether this device keeps a recovery secret for its server, as [SessionDiagnostics] reports it:
 * never the secret itself.
 */
public enum class RecoverySecretStatus {
    /** One is kept. The next session opened where none is stored recovers the player it names. */
    KEPT,

    /** None is kept. A session opened where none is stored is a fresh guest. */
    NONE,

    /** The store would not say, as without Play services on Android. */
    UNREADABLE,

    /** This platform keeps none at all: desktop and web play as guests only. */
    NOT_KEPT_HERE,
}
