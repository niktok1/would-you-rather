package io.ntole.wyr.core.data.mapper

import io.ntole.wyr.core.domain.like.QuestionLikes
import io.ntole.wyr.core.like.LikeResultDto

internal fun LikeResultDto.toDomain(): QuestionLikes =
    QuestionLikes(
        questionId = questionId,
        likeCount = likeCount,
        likedByMe = likedByMe,
    )
