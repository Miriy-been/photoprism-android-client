package ua.com.radiokot.photoprism.features.gallery.data.storage

import ua.com.radiokot.photoprism.db.CachedMediaEntity
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.features.gallery.data.model.GalleryMedia
import ua.com.radiokot.photoprism.features.gallery.data.model.GalleryMedia.TypeData
import ua.com.radiokot.photoprism.features.gallery.data.model.GalleryMedia.TypeName
import ua.com.radiokot.photoprism.features.gallery.data.model.formatPhotoPrismDate
import ua.com.radiokot.photoprism.features.gallery.data.model.parsePhotoPrismDate
import ua.com.radiokot.photoprism.util.LocalDate
import java.util.Date

/**
 * Converts [GalleryMedia] to [CachedMediaEntity] for Room persistence.
 */
internal fun GalleryMedia.toCachedEntity(): CachedMediaEntity {
    return CachedMediaEntity(
        uid = uid,
        title = title,
        description = null,
        mediaType = media.typeName.value,
        mediaTypeRaw = null,
        takenAt = null,
        takenAtLocal = formatPhotoPrismDate(takenAtLocal),
        timeZone = null,
        favorite = isFavorite,
        isPrivate = isPrivate,
        lat = latLng?.first,
        lng = latLng?.second,
        altitude = null,
        cameraModel = null,
        cameraMake = null,
        width = width,
        height = height,
        fileHash = hash,
        cachedAt = System.currentTimeMillis(),
        isReadOnly = false,
    )
}

/**
 * Converts [CachedMediaEntity] back to [GalleryMedia] for offline display.
 */
internal fun CachedMediaEntity.toGalleryMedia(): GalleryMedia {
    val log = kLogger("CachedMediaEntity")

    val typeName = try {
        TypeName.fromPhotoPrism(mediaType)
    } catch (_: Exception) {
        TypeName.UNKNOWN
    }

    val typeData: TypeData = when (typeName) {
        TypeName.IMAGE -> TypeData.Image
        TypeName.RAW -> TypeData.Raw
        TypeName.VECTOR -> TypeData.Vector
        TypeName.ANIMATED -> TypeData.Animated
        TypeName.VIDEO -> TypeData.Video
        TypeName.LIVE -> TypeData.Live(
            fullDurationMs = null,
            kind = TypeData.Live.Kind.OTHER,
        )
        TypeName.SIDECAR -> TypeData.Sidecar
        TypeName.TEXT -> TypeData.Text
        TypeName.OTHER -> TypeData.Other
        TypeName.UNKNOWN -> TypeData.Unknown
    }

    val parsedTakenAtLocal = takenAtLocal?.let(::parsePhotoPrismDate)
    if (parsedTakenAtLocal == null) {
        log.warn { "toGalleryMedia(): missing or unparseable takenAtLocal for cached media $uid, using fallback" }
    }

    return GalleryMedia(
        media = typeData,
        uid = uid,
        width = width ?: 0,
        height = height ?: 0,
        takenAtLocal = LocalDate(localDate = parsedTakenAtLocal ?: Date(0)),
        title = title ?: "",
        isFavorite = favorite,
        isPrivate = isPrivate,
        latLng = if (lat != null && lng != null) lat to lng else null,
        files = emptyList(),
        hash = fileHash ?: uid,
    )
}
