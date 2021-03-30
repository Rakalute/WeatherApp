package com.websarva.wings.android.cameraxapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Location
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ml.custom.*
import kotlinx.android.synthetic.main.activity_main.*
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors.newSingleThreadExecutor
import kotlin.concurrent.scheduleAtFixedRate


class MainActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var outputDirectory: File
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var latitude: Double? = null
    private var longitude: Double? = null
    private var probabilities: FloatArray? = null

    //カスタムモデルのローカルモデルを構成する
    val localModel = FirebaseCustomLocalModel.Builder()
        .setAssetFilePath("cross_validation1_model.tflite")
        .build()

    //インタープリター
    val options = FirebaseModelInterpreterOptions.Builder(localModel).build()
    val interpreter = FirebaseModelInterpreter.getInstance(options)

    //入力データの推論
    val inputOutputOptions = FirebaseModelInputOutputOptions.Builder()
        .setInputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 224, 224, 3))
        .setOutputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 3))
        .build()



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = newSingleThreadExecutor()
        outputDirectory = getOutputDirectory()

        //10秒ごとにイベントを発生させる．
        Timer().scheduleAtFixedRate(0, 15000){
            takePhoto()
        }



    }

    //マニフェストで指定されたすべての権限が付与されているか
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        //CameraProviderをリクエスト
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    //PreviewにPreviewViewを接続
                    it.setSurfaceProvider(viewFinder.createSurfaceProvider())
                }

            imageCapture = ImageCapture.Builder()
                .build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))



    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        //cameraAPI使用
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    latitude = location.latitude
                    longitude = location.longitude
                }else{
                    Toast.makeText(applicationContext, "no location", Toast.LENGTH_LONG).show()
                }
            }


        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(
                "yyyyMMddHHmmss", Locale.JAPAN
            ).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    val stream: InputStream? = contentResolver.openInputStream(savedUri)


                    //位置情報，時間変数
                    // var position = FloatArray(2)
                    // var latitude: String? = null
                    // var longitude: String? = null
                    var time: String? = null

                    //画像のタグ情報を取得
                    val exifInterface = stream?.let { ExifInterface(it) }
                    if (exifInterface != null) {
                        // exifInterface.getLatLong(position)
                        // latitude = position[0].toString()
                        // longitude = position[1].toString()
                        time = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    }

                    //LocationAPIを使い位置情報を取得


                    if (time == null) time = "null"
                    if (latitude == null) latitude = 0.0
                    if (longitude == null) longitude = 0.0

                    //気象判別
                    val stream2: InputStream? = contentResolver.openInputStream(savedUri)
                    val bitmap = BitmapFactory.decodeStream(BufferedInputStream(stream2))
                    val bitmap2 = Bitmap.createScaledBitmap(bitmap, 224, 224, true)


                    val batchNum = 0
                    val input = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }
                    for (x in 0..223) {
                        for (y in 0..223) {
                            val pixel = bitmap2.getPixel(x, y)
                            input[batchNum][x][y][0] = (Color.red(pixel)) / 255.0f
                            input[batchNum][x][y][1] = (Color.green(pixel)) / 255.0f
                            input[batchNum][x][y][2] = (Color.blue(pixel)) / 255.0f
                        }
                    }

                    val inputs = FirebaseModelInputs.Builder()
                        .add(input) // add() as many input arrays as your model requires
                        .build()
                    interpreter?.run(inputs, inputOutputOptions)?.addOnSuccessListener { result ->
                        //ラベルの出力をしたい
                        val output = result.getOutput<Array<FloatArray>>(0)
                        probabilities = output[0]
                        val show = String.format(
                            "sunny : %1.4f\n cloudy : %1.4f\n rain : %1.4f\n latitude : %1.4f\n longitude : %1.4f\n time : %s",
                            probabilities!![0],
                            probabilities!![1],
                            probabilities!![2],
                            latitude,
                            longitude,
                            time
                        )
                        Toast.makeText(applicationContext, show, Toast.LENGTH_LONG).show()

                    }?.addOnFailureListener { e ->
                        Toast.makeText(applicationContext, "failure", Toast.LENGTH_LONG).show()
                    }

                    //データベースを初期化
                    val db = FirebaseFirestore.getInstance()
                    var weather: String? = null

                    if (probabilities?.max() == probabilities?.get(0)) weather = "sunny"
                    if (probabilities?.max() == probabilities?.get(1)) weather = "cloudy"
                    if (probabilities?.max() == probabilities?.get(2)) weather = "rain"


                    //val weather= probabilities?.get(0)

                    val data = hashMapOf(
                         "weather" to weather,
                        "latitude" to latitude,
                        "longitude" to longitude,
                        "time" to time,
                        "weather" to weather
                    )


                    db.collection("weather information")
                        .add(data)
                        .addOnSuccessListener { Log.d(TAG, "DocumentSnapshot successfully written!") }
                        .addOnFailureListener { e -> Log.w(TAG, "Error writing document", e) }

                }
            })


        /*
        //画像をキャプチャ，メタデータとともにファイルに保存する場合はOnImageSavedCallback
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onCaptureSuccess(image: ImageProxy) {

                    val bitmap = convertImageProxyToBitmap(image)
                    val bitmap2 = Bitmap.createScaledBitmap(bitmap, 224, 224, true)

                    //convert bitmap to InputStream
                    val byteSize = bitmap.rowBytes * bitmap.height
                    val byteBuffer = ByteBuffer.allocate(byteSize)
                    bitmap.copyPixelsToBuffer(byteBuffer)
                    val byteArray = byteBuffer.array()
                    val bs = ByteArrayInputStream(byteArray)

                    //位置情報，時間変数
                    val position = FloatArray(2)
                    var latitude: String? = null
                    var longitude: String? = null
                    var time: String? = null

                    //画像のタグ情報を取得
                    val exifInterface = ExifInterface(bs)
                    exifInterface.getLatLong(position)
                    time = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    latitude = position[0].toString()
                    longitude = position[1].toString()

                    if (time == null) time = "null"
                    if (latitude == null) latitude = "null"
                    if (longitude == null) longitude = "null"


                    val batchNum = 0
                    val input = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }
                    for (x in 0..223) {
                        for (y in 0..223) {
                            val pixel = bitmap2.getPixel(x, y)
                            // Normalize channel values to [-1.0, 1.0]. This requirement varies by
                            // model. For example, some models might require values to be normalized
                            // to the range [0.0, 1.0] instead.
                            input[batchNum][x][y][0] = (Color.red(pixel) - 127) / 255.0f
                            input[batchNum][x][y][1] = (Color.green(pixel) - 127) / 255.0f
                            input[batchNum][x][y][2] = (Color.blue(pixel) - 127) / 255.0f
                        }
                    }

                    val inputs = FirebaseModelInputs.Builder()
                        .add(input) // add() as many input arrays as your model requires
                        .build()
                    interpreter?.run(inputs, inputOutputOptions)?.addOnSuccessListener { result ->
                        //ラベルの出力をしたい
                        var output = result.getOutput<Array<FloatArray>>(0)
                        var probabilities = output[0]
                        var show = String.format(
                            "sunny : %1.4f\n cloudy : %1.4f\n rain : %1.4f\n latitude : %s\n longitude : %s\n time : %s",
                            probabilities[0],
                            probabilities[1],
                            probabilities[2],
                            latitude,
                            longitude,
                            time
                        )
                        Toast.makeText(applicationContext, show, Toast.LENGTH_LONG).show()

                        //firestoreへの処理


                    }?.addOnFailureListener { e ->
                        Toast.makeText(applicationContext, "failure", Toast.LENGTH_LONG).show()
                    }
                    image.close()
                }
            })
         */

    }



    //ImageProxyをBitmapに変換
    private fun convertImageProxyToBitmap(image: ImageProxy): Bitmap{
        val byteBuffer: ByteBuffer = image.planes[0].buffer
        byteBuffer.rewind()
        val bytes = ByteArray(byteBuffer.capacity())
        byteBuffer.get(bytes)
        val clonedBytes = bytes.clone()
        return BitmapFactory.decodeByteArray(clonedBytes, 0, clonedBytes.size)
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}




