package com.example.faceswap

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private var imageUri: Uri? = null
    private var videoUri: Uri? = null
    private var resultUri: Uri? = null
    private var worker: Thread? = null
    @Volatile private var cancelled = false

    // Swap range (milliseconds into the video).
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L

    // Frame previews are decoded on one background thread; the retriever is only used there.
    private val previewExecutor = Executors.newSingleThreadExecutor()
    private var previewRetriever: MediaMetadataRetriever? = null
    private var previewTicket = 0

    private lateinit var imagePreview: ImageView
    private lateinit var videoPreview: ImageView
    private lateinit var pickImageBtn: Button
    private lateinit var pickVideoBtn: Button
    private lateinit var swapBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var playBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var trimGroup: View
    private lateinit var startBar: SeekBar
    private lateinit var endBar: SeekBar
    private lateinit var startLabel: TextView
    private lateinit var endLabel: TextView
    private lateinit var keepRestCheck: CheckBox
    private lateinit var expressionBar: SeekBar
    private lateinit var expressionLabel: TextView

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            imageUri = uri
            try { imagePreview.setImageBitmap(loadBitmap(this, uri, 512)) }
            catch (e: Exception) { status.text = "Can't open that image." }
            playBtn.visibility = View.GONE
            updateButtons()
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            videoUri = uri
            playBtn.visibility = View.GONE
            trimGroup.visibility = View.GONE
            videoPreview.setImageDrawable(null)
            updateButtons()
            previewExecutor.execute {
                try {
                    previewRetriever?.release()
                    val r = MediaMetadataRetriever()
                    r.setDataSource(this, uri)
                    previewRetriever = r
                    val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    val first = r.getFrameAtTime(0)
                    runOnUiThread {
                        if (dur <= 0L) {
                            status.text = "Can't read that video."
                        } else {
                            durationMs = dur
                            startMs = 0L
                            endMs = dur
                            startBar.max = dur.toInt()
                            endBar.max = dur.toInt()
                            startBar.progress = 0
                            endBar.progress = dur.toInt()
                            updateLabels()
                            trimGroup.visibility = View.VISIBLE
                            if (first != null) videoPreview.setImageBitmap(first)
                            status.text = "Drag the sliders to choose which part to swap."
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Can't open that video." }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imagePreview = findViewById(R.id.imagePreview)
        videoPreview = findViewById(R.id.videoPreview)
        pickImageBtn = findViewById(R.id.pickImageBtn)
        pickVideoBtn = findViewById(R.id.pickVideoBtn)
        swapBtn = findViewById(R.id.swapBtn)
        cancelBtn = findViewById(R.id.cancelBtn)
        playBtn = findViewById(R.id.playBtn)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        trimGroup = findViewById(R.id.trimGroup)
        startBar = findViewById(R.id.startBar)
        endBar = findViewById(R.id.endBar)
        startLabel = findViewById(R.id.startLabel)
        endLabel = findViewById(R.id.endLabel)
        keepRestCheck = findViewById(R.id.keepRestCheck)
        expressionBar = findViewById(R.id.expressionBar)
        expressionLabel = findViewById(R.id.expressionLabel)
        expressionBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                expressionLabel.text = "Follow expressions: $p%"
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        bindBar(startBar, isStart = true)
        bindBar(endBar, isStart = false)

        pickImageBtn.setOnClickListener { pickImage.launch("image/*") }
        pickVideoBtn.setOnClickListener { pickVideo.launch("video/*") }
        swapBtn.setOnClickListener { startSwap() }
        cancelBtn.setOnClickListener { cancelled = true; status.text = "Cancelling…" }
        playBtn.setOnClickListener {
            resultUri?.let {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(it, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
        }
    }

    override fun onDestroy() {
        cancelled = true
        previewExecutor.execute { try { previewRetriever?.release() } catch (_: Exception) {} }
        previewExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindBar(bar: SeekBar, isStart: Boolean) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val gap = min(300L, durationMs / 2)
                if (isStart) {
                    startMs = min(p.toLong(), endMs - gap).coerceAtLeast(0L)
                    startBar.progress = startMs.toInt()
                } else {
                    endMs = max(p.toLong(), startMs + gap).coerceAtMost(durationMs)
                    endBar.progress = endMs.toInt()
                }
                updateLabels()
                showFrame(if (isStart) startMs else endMs)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun updateLabels() {
        startLabel.text = "Start: ${formatTime(startMs)}"
        endLabel.text = "End: ${formatTime(endMs)}   (clip length ${formatTime(endMs - startMs)})"
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000.0
        val minutes = (totalSec / 60).toInt()
        val sec = totalSec - minutes * 60
        return String.format(Locale.US, "%d:%04.1f", minutes, sec)
    }

    private fun showFrame(ms: Long) {
        val ticket = ++previewTicket
        previewExecutor.execute {
            val bmp = try {
                previewRetriever?.getFrameAtTime(
                    ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            } catch (_: Exception) { null }
            if (bmp != null) {
                runOnUiThread { if (ticket == previewTicket) videoPreview.setImageBitmap(bmp) }
            }
        }
    }

    private fun updateButtons() {
        val busy = worker != null
        swapBtn.isEnabled = !busy && imageUri != null && videoUri != null && durationMs > 0
        pickImageBtn.isEnabled = !busy
        pickVideoBtn.isEnabled = !busy
        startBar.isEnabled = !busy
        endBar.isEnabled = !busy
        keepRestCheck.isEnabled = !busy
        expressionBar.isEnabled = !busy
        cancelBtn.visibility = if (busy) View.VISIBLE else View.GONE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun startSwap() {
        val img = imageUri ?: return
        val vid = videoUri ?: return
        val rangeStart = startMs
        val rangeEnd = endMs
        val keepRest = keepRestCheck.isChecked
        val expression = expressionBar.progress / 100f
        cancelled = false
        playBtn.visibility = View.GONE
        progress.progress = 0
        status.text = "Preparing…"

        worker = Thread {
            var out: Uri? = null
            var message: String
            var success = false
            try {
                val swapper = FaceSwapper(loadBitmap(this, img, 1280))
                swapper.expressionStrength = expression
                try {
                    if (!swapper.sourceHasFace()) {
                        message = "No face found in the image. Try a clearer, front-facing photo."
                    } else {
                        out = createOutputUri()
                        val done = VideoProcessor(this).process(
                            vid, swapper, out, rangeStart, rangeEnd, keepRest,
                            { p -> runOnUiThread { progress.progress = p; status.text = "Processing… $p%" } },
                            { cancelled }
                        )
                        if (done) {
                            val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                            contentResolver.update(out, v, null, null)
                            resultUri = out
                            success = true
                            message = "Done. Saved to Movies/FaceSwap."
                        } else {
                            contentResolver.delete(out, null, null)
                            message = "Cancelled."
                        }
                    }
                } finally {
                    swapper.close()
                }
            } catch (e: Throwable) {
                out?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                message = "Failed: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                worker = null
                status.text = message
                playBtn.visibility = if (success) View.VISIBLE else View.GONE
                updateButtons()
            }
        }.also { it.start() }
        updateButtons()
    }

    private fun createOutputUri(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "faceswap_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FaceSwap")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Can't create output file")
    }

    private fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val s = maxSide.toFloat() / longest
                decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }
    }
}
