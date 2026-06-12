package ua.com.radiokot.photoprism

import org.junit.Assert.*
import org.junit.Test
import ua.com.radiokot.photoprism.db.CachedMediaEntity

/**
 * Unit tests for [CachedMediaEntity] data class.
 *
 * Note: Full Room DAO tests require Android Context (instrumented tests).
 * These tests verify the entity construction and field mapping.
 */
class CachedMediaDaoTest {

    @Test
    fun createEntityWithMinimalFields() {
        val entity = CachedMediaEntity(
            uid = "photo:test123",
            title = null,
            description = null,
            mediaType = "image",
            mediaTypeRaw = null,
            takenAt = null,
            takenAtLocal = "2024-01-15T10:30:00Z",
            timeZone = null,
            favorite = true,
            isPrivate = false,
            lat = null,
            lng = null,
            altitude = null,
            cameraModel = null,
            cameraMake = null,
            width = 1920,
            height = 1080,
            fileHash = "abc123hash",
            cachedAt = 1000L,
        )

        assertEquals("photo:test123", entity.uid)
        assertEquals("image", entity.mediaType)
        assertTrue(entity.favorite)
        assertEquals(1920, entity.width)
        assertEquals(1080, entity.height)
    }

    @Test
    fun entityDefaults() {
        val entity = CachedMediaEntity(
            uid = "photo:defaults",
            title = null,
            description = null,
            mediaType = "video",
            mediaTypeRaw = null,
            takenAt = null,
            takenAtLocal = "2024-06-01T12:00:00Z",
            timeZone = null,
            lat = null,
            lng = null,
            altitude = null,
            cameraModel = null,
            cameraMake = null,
            width = null,
            height = null,
            fileHash = null,
            cachedAt = 5000L,
        )

        assertEquals("photo:defaults", entity.uid)
        assertEquals("video", entity.mediaType)
        assertFalse(entity.favorite) // default value
        assertFalse(entity.isReadOnly) // default value
        assertFalse(entity.isPrivate)  // default value
    }

    @Test
    fun entityWithGeoLocation() {
        val entity = CachedMediaEntity(
            uid = "photo:geo",
            title = "Geo Tagged",
            description = null,
            mediaType = "image",
            mediaTypeRaw = null,
            takenAt = null,
            takenAtLocal = "2024-03-20T08:15:00Z",
            timeZone = "Asia/Tokyo",
            favorite = false,
            isPrivate = false,
            lat = 35.6762,
            lng = 139.6503,
            altitude = 40.0,
            cameraModel = "iPhone 15",
            cameraMake = "Apple",
            width = 4032,
            height = 3024,
            fileHash = "geohash",
            cachedAt = 2000L,
        )

        assertEquals(35.6762, entity.lat!!, 0.0001)
        assertEquals(139.6503, entity.lng!!, 0.0001)
        assertEquals("Geo Tagged", entity.title)
        assertEquals("iPhone 15", entity.cameraModel)
    }

    @Test
    fun entityWithFavoriteAndPrivate() {
        val entity = CachedMediaEntity(
            uid = "photo:private_fav",
            title = null,
            description = null,
            mediaType = "image",
            mediaTypeRaw = null,
            takenAt = null,
            takenAtLocal = "2024-05-10T16:45:00Z",
            timeZone = null,
            favorite = true,
            isPrivate = true,
            lat = null,
            lng = null,
            altitude = null,
            cameraModel = null,
            cameraMake = null,
            width = null,
            height = null,
            fileHash = null,
            cachedAt = 3000L,
            isReadOnly = true,
        )

        assertTrue(entity.favorite)
        assertTrue(entity.isPrivate)
        assertTrue(entity.isReadOnly)
    }
}
