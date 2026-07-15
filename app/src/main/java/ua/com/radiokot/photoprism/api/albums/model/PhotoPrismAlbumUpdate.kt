package ua.com.radiokot.photoprism.api.albums.model

import com.fasterxml.jackson.annotation.JsonProperty

data class PhotoPrismAlbumUpdate(
    @JsonProperty("Title")
    val title: String?,
    @JsonProperty("Description")
    val description: String? = null,
)
