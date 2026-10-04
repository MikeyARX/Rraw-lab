package com.ridderx.rrawlab

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.BlackLevelPattern
import android.media.DngCreator
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MainActivity : Activity() {

    companion object {
        private const val CAMERA_PERMISSION = 100
        private const val RAW14_API = 37

        private fun raw14FormatOrNull(): Int? {
            if (Build.VERSION.SDK_INT < RAW14_API) return null
            return runCatching {
                ImageFormat::class.java.getField("RAW14").getInt(null)
            }.getOrNull()
        }
    }

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraSpinner: Spinner
    private lateinit var formatSpinner: Spinner
    private lateinit var sizeSpinner: Spinner
    private lateinit var captureButton: Button
    private lateinit var copyButton: Button
    private lateinit var reportView: TextView
    private lateinit var textureView: TextureView
    private lateinit var statusView: TextView

    private val cameraIds = mutableListOf<String>()
    private val formatOptions = mutableListOf<FormatOption>()
    private val sizeOptions = mutableListOf<Size>()

    private var selectedCameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var characteristics: CameraCharacteristics? = null

    @Volatile private var pendingImage: Image? = null
    @Volatile private var pendingResult: TotalCaptureResult? = null
    private val saving = AtomicBoolean(false)

    data class FormatOption(val label: String, val format: Int, val dng: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraManager = getSystemService(CameraManager::class.java)
        buildUi()
        startCameraThread()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initializeLab()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
    }

    override fun onDestroy() {
        closeCamera()
        cameraThread?.quitSafely()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            initializeLab()
        } else {
            setStatus("Camera permission is required for R-RAW Lab.", true)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(6, 18, 40))
        }

        val title = TextView(this).apply {
            text = "R-RAW LAB  v0.1"
            textSize = 22f
            setTextColor(Color.rgb(212, 175, 55))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = "Phase 1 · RAW still-photo sensor probe"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(10))
        }
        statusView = TextView(this).apply {
            text = "Waiting for camera permission…"
            setTextColor(Color.rgb(0, 183, 194))
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(title)
        root.addView(sub)
        root.addView(statusView)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cameraSpinner = labeledSpinner(controls, "Camera")
        formatSpinner = labeledSpinner(controls, "RAW mode")
        sizeSpinner = labeledSpinner(controls, "Resolution")
        root.addView(controls)

        textureView = TextureView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260)).also {
                it.topMargin = dp(10)
                it.bottomMargin = dp(10)
            }
            setBackgroundColor(Color.BLACK)
        }
        root.addView(textureView)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        captureButton = Button(this).apply {
            text = "CAPTURE RAW"
            isEnabled = false
        }
        copyButton = Button(this).apply { text = "COPY REPORT" }
        buttonRow.addView(captureButton, LinearLayout.LayoutParams(0, dp(52), 1f).also { it.marginEnd = dp(6) })
        buttonRow.addView(copyButton, LinearLayout.LayoutParams(0, dp(52), 1f).also { it.marginStart = dp(6) })
        root.addView(buttonRow)

        val scroll = ScrollView(this)
        reportView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(4), dp(12), dp(4), dp(30))
            text = "Capability report will appear here."
        }
        scroll.addView(reportView)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        cameraSpinner.onItemSelectedListener = simpleListener { position ->
            if (position in cameraIds.indices) {
                selectedCameraId = cameraIds[position]
                onCameraSelectionChanged()
            }
        }
        formatSpinner.onItemSelectedListener = simpleListener { updateSizesForSelectedFormat() }
        sizeSpinner.onItemSelectedListener = simpleListener { recreateCapturePipeline() }
        captureButton.setOnClickListener { captureStill() }
        copyButton.setOnClickListener { copyReport() }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { openSelectedCamera() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { closeCamera(); return true }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    private fun labeledSpinner(parent: LinearLayout, label: String): Spinner {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(0, dp(7), 0, 0)
        })
        return Spinner(this).also { parent.addView(it, LinearLayout.LayoutParams.MATCH_PARENT, dp(48)) }
    }

    private fun simpleListener(block: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = block(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun startCameraThread() {
        cameraThread = HandlerThread("RRAWCamera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun initializeLab() {
        cameraIds.clear()
        cameraIds.addAll(cameraManager.cameraIdList)
        cameraSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraIds.map { "Camera $it" })
        reportView.text = buildCapabilityReport()
        setStatus("Camera2 probe ready. ${cameraIds.size} openable camera ID(s) found.")
        if (cameraIds.isNotEmpty()) {
            selectedCameraId = cameraIds.first()
            onCameraSelectionChanged()
        }
    }

    private fun buildCapabilityReport(): String {
        val sb = StringBuilder()
        sb.appendLine("R-RAW LAB CAPABILITY REPORT")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("RAW14 API available in OS: ${Build.VERSION.SDK_INT >= RAW14_API}")
        sb.appendLine("RAW14 constant available at runtime: ${raw14FormatOrNull() != null}")
        sb.appendLine("Openable camera IDs: ${cameraIds.joinToString()}")
        sb.appendLine()

        cameraIds.forEach { id ->
            try {
                val c = cameraManager.getCameraCharacteristics(id)
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
                val logical = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                val rawCap = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                sb.appendLine("=== CAMERA $id ===")
                sb.appendLine("Facing: ${facingName(c.get(CameraCharacteristics.LENS_FACING))}")
                sb.appendLine("Hardware: ${hardwareName(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")
                sb.appendLine("Logical multi-camera: $logical")
                sb.appendLine("Physical IDs: ${if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.joinToString().ifBlank { "none" } else "n/a"}")
                sb.appendLine("RAW capability flag: $rawCap")
                sb.appendLine("Manual sensor: ${caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)}")
                sb.appendLine("Focal lengths: ${c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()?.plus(" mm") ?: "unknown"}")
                sb.appendLine("ISO: ${rangeText(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE))}")
                sb.appendLine("Exposure ns: ${rangeText(c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE))}")
                sb.appendLine("CFA: ${cfaName(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT))}")
                sb.appendLine("Black level: ${blackLevelText(c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN))}")
                sb.appendLine("White level: ${c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: "unknown"}")
                sb.appendLine("Active array: ${c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: "unknown"}")
                sb.appendLine("Pixel array: ${c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: "unknown"}")
                appendFormatReport(sb, map, ImageFormat.RAW12, "RAW12")
                raw14FormatOrNull()?.let {
                    appendFormatReport(sb, map, it, "RAW14")
                } ?: sb.appendLine("RAW14: unavailable on this OS/runtime")
                appendFormatReport(sb, map, ImageFormat.RAW_SENSOR, "RAW_SENSOR (16-bit container)")
                sb.appendLine()
            } catch (e: Exception) {
                sb.appendLine("Camera $id error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return sb.toString()
    }

    private fun appendFormatReport(sb: StringBuilder, map: android.hardware.camera2.params.StreamConfigurationMap?, format: Int, label: String) {
        val sizes = try { map?.getOutputSizes(format)?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
        if (sizes.isEmpty()) {
            sb.appendLine("$label: NOT ADVERTISED")
            return
        }
        sb.appendLine("$label: YES")
        sizes.sortedByDescending { it.width.toLong() * it.height }.forEach { s ->
            val ns = try { map?.getOutputMinFrameDuration(format, s) ?: 0L } catch (_: Throwable) { 0L }
            val fps = if (ns > 0) 1_000_000_000.0 / ns else 0.0
            sb.appendLine("  ${s.width}x${s.height} · min ${if (ns > 0) "%.2f ms".format(ns / 1_000_000.0) else "unknown"} · theoretical ${if (fps > 0) "%.2f fps".format(fps) else "unknown"}")
        }
    }

    private fun onCameraSelectionChanged() {
        val id = selectedCameraId ?: return
        characteristics = cameraManager.getCameraCharacteristics(id)
        updateFormatOptions()
        closeCamera()
        if (textureView.isAvailable) openSelectedCamera()
    }

    private fun updateFormatOptions() {
        val c = characteristics ?: return
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        formatOptions.clear()
        if (!map?.getOutputSizes(ImageFormat.RAW12).isNullOrEmpty()) formatOptions.add(FormatOption("RAW12 · packed 12-bit", ImageFormat.RAW12))
        raw14FormatOrNull()?.let { raw14 ->
            val sizes = try { map?.getOutputSizes(raw14) } catch (_: Throwable) { null }
            if (!sizes.isNullOrEmpty()) {
                formatOptions.add(FormatOption("RAW14 · packed 14-bit", raw14))
            }
        }
        if (!map?.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()) formatOptions.add(FormatOption("RAW_SENSOR → DNG reference", ImageFormat.RAW_SENSOR, dng = true))
        if (formatOptions.isEmpty()) formatOptions.add(FormatOption("No RAW output advertised", -1))
        formatSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formatOptions.map { it.label })
        updateSizesForSelectedFormat()
    }

    private fun updateSizesForSelectedFormat() {
        val option = formatOptions.getOrNull(formatSpinner.selectedItemPosition) ?: return
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        sizeOptions.clear()
        if (option.format >= 0) {
            sizeOptions.addAll(try { map?.getOutputSizes(option.format)?.toList().orEmpty() } catch (_: Throwable) { emptyList() })
            sizeOptions.sortByDescending { it.width.toLong() * it.height }
        }
        sizeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sizeOptions.map { "${it.width} × ${it.height}" })
        captureButton.isEnabled = sizeOptions.isNotEmpty()
        recreateCapturePipeline()
    }

    @SuppressLint("MissingPermission")
    private fun openSelectedCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val id = selectedCameraId ?: return
        if (!textureView.isAvailable) return
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    recreateCapturePipeline()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); if (cameraDevice == camera) cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (cameraDevice == camera) cameraDevice = null; runOnUiThread { setStatus("Camera open error $error", true) } }
            }, cameraHandler)
        } catch (e: Exception) {
            setStatus("Open failed: ${e.message}", true)
        }
    }

    private fun recreateCapturePipeline() {
        val device = cameraDevice ?: return
        if (!textureView.isAvailable) return
        val option = formatOptions.getOrNull(formatSpinner.selectedItemPosition) ?: return
        val size = sizeOptions.getOrNull(sizeSpinner.selectedItemPosition) ?: return
        if (option.format < 0) return

        captureSession?.close()
        imageReader?.close()
        pendingImage?.close(); pendingImage = null; pendingResult = null

        val reader = ImageReader.newInstance(size.width, size.height, option.format, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireNextImage() } catch (_: Throwable) { null }
            if (image != null) {
                pendingImage?.close()
                pendingImage = image
                tryFinalizeCapture()
            }
        }, cameraHandler)

        val st = textureView.surfaceTexture ?: return
        val previewSize = choosePreviewSize(characteristics)
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(st)

        try {
            device.createCaptureSession(listOf(previewSurface, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface)
                            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }.build()
                        session.setRepeatingRequest(req, null, cameraHandler)
                        runOnUiThread { setStatus("Preview ready · ${option.label} · ${size.width}×${size.height}") }
                    } catch (e: Exception) {
                        runOnUiThread { setStatus("Preview request failed: ${e.message}", true) }
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    runOnUiThread { setStatus("This preview + RAW stream combination was rejected by the camera HAL.", true) }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            setStatus("Session failed: ${e.message}", true)
        }
    }

    private fun choosePreviewSize(c: CameraCharacteristics?): Size {
        val choices = c?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        return choices.filter { it.width <= 1920 && it.height <= 1080 }.maxByOrNull { it.width.toLong() * it.height } ?: choices.firstOrNull() ?: Size(1280, 720)
    }

    private fun captureStill() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        if (saving.get()) { setStatus("Still saving previous capture…"); return }
        pendingImage?.close(); pendingImage = null; pendingResult = null

        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            setStatus("Capturing…")
            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    pendingResult = result
                    tryFinalizeCapture()
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    runOnUiThread { setStatus("Capture failed: reason ${failure.reason}", true) }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            setStatus("Capture exception: ${e.message}", true)
        }
    }

    @Synchronized
    private fun tryFinalizeCapture() {
        val image = pendingImage ?: return
        val result = pendingResult ?: return
        if (!saving.compareAndSet(false, true)) return
        pendingImage = null
        pendingResult = null
        cameraHandler?.post {
            try {
                saveCapture(image, result)
            } catch (e: Exception) {
                runOnUiThread { setStatus("Save failed: ${e.javaClass.simpleName}: ${e.message}", true) }
            } finally {
                image.close()
                saving.set(false)
            }
        }
    }

    private fun saveCapture(image: Image, result: TotalCaptureResult) {
        val option = formatOptions.getOrNull(formatSpinner.selectedItemPosition) ?: return
        val cameraId = selectedCameraId ?: "unknown"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val size = Size(image.width, image.height)
        val mode = when (option.format) {
            ImageFormat.RAW12 -> "RAW12"
            ImageFormat.RAW_SENSOR -> "RAWSENSOR"
            else -> if (raw14FormatOrNull()?.let { option.format == it } == true) "RAW14" else "RAW"
        }
        val base = "RRAW_CAM${cameraId}_${mode}_${size.width}x${size.height}_$stamp"

        val payloadName: String
        var payloadBytes = -1L
        var rowStride = -1
        var pixelStride = -1

        if (option.dng) {
            payloadName = "$base.dng"
            val uri = createDownload(payloadName, "image/x-adobe-dng")
            contentResolver.openOutputStream(uri, "w")!!.use { out ->
                DngCreator(characteristics!!, result).use { dng -> dng.writeImage(out, image) }
            }
        } else {
            payloadName = "$base.raw"
            val plane = image.planes[0]
            rowStride = plane.rowStride
            pixelStride = plane.pixelStride
            val buffer = plane.buffer.duplicate()
            payloadBytes = buffer.remaining().toLong()
            val uri = createDownload(payloadName, "application/octet-stream")
            contentResolver.openOutputStream(uri, "w")!!.use { out -> writeBuffer(out, buffer) }
        }

        val meta = buildMetadataJson(image, result, cameraId, mode, payloadName, payloadBytes, rowStride, pixelStride)
        createDownload("$base.json", "application/json").let { uri ->
            contentResolver.openOutputStream(uri, "w")!!.bufferedWriter().use { it.write(meta.toString(2)) }
        }

        runOnUiThread {
            setStatus("Saved $payloadName + JSON to Downloads/RRAWLab")
            reportView.append("\n\n--- LAST CAPTURE ---\n${meta.toString(2)}\n")
        }
    }

    private fun buildMetadataJson(image: Image, result: TotalCaptureResult, cameraId: String, mode: String, payloadName: String, payloadBytes: Long, rowStride: Int, pixelStride: Int): JSONObject {
        val c = characteristics!!
        val black = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val json = JSONObject()
        json.put("project", "R-RAW Lab")
        json.put("phase", 1)
        json.put("cameraId", cameraId)
        json.put("format", mode)
        json.put("width", image.width)
        json.put("height", image.height)
        json.put("imageTimestampNs", image.timestamp)
        json.put("payloadFile", payloadName)
        if (payloadBytes >= 0) json.put("payloadBytes", payloadBytes)
        if (rowStride >= 0) json.put("rowStride", rowStride)
        if (pixelStride >= 0) json.put("pixelStride", pixelStride)
        json.put("cfa", cfaName(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)))
        json.put("blackLevel", blackLevelJson(black))
        json.put("whiteLevel", c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: JSONObject.NULL)
        json.put("sensorSensitivityIso", result.get(CaptureResult.SENSOR_SENSITIVITY) ?: JSONObject.NULL)
        json.put("exposureTimeNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: JSONObject.NULL)
        json.put("focusDistanceDiopters", result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: JSONObject.NULL)
        json.put("focalLengthMm", result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: JSONObject.NULL)
        json.put("sensorTimestampNs", result.get(CaptureResult.SENSOR_TIMESTAMP) ?: JSONObject.NULL)
        json.put("awbMode", result.get(CaptureResult.CONTROL_AWB_MODE) ?: JSONObject.NULL)
        json.put("colorCorrectionGains", result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.toString() ?: JSONObject.NULL)
        json.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        json.put("androidApi", Build.VERSION.SDK_INT)
        return json
    }

    private fun createDownload(name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RRAWLab")
        }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert returned null")
    }

    private fun writeBuffer(out: OutputStream, buffer: java.nio.ByteBuffer) {
        val chunk = ByteArray(1024 * 1024)
        while (buffer.hasRemaining()) {
            val n = max(1, minOf(buffer.remaining(), chunk.size))
            buffer.get(chunk, 0, n)
            out.write(chunk, 0, n)
        }
    }

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("R-RAW Lab report", reportView.text))
        setStatus("Capability report copied. Paste it into ChatGPT.")
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        imageReader?.close(); imageReader = null
        cameraDevice?.close(); cameraDevice = null
        pendingImage?.close(); pendingImage = null; pendingResult = null
    }

    private fun setStatus(message: String, error: Boolean = false) {
        runOnUiThread {
            statusView.text = message
            statusView.setTextColor(if (error) Color.rgb(255, 105, 120) else Color.rgb(0, 183, 194))
        }
    }

    private fun facingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun hardwareName(value: Int?): String = when (value) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun cfaName(value: Int?): String = when (value) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "NIR"
        else -> "UNKNOWN"
    }

    private fun blackLevelText(p: BlackLevelPattern?): String {
        if (p == null) return "unknown"
        return "[${p.getOffsetForIndex(0,0)}, ${p.getOffsetForIndex(1,0)}, ${p.getOffsetForIndex(0,1)}, ${p.getOffsetForIndex(1,1)}]"
    }

    private fun blackLevelJson(p: BlackLevelPattern?): JSONArray {
        val a = JSONArray()
        if (p != null) {
            a.put(p.getOffsetForIndex(0, 0)); a.put(p.getOffsetForIndex(1, 0)); a.put(p.getOffsetForIndex(0, 1)); a.put(p.getOffsetForIndex(1, 1))
        }
        return a
    }

    private fun rangeText(range: Range<*>?): String = range?.let { "${it.lower}..${it.upper}" } ?: "unknown"
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
