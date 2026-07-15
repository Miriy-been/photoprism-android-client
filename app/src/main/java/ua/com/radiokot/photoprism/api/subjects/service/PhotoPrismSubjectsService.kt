package ua.com.radiokot.photoprism.api.subjects.service

import io.reactivex.rxjava3.core.Observable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import ua.com.radiokot.photoprism.api.subjects.model.PhotoPrismSubject
import ua.com.radiokot.photoprism.api.subjects.model.PhotoPrismSubjectUpdate
import java.io.IOException

interface PhotoPrismSubjectsService {
    @kotlin.jvm.Throws(IOException::class)
    @Headers("Accept: application/json")
    @GET("v1/subjects")
    fun getSubjects(
        @Query("count")
        count: Int,
        @Query("offset")
        offset: Int,
        @Query("type")
        type: String,
        @Query("q")
        q: String? = null,
    ): List<PhotoPrismSubject>

    @PUT("v1/subjects/{uid}")
    fun updateSubject(
        @Path("uid") uid: String,
        @Body body: PhotoPrismSubjectUpdate,
    ): Observable<PhotoPrismSubject>

    @POST("v1/subjects/{uid}/like")
    fun likeSubject(@Path("uid") uid: String): Observable<Unit>

    @DELETE("v1/subjects/{uid}/like")
    fun dislikeSubject(@Path("uid") uid: String): Observable<Unit>
}
