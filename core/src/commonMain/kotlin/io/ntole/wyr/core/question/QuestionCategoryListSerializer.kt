package io.ntole.wyr.core.question

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A list of [QuestionCategory] on the wire: an array of their names, exactly as the default list
 * serializer writes it, except that a name this build has no category for decodes as
 * [QuestionCategory.UNKNOWN] instead of failing the whole payload.
 *
 * The wire enum rule (CLAUDE.md §5) needs this for a list. `coerceInputValues` only ever coerces a
 * property's own value into its default: an element of a list has no default, so one category added
 * server-side would make an older client fail to decode every question filed under it, and with it
 * the whole batch. So every `List<QuestionCategory>` on the wire is declared
 * `@Serializable(with = QuestionCategoryListSerializer::class)`.
 *
 * An unknown name becomes [QuestionCategory.UNKNOWN] rather than being dropped, for two reasons.
 * It is what [QuestionCategory.UNKNOWN] is for, the one landing zone for a category this build
 * cannot name, and a question filed only under new categories still says it has one. And the server
 * decodes with this too: dropped, a submission naming a real category and one the server does not
 * know would be stored under the real one alone, where as [QuestionCategory.UNKNOWN] it is refused.
 *
 * Only a name is lenient, and nothing is reordered or collapsed: two unknown names are two
 * [QuestionCategory.UNKNOWN]s. An element that is not a string is a malformed payload, and fails as
 * one. The names are the enum's own, which are its serial names too, since it has no `@SerialName`.
 */
public object QuestionCategoryListSerializer : KSerializer<List<QuestionCategory>> {
    private val names = ListSerializer(String.serializer())

    private val byName = QuestionCategory.entries.associateBy { it.name }

    override val descriptor: SerialDescriptor = names.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<QuestionCategory>,
    ) {
        encoder.encodeSerializableValue(names, value.map { it.name })
    }

    override fun deserialize(decoder: Decoder): List<QuestionCategory> =
        decoder.decodeSerializableValue(names).map { name -> byName[name] ?: QuestionCategory.UNKNOWN }
}
