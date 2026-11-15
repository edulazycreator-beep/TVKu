package com.tvku.core.data.catalog

import com.tvku.core.model.Channel
import com.tvku.core.model.ChannelCategory
import com.tvku.core.model.ChannelSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class CatalogDto(
    val schema: Int,
    @SerialName("generated_at") val generatedAt: String? = null,
    val channels: List<ChannelDto> = emptyList(),
)

@Serializable
internal data class ChannelDto(
    val id: String,
    val name: String,
    val country: String,
    val category: String? = null,
    val languages: List<String> = emptyList(),
    val sources: List<SourceDto> = emptyList(),
)

@Serializable
internal data class SourceDto(
    val type: String,
    @SerialName("youtube_channel_id") val youtubeChannelId: String? = null,
    val url: String? = null,
    val platform: String? = null,
    val format: String? = null,
    val height: Int? = null,
)

class CatalogFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parses catalog-v1.json defensively: unknown fields and unknown source types
 * are ignored, invalid sources are dropped, and channels left without any
 * valid source are dropped. Nothing is ever "repaired" into a playable source.
 */
object CatalogParser {
    const val SUPPORTED_SCHEMA = 1
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val COUNTRY = Regex("^[A-Z]{2}$")

    fun parse(text: String): List<Channel> {
        val dto = try {
            json.decodeFromString(CatalogDto.serializer(), text)
        } catch (e: Exception) {
            throw CatalogFormatException("catalog is not valid JSON", e)
        }
        if (dto.schema != SUPPORTED_SCHEMA) throw CatalogFormatException("unsupported catalog schema ${dto.schema}")
        return dto.channels.mapNotNull(::toChannel).distinctBy { it.id }
    }

    private fun toChannel(c: ChannelDto): Channel? {
        if (!ID.matches(c.id) || c.name.isBlank() || c.name.length > 200 || !COUNTRY.matches(c.country)) return null
        val sources = c.sources.mapNotNull(::toSource)
        if (sources.isEmpty()) return null
        return Channel(
            id = c.id,
            name = c.name.trim(),
            countryCode = c.country,
            category = ChannelCategory.fromWire(c.category),
            languageCodes = c.languages.filter { it.matches(Regex("^[a-z]{3}$")) },
            sources = sources,
        )
    }

    private fun toSource(s: SourceDto): ChannelSource? = runCatching {
        when (s.type) {
            "stream" -> ChannelSource.Stream(
                url = requireNotNull(s.url),
                format = ChannelSource.Stream.Format.valueOf(requireNotNull(s.format)),
                heightPx = s.height,
            )
            "youtube_live" -> ChannelSource.YouTubeLive(requireNotNull(s.youtubeChannelId))
            "official_link" -> ChannelSource.OfficialLink(requireNotNull(s.url), requireNotNull(s.platform))
            else -> null
        }
    }.getOrNull()
}
