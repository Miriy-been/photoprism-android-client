package ua.com.radiokot.photoprism.api.subjects.model

import com.fasterxml.jackson.annotation.JsonProperty

data class PhotoPrismSubjectUpdate(
    @JsonProperty("Name") val name: String?,
    @JsonProperty("Favorite") val favorite: Boolean?,
    @JsonProperty("Hidden") val hidden: Boolean?,
)