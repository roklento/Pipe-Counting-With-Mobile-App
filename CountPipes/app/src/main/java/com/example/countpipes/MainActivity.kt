package com.example.countpipes

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.TextView
import android.provider.MediaStore
import android.app.Activity
import android.content.ContentValues
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.activity.result.IntentSenderRequest
import android.os.Environment
import android.graphics.Point
import android.view.MotionEvent


data class Prediction(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val label: String,
    val confidence: Double
)

class MainActivity : AppCompatActivity() {
    private val apiKey = "vMPpAkt9TmVg22yTr8Yg"
    private val modelEndpoint = "pipe-2c54f/1"
    private lateinit var originalBitmap: Bitmap
    private lateinit var boundingBoxViews: BoundingBoxView
    private var predictionsCount: Int = 0
    private var totalPipeCount: Int = 0

    private fun setPredictionsCount(count: Int) {
        predictionsCount = count
    }

    private fun getPredictionsCount(): Int {
        return predictionsCount
    }

    private fun setTotalPipeCount(count: Int) {
        totalPipeCount = count
    }

    private fun getTotalPipeCount(): Int {
        return totalPipeCount
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let { capturedBitmap ->
            findViewById<ImageView>(R.id.image_view).setImageBitmap(capturedBitmap)

            val encodedImage = convertBitmapToBase64(capturedBitmap)
            uploadImage(encodedImage)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            takePictureLauncher.launch(null)
        } else {
            Toast.makeText(this, "Kamera izni gereklidir.", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestWritePermissionLauncher = registerForActivityResult(StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            saveImage()
        } else {

            Toast.makeText(this, "Depolama alanına yazma izni gereklidir.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.button_capture).setOnClickListener {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                    takePictureLauncher.launch(null)
                    boundingBoxViews.clearManualTags()
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
        findViewById<Button>(R.id.button_save).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestWritePermission()
            } else {
                saveImage()
            }
        }
        boundingBoxViews = findViewById(R.id.boundingBoxView)

        val buttonTag = findViewById<Button>(R.id.button_tag)
        val buttonFinish = findViewById<Button>(R.id.button_finish)
        val buttonUndo = findViewById<Button>(R.id.button_undo)
        val buttonBack = findViewById<Button>(R.id.button_back)

        buttonTag.setOnClickListener {
            toggleManualTagging(true)
        }

        buttonFinish.setOnClickListener {
            toggleManualTagging(false)
            val totalBoxes = getPredictionsCount() + boundingBoxViews.getManualTagsCount()
            setTotalPipeCount(totalBoxes)
            updateBoxCount(totalBoxes)
        }

        buttonUndo.setOnClickListener {
            boundingBoxViews.removeLastTag()
        }

        buttonBack.setOnClickListener {
            toggleManualTagging(false)
            boundingBoxViews.clearManualTags()
            val totalBoxes = getPredictionsCount()
            setTotalPipeCount(totalBoxes)
            updateBoxCount(totalBoxes)
        }
    }

    private fun toggleManualTagging(enable: Boolean) {
        findViewById<Button>(R.id.button_tag).visibility = if (enable) View.INVISIBLE else View.VISIBLE
        findViewById<Button>(R.id.button_finish).visibility = if (enable) View.VISIBLE else View.INVISIBLE
        findViewById<Button>(R.id.button_undo).visibility = if (enable) View.VISIBLE else View.INVISIBLE
        findViewById<Button>(R.id.button_back).visibility = if (enable) View.VISIBLE else View.INVISIBLE
        findViewById<Button>(R.id.button_save).visibility = if (enable) View.INVISIBLE else View.VISIBLE
        findViewById<Button>(R.id.button_capture).visibility = if (enable) View.INVISIBLE else View.VISIBLE

        if (enable) {
            boundingBoxViews.enableTouch(true)
        } else {
            boundingBoxViews.enableTouch(false)
        }
    }

    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    private fun uploadImage(encodedImage: String) {
        val thread = Thread {
            try {
                val url = URL("https://detect.roboflow.com/$modelEndpoint?api_key=$apiKey")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.doOutput = true

                val wr = DataOutputStream(connection.outputStream)
                wr.writeBytes(encodedImage)
                wr.close()

                val inputStream = connection.inputStream
                val result = inputStream.bufferedReader().use { it.readText() }
                handleApiResponse(result)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        thread.start()
    }

    private fun updateBoxCount(count: Int) {
        findViewById<TextView>(R.id.textViewCount).text = getString(R.string.box_count, count)
    }

    private fun handleApiResponse(response: String) {
        val jsonResponse = JSONObject(response)
        val predictionsJsonArray = jsonResponse.getJSONArray("predictions")
        val imageWidth = jsonResponse.getJSONObject("image").getDouble("width")
        val imageHeight = jsonResponse.getJSONObject("image").getDouble("height")
        val predictions = mutableListOf<Prediction>()

        for (i in 0 until predictionsJsonArray.length()) {
            val item = predictionsJsonArray.getJSONObject(i)
            val prediction = Prediction(
                x = item.getDouble("x"),
                y = item.getDouble("y"),
                width = item.getDouble("width"),
                height = item.getDouble("height"),
                label = item.getString("class"),
                confidence = item.getDouble("confidence")
            )
            predictions.add(prediction)
        }
        val imageView = findViewById<ImageView>(R.id.image_view)
        imageView.isDrawingCacheEnabled = true
        originalBitmap = Bitmap.createBitmap(imageView.drawingCache)
        imageView.isDrawingCacheEnabled = false

        val boundingBoxView = findViewById<BoundingBoxView>(R.id.boundingBoxView)
        boundingBoxView.setPredictions(predictions, imageWidth, imageHeight)

        setPredictionsCount(predictions.size)
        runOnUiThread {
            updateBoxCount(predictions.size)
        }
    }

    private fun requestWritePermission() {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val writeIntent = MediaStore.createWriteRequest(contentResolver, listOf(collection))

        val intentSender = writeIntent.intentSender
        requestWritePermissionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
    }

    private fun saveImage() {
        val boundingBoxView = findViewById<BoundingBoxView>(R.id.boundingBoxView)
        val combinedBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combinedBitmap)
        canvas.drawBitmap(originalBitmap, 0f, 0f, null)
        boundingBoxView.draw(canvas)

        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 40f
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val text = "Boru Sayısı: ${getTotalPipeCount()}"
        canvas.drawText(text, 10f, 50f, textPaint)

        val filename = "image_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
        }

        val imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        imageUri?.let {
            contentResolver.openOutputStream(it).use { outputStream ->
                if (outputStream != null) {
                    combinedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    Toast.makeText(this, "Görüntü galeriye kaydedildi", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

class BoundingBoxView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var predictions: List<Prediction> = emptyList()
    private var imageWidth: Double = 1.0
    private var imageHeight: Double = 1.0

    private var manualTags = mutableListOf<Point>()
    private var isTouchEnabled = false

    fun enableTouch(enable: Boolean) {
        isTouchEnabled = enable
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isTouchEnabled && event.action == MotionEvent.ACTION_DOWN) {
            manualTags.add(Point(event.x.toInt(), event.y.toInt()))
            invalidate()
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }


    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    fun setPredictions(predictions: List<Prediction>, imageWidth: Double, imageHeight: Double) {
        this.predictions = predictions
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        predictions.forEach { prediction ->
            val centerX = (prediction.x * scaleX).toFloat()
            val centerY = (prediction.y * scaleY).toFloat()

            val x1 = centerX - (prediction.width * scaleX / 2).toFloat()
            val y1 = centerY - (prediction.height * scaleY / 2).toFloat()
            val x2 = centerX + (prediction.width * scaleX / 2).toFloat()
            val y2 = centerY + (prediction.height * scaleY / 2).toFloat()

            canvas.drawRect(x1, y1, x2, y2, paint)
        }

        manualTags.forEach { tag ->
            canvas.drawCircle(tag.x.toFloat(), tag.y.toFloat(), 10f, paint)
        }
    }
    fun removeLastTag() {
        if (manualTags.isNotEmpty()) {
            manualTags.removeAt(manualTags.size - 1)
            invalidate()
        }
    }
    fun clearManualTags() {
        manualTags.clear()
        invalidate()
    }
    fun getManualTagsCount(): Int {
        return manualTags.size
    }
}


