package net.darkrelay.microdeck

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.net.Uri
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ln
import kotlin.math.exp

/**
 * MicroDeck v1: Camera2 preview on the logical back camera with a right-side
 * control panel. Lenses are forced with physical-camera streams
 * (OutputConfiguration.setPhysicalCameraId); if a physical stream won't
 * configure, that lens falls back to the logical camera + zoom ratio.
 * Every capture setting lives in the fields below and is rebuilt into one
 * repeating request by [applyRequest].
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MicroDeck"
        private const val REQ_CAMERA = 1
        private val PREVIEW_SIZE = Size(1920, 1080)
        private const val OPEN_WAIT_MS = 2500L
        private const val UPDATE_CHECK_DELAY_MS = 3000L
    }

    /** One lens button: physical id to force, and the logical zoom ratio to fall back on. */
    private data class Lens(val name: String, val focal: Float, val fallbackZoom: Float) {
        var physId: String? = null
        var minFocus = 0f                       // diopters
        var zoomRange = Range(1f, 1f)
    }

    private val lenses = listOf(
        Lens("Macro", 2.23f, 0.5f),
        Lens("Main", 6.9f, 1f),
        Lens("Tele", 18f, 5f),
    )

    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var cameraManager: CameraManager
    private var logical: CameraCharacteristics? = null   // null when the device has no back camera
    private var physKeys: Set<CaptureRequest.Key<*>> = emptySet()

    private var camThread: HandlerThread? = null
    private var camHandler: Handler? = null

    // Open/close handshake between the UI thread and the camera thread. onOpened can
    // land after onPause has started closing, so camera, opening and cameraWanted are
    // only changed under camLock, and closeCamera waits for an in-flight open to finish.
    private val camLock = ReentrantLock()
    private val openDone = camLock.newCondition()
    @Volatile private var camera: CameraDevice? = null
    private var opening = false                 // an openCamera call is waiting for its callback
    private var cameraWanted = false            // false once onPause has asked to close
    private var resumed = false                 // UI thread only
    private var lastDisplayRotation = -1
    private var permissionDenied = false        // denied: tap the status or preview to retry
    private var permissionBlocked = false       // "don't ask again": the tap opens app settings
    private var frameSeen = false               // a camera frame has reached the preview since the session began
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var cameraId: String? = null

    // -- control state --
    private var lens = lenses[1]
    private var physicalMode = false            // true = current session forces lens.physId
    private val physFailed = mutableSetOf<String>()
    private var zoom = 1f
    private var afOn = true
    private var focusDiopters = 0f
    private var torchOn = false
    private var hasFlash = false
    private var torch = TorchLevels.ON_OFF       // real strength range of the opened camera
    private var torchLevel = 1
    private var ev = 0
    private var awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var awbLock = false
    private var frozen = false
    private var locked = false
    private var imageRotation = 0               // user rotation of the preview image only

    // last values reported by the camera (for the status line / AF hand-off)
    @Volatile private var lastFocus: Float? = null
    @Volatile private var lastActivePhys: String? = null
    @Volatile private var lastFlashState: Int? = null
    private var lastStatusAt = 0L
    private var lastSig = ""                    // logs a result line only when settings change

    // -- views --
    private lateinit var zoomBar: SeekBar
    private lateinit var zoomLabel: TextView
    private lateinit var focusBar: SeekBar
    private lateinit var focusLabel: TextView
    private lateinit var afSwitch: CompoundButton
    private lateinit var torchLabel: TextView
    private lateinit var evLabel: TextView
    private lateinit var lensButtons: List<Button>
    private lateinit var rotButtons: List<Button>
    private lateinit var controls: List<View>   // everything the lock disables
    private lateinit var snapButton: Button
    private lateinit var updateBar: UpdateBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        cameraManager = getSystemService(CameraManager::class.java)

        cameraId = backCameraId()
        cameraId?.let { setupLenses(it) }
        logCapabilities()
        bindControls()
        updateBar = UpdateBar(this, findViewById(R.id.update_bar))
        if (cameraId == null) status.setText(R.string.no_back_camera)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        updateBar.refresh()
        maybeCheckForUpdate()
        if (cameraId == null) return            // nothing to open; status already says why
        resumed = true
        // a 180 degree flip keeps the same size, so no configuration change fires for it;
        // the display listener is the documented way to catch it
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
        lastDisplayRotation = display?.rotation ?: -1
        startCamThread()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // after a denial, wait for a tap instead of asking again on every resume
            if (permissionDenied) showPermissionRetry()
            else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        permissionDenied = false                // granted, maybe from the settings page
        updateSnap()
        if (preview.isAvailable) openCamera() else preview.surfaceTextureListener = surfaceListener
    }

    override fun onPause() {
        resumed = false
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        closeCamera()
        stopCamThread()
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_CAMERA) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false
            updateSnap()
            status.text = ""
            if (preview.isAvailable) openCamera() else preview.surfaceTextureListener = surfaceListener
        } else {
            permissionDenied = true
            updateSnap()
            // no rationale after a real denial means "don't ask again"; a dismissed
            // dialog comes back with empty results and can still be asked again
            permissionBlocked = results.isNotEmpty() &&
                !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            showPermissionRetry()
        }
    }

    private fun showPermissionRetry() {
        status.text = if (permissionBlocked) "Camera permission denied. Tap to open settings"
                      else "Camera permission denied. Tap to retry"
    }

    private fun retryPermission() {
        if (permissionBlocked) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    /** Once per launch, a few seconds in so the camera starts first, if the About switch allows it. */
    private fun maybeCheckForUpdate() {
        if (Updates.startupCheckDone || !Updates.checkAtStartup(this)) return
        Updates.startupCheckDone = true
        preview.postDelayed({
            Updates.check(this) { if (!isFinishing && !isDestroyed) updateBar.refresh() }
        }, UPDATE_CHECK_DELAY_MS)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lastDisplayRotation = display?.rotation ?: -1
        configureTransform(preview.width, preview.height)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            val d = display ?: return
            if (id != d.displayId || d.rotation == lastDisplayRotation) return
            lastDisplayRotation = d.rotation
            configureTransform(preview.width, preview.height)
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = configureTransform(w, h)
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
            if (!frameSeen) { frameSeen = true; updateSnap() }
        }
    }

    // -- lens discovery --

    private fun backCameraId(): String? = try {
        cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (e: Exception) {
        Log.w(TAG, "camera list failed", e)
        null
    }

    /** Match each lens button to the first physical camera with that focal length. */
    private fun setupLenses(id: String) {
        val logical = cameraManager.getCameraCharacteristics(id)
        this.logical = logical
        physKeys = logical.availablePhysicalCameraRequestKeys?.toSet() ?: emptySet()
        Log.i(TAG, "physical request keys: ${physKeys.map { it.name }}")
        val physIds = logical.physicalCameraIds.sorted()
        for (l in lenses) {
            val match = physIds.firstOrNull { pid ->
                val c = cameraManager.getCameraCharacteristics(pid)
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                kotlin.math.abs(f - l.focal) < 0.5f && supports1080(c)
            }
            val c = if (match != null) cameraManager.getCameraCharacteristics(match) else logical
            l.physId = match
            l.minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            l.zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
            Log.i(TAG, "lens ${l.name} -> physical $match minFocus=${l.minFocus} zoom=${l.zoomRange}")
        }
        // the flash belongs to the logical camera we open, so its strength range is read there
        hasFlash = logical.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        torch = if (hasFlash && Build.VERSION.SDK_INT >= 35) TorchLevels.from(
            logical.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL),
            logical.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL),
        ) else TorchLevels.ON_OFF
        torchLevel = torch.default
        Log.i(TAG, "flash=$hasFlash torch levels=$torch")
    }

    private fun supports1080(c: CameraCharacteristics): Boolean {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return map.getOutputSizes(SurfaceTexture::class.java)?.contains(PREVIEW_SIZE) == true
    }

    /** Zoom range the slider uses: the physical lens's own range, or the logical one in fallback. */
    private fun activeZoomRange(): Range<Float> =
        if (physicalMode) lens.zoomRange
        else logical?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)

    // -- camera --

    private fun openCamera() {
        val id = cameraId ?: run { status.setText(R.string.no_back_camera); return }
        val handler = camHandler ?: return
        camLock.withLock {
            if (camera != null || opening) return
            opening = true
            cameraWanted = true
        }
        configureTransform(preview.width, preview.height)
        try {
            cameraManager.openCamera(id, stateCallback, handler)
        } catch (e: SecurityException) {
            openFailed(e, "Camera permission missing")
        } catch (e: Exception) {
            openFailed(e, "Camera unavailable")
        }
    }

    private fun openFailed(e: Exception, message: String) {
        camLock.withLock { opening = false; cameraWanted = false; openDone.signalAll() }
        Log.w(TAG, "openCamera failed", e)
        status.text = message
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(dev: CameraDevice) {
            val keep = camLock.withLock {
                opening = false
                openDone.signalAll()
                if (cameraWanted) camera = dev
                cameraWanted
            }
            if (!keep) {
                // the activity paused while this open was in flight: never leave it open
                Log.i(TAG, "camera opened after pause, closing it")
                dev.close()
                quitIfOrphaned()
                // if we were resumed while that stale open was pending, open a fresh one now
                runOnUiThread { if (resumed && preview.isAvailable) openCamera() }
                return
            }
            runOnUiThread { startSession() }
        }
        override fun onDisconnected(dev: CameraDevice) {
            dropDevice(dev)
            runOnUiThread { status.text = "Camera disconnected" }
        }
        override fun onError(dev: CameraDevice, error: Int) {
            dropDevice(dev)
            runOnUiThread { status.text = "Camera error $error" }
        }
    }

    private fun dropDevice(dev: CameraDevice) {
        camLock.withLock {
            opening = false
            openDone.signalAll()
            if (camera === dev) camera = null
        }
        dev.close()
        quitIfOrphaned()
    }

    /** A camera thread left running by a timed-out close quits once its late callback is handled. */
    private fun quitIfOrphaned() {
        val looper = Looper.myLooper() ?: return
        if (looper != camThread?.looper) looper.quitSafely()
    }

    /** (Re)build the capture session for the current lens. */
    private fun startSession() {
        val dev = camera ?: return
        val st = preview.surfaceTexture ?: return
        session?.close()
        session = null
        frameSeen = false
        updateSnap()
        st.setDefaultBufferSize(PREVIEW_SIZE.width, PREVIEW_SIZE.height)
        val surf = surface ?: Surface(st).also { surface = it }

        val pid = lens.physId?.takeIf { it !in physFailed }
        val out = OutputConfiguration(surf)
        if (pid != null) out.setPhysicalCameraId(pid)
        val cfg = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, listOf(out), mainExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera !== dev) { s.close(); return }   // closed while configuring
                    session = s
                    updateSnap()
                    physicalMode = pid != null
                    zoom = if (physicalMode) 1f else lens.fallbackZoom
                    syncSliders()
                    applyRequest()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (pid != null) {
                        Log.w(TAG, "physical stream $pid failed; falling back to zoom ratio")
                        physFailed += pid
                        startSession()
                    } else {
                        status.text = "Preview session failed"
                    }
                }
            })
        if (pid != null) {
            val ok = try { dev.isSessionConfigurationSupported(cfg) } catch (e: Exception) { true }
            Log.i(TAG, "physical $pid session supported=$ok")
            if (!ok) {
                physFailed += pid
                startSession()
                return
            }
        }
        dev.createCaptureSession(cfg)
    }

    /** Set a key on the request, and on the forced physical camera if it accepts it. */
    private fun <T> CaptureRequest.Builder.put(key: CaptureRequest.Key<T>, value: T) {
        set(key, value)
        val pid = if (physicalMode) lens.physId else null
        if (pid != null && key in physKeys) setPhysicalCameraKey(key, value, pid)
    }

    /**
     * Rebuild the repeating request from the control state. If the camera
     * rejects the torch strength, send it again with plain on/off torch so
     * zoom, focus, EV and white balance still apply.
     */
    private fun applyRequest() {
        if (!submitRequest(withTorchStrength = true)) submitRequest(withTorchStrength = false)
    }

    /** Returns false only when the torch strength was what got rejected. */
    private fun submitRequest(withTorchStrength: Boolean): Boolean {
        val dev = camera ?: return true
        val s = session ?: return true
        val surf = surface ?: return true
        val pid = if (physicalMode) lens.physId else null
        val strength = withTorchStrength && torchOn && torch.adjustable && Build.VERSION.SDK_INT >= 35
        try {
            val b = if (pid != null) dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW, setOf(pid))
                    else dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b.addTarget(surf)
            b.put(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            if (afOn) {
                b.put(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                b.put(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.put(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
            }
            // torch needs plain AE (not an auto-flash mode)
            b.put(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.put(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev)
            b.put(CaptureRequest.CONTROL_AWB_MODE, awbMode)
            b.put(CaptureRequest.CONTROL_AWB_LOCK, awbLock)
            if (torchOn) {
                b.put(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                if (strength) b.put(CaptureRequest.FLASH_STRENGTH_LEVEL, torch.clamp(torchLevel))
            } else {
                b.put(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            if (frozen) s.capture(b.build(), resultCallback, camHandler)   // apply once, stay paused
            else s.setRepeatingRequest(b.build(), resultCallback, camHandler)
        } catch (e: IllegalArgumentException) {
            if (strength) {
                Log.w(TAG, "torch strength ${torch.clamp(torchLevel)} rejected, retrying with plain torch", e)
                return false
            }
            Log.w(TAG, "request failed", e)
        } catch (e: Exception) {
            Log.w(TAG, "request failed", e)
        }
        return true
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val pid = if (physicalMode) lens.physId else null
            val pr = pid?.let { result.physicalCameraTotalResults[it] }
            lastFocus = pr?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            lastActivePhys = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            lastFlashState = result.get(CaptureResult.FLASH_STATE)
            val strength = if (Build.VERSION.SDK_INT >= 35) result.get(CaptureResult.FLASH_STRENGTH_LEVEL) else null
            val sig = "lens=${lens.name} forced=${pid ?: "-"} active=$lastActivePhys " +
                "zoom=${pr?.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: result.get(CaptureResult.CONTROL_ZOOM_RATIO)} " +
                "af=${result.get(CaptureResult.CONTROL_AF_MODE)} flash=$lastFlashState strength=$strength " +
                "ev=${result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)} " +
                "awb=${result.get(CaptureResult.CONTROL_AWB_MODE)}/${result.get(CaptureResult.CONTROL_AWB_LOCK)}"
            if (sig != lastSig) { lastSig = sig; Log.i(TAG, "result $sig focus=$lastFocus") }
            val now = SystemClock.uptimeMillis()
            if (now - lastStatusAt > 250) {
                lastStatusAt = now
                runOnUiThread { updateStatus() }
            }
        }
    }

    private fun updateStatus() {
        val mode = if (physicalMode) "forced ${lens.physId}" else "zoom fallback"
        val f = lastFocus?.let { if (it > 0f) "%.1f cm".format(100f / it) else "∞" } ?: "?"
        val t = when {
            !torchOn -> ""
            torch.adjustable -> "  torch ${torch.clamp(torchLevel)}"
            else -> "  torch on"
        }
        val fz = if (frozen) "  FROZEN" else ""
        status.text = "${lens.name} ($mode, active ${lastActivePhys ?: "?"})  " +
            "%.2f×  focus %s%s%s".format(zoom, f, t, fz)
        if (afOn) focusLabel.text = "Focus  auto ($f)"
    }

    /**
     * Close everything. If an open is still in flight, wait (bounded) for its
     * callback so the device can't be handed over after we've gone; if the wait
     * times out, onOpened sees cameraWanted == false and closes it there.
     */
    private fun closeCamera() {
        val dev = camLock.withLock {
            cameraWanted = false
            var left = TimeUnit.MILLISECONDS.toNanos(OPEN_WAIT_MS)
            while (opening && left > 0) left = openDone.awaitNanos(left)
            if (opening) Log.w(TAG, "camera open still pending after ${OPEN_WAIT_MS} ms")
            camera.also { camera = null }
        }
        session?.close(); session = null
        dev?.close()
        surface?.release(); surface = null
        frameSeen = false
        updateSnap()
    }

    /** Snap only when a real frame is on screen (see [SnapGate]). */
    private fun snapReady() = SnapGate.ready(camera != null, session != null, frameSeen, permissionDenied)

    private fun updateSnap() {
        if (::snapButton.isInitialized) snapButton.isEnabled = SnapGate.buttonEnabled(snapReady(), locked)
    }

    private fun startCamThread() {
        camThread = HandlerThread("cam").also { it.start() }
        camHandler = Handler(camThread!!.looper)
    }

    private fun stopCamThread() {
        // with an open still pending the thread must live to deliver onOpened, which then
        // closes the device and quits this looper itself (see quitIfOrphaned)
        val stillOpening = camLock.withLock { opening }
        if (!stillOpening) camThread?.quitSafely()
        camThread = null
        camHandler = null
    }

    /**
     * Map the preview buffer onto the TextureView without distortion, for any
     * display rotation (phone screen = 90, PhoneDeck's virtual display = 0),
     * plus the user's image rotation. The camera already rotates the buffer by
     * the sensor orientation, so it arrives as a portrait 1080x1920 image that
     * TextureView stretches across the view; undo that stretch, rotate, then
     * scale to fit (black bars, never cropped or stretched).
     */
    private fun configureTransform(viewW: Int, viewH: Int) {
        if (viewW == 0 || viewH == 0) return
        val displayDeg = 90 * (display?.rotation ?: Surface.ROTATION_0)
        val angle = ((imageRotation - displayDeg) % 360 + 360) % 360
        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        val bufW = PREVIEW_SIZE.height.toFloat()     // portrait after sensor rotation
        val bufH = PREVIEW_SIZE.width.toFloat()
        val bufRect = RectF(0f, 0f, bufW, bufH)
        bufRect.offset(cx - bufRect.centerX(), cy - bufRect.centerY())
        val matrix = Matrix()
        matrix.setRectToRect(viewRect, bufRect, Matrix.ScaleToFit.FILL)
        val (outW, outH) = if (angle % 180 == 0) bufW to bufH else bufH to bufW
        val scale = minOf(viewW / outW, viewH / outH)
        matrix.postScale(scale, scale, cx, cy)
        matrix.postRotate(angle.toFloat(), cx, cy)
        preview.setTransform(matrix)
    }

    private fun setImageRotation(deg: Int) {
        imageRotation = deg
        getPreferences(MODE_PRIVATE).edit().putInt("image_rotation", deg).apply()
        rotButtons.forEachIndexed { i, b -> b.alpha = if (i * 90 == deg) 1f else 0.55f }
        configureTransform(preview.width, preview.height)
    }

    // -- controls --

    private fun bindControls() {
        val panel = findViewById<View>(R.id.panel)
        val collapse = findViewById<Button>(R.id.collapse)
        collapse.setOnClickListener {
            val show = panel.visibility != View.VISIBLE
            panel.visibility = if (show) View.VISIBLE else View.GONE
            collapse.text = if (show) "▶" else "◀"
        }

        zoomBar = findViewById(R.id.zoom)
        zoomLabel = findViewById(R.id.zoom_label)
        focusBar = findViewById(R.id.focus)
        focusLabel = findViewById(R.id.focus_label)
        afSwitch = findViewById(R.id.af)
        torchLabel = findViewById(R.id.torch_label)
        evLabel = findViewById(R.id.ev_label)

        lensButtons = listOf(R.id.lens_macro, R.id.lens_main, R.id.lens_tele).mapIndexed { i, id ->
            findViewById<Button>(id).apply {
                setOnClickListener {
                    if (lenses[i] != lens) {
                        lens = lenses[i]
                        highlightLens()
                        startSession()
                    }
                }
            }
        }
        highlightLens()

        rotButtons = listOf(R.id.rot_0, R.id.rot_90, R.id.rot_180, R.id.rot_270).mapIndexed { i, id ->
            findViewById<Button>(id).apply { setOnClickListener { setImageRotation(i * 90) } }
        }
        setImageRotation(getPreferences(MODE_PRIVATE).getInt("image_rotation", 0))

        // zoom: log scale across the active range
        zoomBar.setOnSeekBarChangeListener(seek { p ->
            val r = activeZoomRange()
            setZoom(r.lower * exp(ln(r.upper / r.lower) * p / 1000f), moveSlider = false)
        })

        // pinch on the preview drives the same zoom as the slider (phone-screen use)
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(zoom * d.scaleFactor, moveSlider = true)
                return true
            }
        })
        preview.setOnTouchListener { _, e: MotionEvent ->
            if (permissionDenied) {
                if (e.actionMasked == MotionEvent.ACTION_UP) retryPermission()
            } else if (!locked) {
                pinch.onTouchEvent(e)
            }
            true
        }
        status.setOnClickListener { if (permissionDenied) retryPermission() }

        afSwitch.setOnCheckedChangeListener { _, on ->
            afOn = on
            focusBar.isEnabled = !on
            if (!on) {
                // hand off from AF: start manual focus where AF had it
                focusDiopters = (lastFocus ?: 0f).coerceIn(0f, lens.minFocus)
                focusBar.progress = focusProgress()
                showFocus()
            }
            applyRequest()
        }
        focusBar.setOnSeekBarChangeListener(seek { p ->
            focusDiopters = lens.minFocus * p / 1000f
            showFocus()
            applyRequest()
        })

        val torchSwitch = findViewById<CompoundButton>(R.id.torch)
        val torchBar = findViewById<SeekBar>(R.id.torch_level)
        torchSwitch.setOnCheckedChangeListener { _, on ->
            torchOn = on
            applyRequest()
        }
        // slider range comes from the device; on/off-only torches get no slider, no flash gets nothing
        torchBar.max = torch.max - 1
        torchBar.progress = torch.toProgress(torchLevel)
        showTorchLevel()
        torchBar.setOnSeekBarChangeListener(seek { p ->
            torchLevel = torch.fromProgress(p)
            showTorchLevel()
            applyRequest()
        })
        if (!torch.adjustable) {
            torchBar.visibility = View.GONE
            torchLabel.visibility = View.GONE
        }
        if (!hasFlash) torchSwitch.visibility = View.GONE

        val evStep = logical?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 6)
        val evRange = logical?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        findViewById<SeekBar>(R.id.ev).apply {
            max = evRange.upper - evRange.lower
            progress = -evRange.lower
            setOnSeekBarChangeListener(seek { p ->
                ev = p + evRange.lower
                evLabel.text = "Exposure  %+.1f EV".format(ev * evStep)
                applyRequest()
            })
        }

        setupWhiteBalance()
        findViewById<CompoundButton>(R.id.awb_lock).setOnCheckedChangeListener { _, on ->
            awbLock = on
            applyRequest()
        }

        findViewById<ToggleButton>(R.id.freeze).setOnCheckedChangeListener { _, on -> setFrozen(on) }
        snapButton = findViewById(R.id.snap)
        snapButton.setOnClickListener { snapshot() }
        findViewById<Button>(R.id.about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        val grid = findViewById<View>(R.id.grid)
        findViewById<ToggleButton>(R.id.grid_toggle).setOnCheckedChangeListener { _, on ->
            grid.visibility = if (on) View.VISIBLE else View.GONE
        }

        val lock = findViewById<ToggleButton>(R.id.lock)
        controls = collectControls(findViewById(R.id.panel)).filter { it !== lock }
        lock.setOnCheckedChangeListener { _, on -> setLocked(on) }
        updateSnap()                            // disabled until the first frame arrives
    }

    private fun setupWhiteBalance() {
        val names = mapOf(
            CaptureRequest.CONTROL_AWB_MODE_AUTO to "Auto",
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "Incandescent",
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent",
            CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT to "Warm fluorescent",
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy",
            CaptureRequest.CONTROL_AWB_MODE_TWILIGHT to "Twilight",
            CaptureRequest.CONTROL_AWB_MODE_SHADE to "Shade",
        )
        val avail = logical?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet() ?: emptySet()
        val modes = names.keys.filter { it in avail }
        val spinner = findViewById<Spinner>(R.id.wb)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            modes.map { names.getValue(it) })
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (awbMode != modes[pos]) {
                    awbMode = modes[pos]
                    applyRequest()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setFrozen(on: Boolean) {
        frozen = on
        val s = session ?: return
        if (on) {
            try { s.stopRepeating() } catch (e: Exception) { Log.w(TAG, "stopRepeating", e) }
        } else {
            applyRequest()
        }
        updateStatus()
    }

    /** The one zoom setter: slider and pinch both land here, clamped to the active lens. */
    private fun setZoom(z: Float, moveSlider: Boolean) {
        val r = activeZoomRange()
        zoom = z.coerceIn(r.lower, r.upper)
        zoomLabel.text = "Zoom  %.2f×".format(zoom)
        if (moveSlider) zoomBar.progress = zoomProgress()
        applyRequest()
    }

    private fun zoomProgress(): Int {
        val r = activeZoomRange()
        return (1000f * ln(zoom / r.lower) / ln(r.upper / r.lower)).toInt().coerceIn(0, 1000)
    }

    private fun setLocked(on: Boolean) {
        locked = on
        controls.forEach { it.isEnabled = !on }
        if (!on) {
            focusBar.isEnabled = !afOn
            updateSnap()
        }
    }

    private fun collectControls(v: View): List<View> = when {
        v is ViewGroup && v !is Spinner -> (0 until v.childCount).flatMap { collectControls(v.getChildAt(it)) }
        v is Button || v is SeekBar || v is Spinner -> listOf(v)
        else -> emptyList()
    }

    /** Save what's on screen (preview resolution) to Pictures/MicroDeck. */
    private fun snapshot() {
        // before the first frame getBitmap() returns a blank bitmap, not null: save nothing
        val bmp = if (snapReady()) preview.bitmap else null
        if (bmp == null) {
            snapshotFailed("no preview frame to save", null)
            return
        }
        val name = "MicroDeck_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MicroDeck")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            snapshotFailed("MediaStore insert threw", e)
            return
        }
        if (uri == null) {
            snapshotFailed("MediaStore insert returned null", null)
            return
        }
        try {
            val written = contentResolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
            } ?: false
            if (!written) throw java.io.IOException("JPEG not written")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}   // drop the half-made entry
            snapshotFailed("saving $name failed", e)
            return
        }
        Log.i(TAG, "snapshot saved $name ${bmp.width}x${bmp.height}")
        Toast.makeText(this, "Saved $name", Toast.LENGTH_SHORT).show()
    }

    private fun snapshotFailed(why: String, e: Exception?) {
        Log.w(TAG, "snapshot failed: $why", e)
        Toast.makeText(this, "Snapshot failed", Toast.LENGTH_SHORT).show()
    }

    private fun highlightLens() {
        lensButtons.forEachIndexed { i, b -> b.alpha = if (lenses[i] == lens) 1f else 0.55f }
    }

    /** Put the sliders back in step with the state after a lens change. */
    private fun syncSliders() {
        zoomBar.progress = zoomProgress()
        zoomLabel.text = "Zoom  %.2f×".format(zoom)
        focusDiopters = focusDiopters.coerceIn(0f, lens.minFocus)
        focusBar.progress = focusProgress()
        if (!afOn) showFocus()
    }

    private fun showTorchLevel() {
        torchLabel.text = getString(R.string.torch_brightness, torch.clamp(torchLevel), torch.max)
    }

    private fun focusProgress() =
        if (lens.minFocus > 0f) (1000f * focusDiopters / lens.minFocus).toInt() else 0

    private fun showFocus() {
        focusLabel.text = if (focusDiopters <= 0f) "Focus  ∞"
                          else "Focus  ~%.1f cm  (%.2f D)".format(100f / focusDiopters, focusDiopters)
    }

    private fun seek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    // -- capability dump (logcat tag MicroDeck) --

    private fun logCapabilities() {
        try {
            for (id in cameraManager.cameraIdList) {
                val c = cameraManager.getCameraCharacteristics(id)
                Log.i(TAG, "camera $id facing=${c.get(CameraCharacteristics.LENS_FACING)} " +
                    "physical=${c.physicalCameraIds}")
                for (pid in c.physicalCameraIds) {
                    val p = cameraManager.getCameraCharacteristics(pid)
                    Log.i(TAG, "  physical $pid focal=${p.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()}" +
                        " minFocus=${p.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)}" +
                        " zoom=${p.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)}" +
                        " 1080=${supports1080(p)}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "capability dump failed", e)
        }
    }
}
