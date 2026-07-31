package io.ntole.wyr.core.domain.vote

/** Which of a question's two options was chosen. */
public enum class Side {
    A,
    B,
    ;

    public val other: Side
        get() = if (this == A) B else A
}
