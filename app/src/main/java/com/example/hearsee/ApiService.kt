import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("process")
    fun uploadImage(@Body requestBody: ImageRequest): Call<ImageResponse>
}

data class ImageRequest(val image: String)
data class ImageResponse(val audio: String)