package com.example.starannotationapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCamera: Button
    private lateinit var btnGallery: Button

    private var cameraPhotoUri: Uri? = null

    // Replace with your actual server IP
    private val SERVER_UPLOAD_URL = "http://192.168.1.2:5000/upload"

    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraPhotoUri != null) {
                Log.d(TAG, "Camera captured URI: $cameraPhotoUri")
                handleImageUri(cameraPhotoUri!!)
            } else {
                Toast.makeText(this, "Camera capture canceled or failed.", Toast.LENGTH_SHORT).show()
            }
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                Log.d(TAG, "Gallery selected URI: $uri")
                handleImageUri(uri)
            } else {
                Toast.makeText(this, "No image selected.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        progressBar = findViewById(R.id.progressBar)
        btnCamera = findViewById(R.id.btnCamera)
        btnGallery = findViewById(R.id.btnGallery)

        btnCamera.setOnClickListener { openCamera() }
        btnGallery.setOnClickListener { openGalleryPicker() }
    }

    private fun openCamera() {
        val photoFile = try {
            File.createTempFile("photo_${System.currentTimeMillis()}", ".jpg", cacheDir)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to create file for photo.", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "createTempImageFile failed", e)
            return
        }

        cameraPhotoUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        Log.d(TAG, "Opening camera with URI: $cameraPhotoUri")
        takePhotoLauncher.launch(cameraPhotoUri)
    }

    private fun openGalleryPicker() {
        pickImageLauncher.launch("image/*")
    }

    private fun handleImageUri(imageUri: Uri) {
        progressBar.visibility = View.VISIBLE
        imageView.visibility = View.GONE
        btnCamera.isEnabled = false
        btnGallery.isEnabled = false

        lifecycleScope.launchWhenStarted {
            try {
                // STEP 1: Convert URI to File
                val imageFile = try {
                    uriToFile(imageUri).also {
                        Log.d(TAG, "STEP 1: Copied URI to file: ${it.absolutePath}")
                    }
                } catch (e: Exception) {
                    throw Exception("Failed to convert URI to File", e)
                }

                // STEP 2 & 3: Upload to server AND parse JSON on IO dispatcher
                val stars = withContext(Dispatchers.IO) {
                    val jsonResponse = try {
                        uploadImageFile(imageFile).also {
                            Log.d(TAG, "STEP 2: Received JSON: $it")
                        }
                    } catch (e: Exception) {
                        throw Exception("Upload/Server error", e)
                    }

                    try {
                        parseStarData(jsonResponse).also {
                            Log.d(TAG, "STEP 3: Parsed ${it.size} stars")
                        }
                    } catch (e: Exception) {
                        throw Exception("JSON parsing failure", e)
                    }
                }

                // STEP 4: Draw annotations on the image (this is CPU‐bound but not blocking network)
                val annotatedBitmap = try {
                    drawStarsOnImage(imageFile, stars)
                } catch (e: Exception) {
                    throw Exception("Failed drawing annotations", e)
                }

                // STEP 5: Display annotated image (back on Main dispatcher by default)
                runOnUiThread {
                    imageView.setImageBitmap(annotatedBitmap)
                    imageView.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    btnCamera.isEnabled = true
                    btnGallery.isEnabled = true
                }

            } catch (e: Exception) {
                e.printStackTrace()
                val rootClass = e.javaClass.simpleName
                val rootMsg = e.message ?: "no message"
                val causeClass = e.cause?.javaClass?.simpleName ?: "none"
                val causeMsg = e.cause?.message ?: "none"
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnCamera.isEnabled = true
                    btnGallery.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        "Error [$rootClass]: $rootMsg\nCause [$causeClass]: $causeMsg",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun uriToFile(uri: Uri): File {
        return if (uri.scheme == "file") {
            File(uri.path!!)
        } else {
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw Exception("Unable to open InputStream from URI")
            val tempFile = File.createTempFile("selected_image_", ".jpg", cacheDir)
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()
            tempFile
        }
    }

    // Make this a suspend function and ensure it always runs on Dispatchers.IO
    @Throws(Exception::class)
    private suspend fun uploadImageFile(imageFile: File): String = withContext(Dispatchers.IO) {
        // 1) Build a client with a 5-minute read timeout
        val client = OkHttpClient.Builder()
            // How long to wait for the server to accept the connection
            .connectTimeout(60, TimeUnit.SECONDS)
            // How long to wait for reads (i.e. response body). We set it to 600 seconds (5 min).
            .readTimeout(600, TimeUnit.SECONDS)
            // How long to wait for writes (i.e. uploading the file). You can adjust as needed.
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        // 2) Prepare multipart‐form request body (field name must match Flask’s "image")
        val requestBody = imageFile
            .asRequestBody("image/jpeg".toMediaTypeOrNull())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", imageFile.name, requestBody)
            .build()

        // 3) Build and execute the HTTP request
        val request = Request.Builder()
            .url(SERVER_UPLOAD_URL)
            .post(multipart)
            .build()

        client.newCall(request).execute().use { response ->
            // Log response code/body for debugging
            Log.d(TAG, "Upload HTTP code: ${response.code}")
            val bodyString = response.body?.string()
            Log.d(TAG, "Upload response body: $bodyString")

            if (!response.isSuccessful) {
                throw Exception("Server returned HTTP ${response.code}")
            }
            return@withContext bodyString ?: throw Exception("Empty response from server")
        }
    }

    @Throws(Exception::class)
    private fun parseStarData(jsonResponse: String): List<Star> {
        val resultList = mutableListOf<Star>()
        val jsonObj = JSONObject(jsonResponse)
        val starsArray = jsonObj.getJSONArray("stars")
        for (i in 0 until starsArray.length()) {
            val starObj = starsArray.getJSONObject(i)
            val name = starObj.getString("name")
            val x = starObj.getDouble("x").toFloat()
            val y = starObj.getDouble("y").toFloat()
            resultList.add(Star(name, x, y))
        }
        return resultList
    }

    @Throws(Exception::class)
    private fun drawStarsOnImage(imageFile: File, stars: List<Star>): Bitmap {
        val originalBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: throw Exception("Failed to decode image.")
        val annotatedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        originalBitmap.recycle()

        val canvas = Canvas(annotatedBitmap)
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 25f
            setShadowLayer(5f, 2f, 2f, Color.BLACK)
        }

        for (star in stars) {
            val x = star.x
            val y = star.y
            canvas.drawCircle(x, y, 20f, circlePaint)
            canvas.drawText(star.name, x + 25f, y - 25f, textPaint)
        }
        return annotatedBitmap
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
