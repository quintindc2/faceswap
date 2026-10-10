package com.example.faceswap

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.faceswap.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val swapper by lazy { FaceSwapper() }

    private var source: FaceSwapper.Source? = null
    private var videoUri: Uri? = null
    private var resultUri: Uri? = null
    private var startMs = 0L
    private var endMs = 0L
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImage(uri)
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadVideo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.rangeSlider.valueFrom = 0f
        b.rangeSlider.valueTo = 1f
        b.rangeSlider.values = listOf(0f, 1f)
        b.rangeSlider.isEnabled = false

        b.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        b.btnPickVideo.setOnClickListener { pickVideo.launch("video/*") }

        b.rangeSlider.addOnChangeListener { slider, value, fromUser ->
            val v = slider.values
            startMs = (v[0] * 1000).toLong()
            endMs = (v[1] * 1000).toLong()
            updateRangeText()
            if (fromUser) b.videoView.seekTo((value * 1000).toInt())
        }
        b.btnSetStart.setOnClickListener {
            if (videoUri == null) return@setOnClickListener
            val v = b.rangeSlider.values
            val now = (b.videoView.currentPosition / 1000f).coerceIn(0f, v[1])
            b.rangeSlider.values = listOf(now, v[1])
        }
        b.btnSetEnd.setOnClickListener {
            if (videoUri == null) return@setOnClickListener
            val v = b.rangeSlider.values
            val now = (b.videoView.currentPosition / 1000f).coerceIn(v[0], b.rangeSlider.valueTo)
            b.rangeSlider.values = listOf(v[0], now)
        }
        b.btnPlayRange.setOnClickListener { playRange() }

        b.sliderExpr.addOnChangeListener { _, value, _ ->
            val pct = value.toInt()
            val note = when {
                pct == 0 -> "copy video's expression"
                pct == 100 -> "keep image's expression"
                else -> "mix"
            }
            b.tvExpr.text = "4. Expression: $pct% ($note)"
        }

        b.btnSwap.setOnClickListener { startSwap() }
        b.btnCancel.setOnClickListener { job?.cancel() }
        b.btnOpenResult.setOnClickListener {
            val uri = resultUri ?: return@setOnClickListener
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    // ------------------------------------------------------------------ picking

    private fun loadImage(uri: Uri) {
        status("Loading image…")
        lifecycleScope.launch {
            try {
                val prepared = withContext(Dispatchers.Default) {
                    val bmp = decodeImage(uri)
                    bmp to swapper.prepareSource(bmp)
                }
                val (bmp, src) = prepared
                b.ivFace.setImageBitmap(bmp)
                source = src
                if (src == null) {
                    status("No face found in that image. Try a clear, front-facing photo.")
                } else {
                    status("Face found. Now choose a video.")
                }
            } catch (e: Exception) {
                source = null
                status("Could not load image: ${e.message}")
            }
        }
    }

    private fun decodeImage(uri: Uri): Bitmap {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val s = min(1f, 1280f / max(w, h))
            decoder.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun loadVideo(uri: Uri) {
        videoUri = uri
        resultUri = null
        b.btnOpenResult.visibility = android.view.View.GONE
        b.videoView.setOnPreparedListener { mp ->
            mp.isLooping = false
            val dur = b.videoView.duration / 1000f
            if (dur > 0f) {
                b.rangeSlider.isEnabled = true
                b.rangeSlider.valueFrom = 0f
                b.rangeSlider.valueTo = dur
                b.rangeSlider.values = listOf(0f, dur)
            }
            b.videoView.seekTo(1)
            status("Video loaded. Set the start/end if you only want part of it.")
        }
        b.videoView.setVideoURI(uri)
    }

    private fun updateRangeText() {
        b.tvRange.text = "3. Range: %.1fs → %.1fs  (%.1fs long)".format(
            startMs / 1000f, endMs / 1000f, (endMs - startMs) / 1000f
        )
    }

    private fun playRange() {
        if (videoUri == null) return
        b.videoView.seekTo(startMs.toInt())
        b.videoView.start()
        val check = object : Runnable {
            override fun run() {
                if (!b.videoView.isPlaying) return
                if (b.videoView.currentPosition >= endMs) {
                    b.videoView.pause()
                } else {
                    handler.postDelayed(this, 80)
                }
            }
        }
        handler.postDelayed(check, 200)
    }

    // ------------------------------------------------------------------ processing

    private fun startSwap() {
        val src = source
        if (src == null) { toast("Choose an image with a detectable face first."); return }
        val uri = videoUri
        if (uri == null) { toast("Choose a video first."); return }
        if (endMs - startMs < 300) { toast("The selected range is too short."); return }
        b.videoView.pause()
        val expression = b.sliderExpr.value / 100f
        setBusy(true)
        job = lifecycleScope.launch {
            try {
                val tmp = withContext(Dispatchers.Default) {
                    VideoProcessor(this@MainActivity, swapper).process(uri, src, startMs, endMs, expression) { pct, text ->
                        b.progress.post {
                            b.progress.progress = pct
                            b.tvStatus.text = "Swapping… $pct%  ($text)"
                        }
                    }
                }
                status("Saving…")
                val saved = withContext(Dispatchers.IO) { saveToMovies(tmp) }
                resultUri = saved
                b.btnOpenResult.visibility = android.view.View.VISIBLE
                status("Done! Saved to Movies/FaceSwap.")
                b.videoView.setVideoURI(saved)
                b.videoView.setOnPreparedListener { b.videoView.seekTo(1) }
            } catch (e: CancellationException) {
                status("Cancelled.")
                throw e
            } catch (e: Throwable) {
                status("Failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun saveToMovies(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "faceswap_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/FaceSwap")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create output file")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        file.delete()
        return uri
    }

    private fun setBusy(busy: Boolean) {
        b.progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        b.progress.progress = 0
        b.btnCancel.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        b.btnSwap.isEnabled = !busy
        b.btnPickImage.isEnabled = !busy
        b.btnPickVideo.isEnabled = !busy
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun status(text: String) {
        b.tvStatus.text = text
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        handler.removeCallbacksAndMessages(null)
        swapper.close()
    }
}
