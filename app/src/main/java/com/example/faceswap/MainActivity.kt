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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private var imageUri: Uri? = null
    private var videoUri: Uri? = null
    private var resultUri: Uri? = null
    private var worker: Thread? = null
    @Volatile private var cancelled = false

    private lateinit var imagePreview: ImageView
    private lateinit var videoPreview: ImageView
    private lateinit var pickImageBtn: Button
    private lateinit var pickVideoBtn: Button
    private lateinit var swapBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var playBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

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
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                videoPreview.setImageBitmap(r.getFrameAtTime(0))
                r.release()
            } catch (e: Exception) { status.text = "Can't open that video." }
            playBtn.visibility = View.GONE
            updateButtons()
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
        super.onDestroy()
    }

    private fun updateButtons() {
        val busy = worker != null
        swapBtn.isEnabled = !busy && imageUri != null && videoUri != null
        pickImageBtn.isEnabled = !busy
        pickVideoBtn.isEnabled = !busy
        cancelBtn.visibility = if (busy) View.VISIBLE else View.GONE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun startSwap() {
        val img = imageUri ?: return
        val vid = videoUri ?: return
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
                try {
                    if (!swapper.sourceHasFace()) {
                        message = "No face found in the image. Try a clearer, front-facing photo."
                    } else {
                        out = createOutputUri()
                        val done = VideoProcessor(this).process(
                            vid, swapper, out,
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
