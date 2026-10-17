package com.xiuyin.app.omr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import org.json.JSONObject

internal data class OmrPreparedImage(val bytes: ByteArray, val diagnostics: JSONObject)

/** Local decode only. Original document bytes remain separate from the bounded inference page. */
internal object OmrImagePreparation {
    fun prepare(bytes: ByteArray, progress: (String) -> Unit = {}): OmrPreparedImage {
        require(bytes.isNotEmpty()) { "图片为空，请重新选择原图" }
        require(bytes.size <= OmrImageSizing.MAX_IMAGE_BYTES) { "单张原图超过 20 MiB，请按页导入" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType != null) {
            "图片无法解码：文件可能损坏，或此手机不支持该格式；请另存为 JPG / PNG 后导入"
        }
        val mime = requireNotNull(bounds.outMimeType).lowercase()
        require(mime in setOf("image/jpeg", "image/png", "image/webp", "image/heif", "image/heic", "image/bmp", "image/x-ms-bmp")) {
            "暂不支持此图片格式（$mime）；请另存为 JPG / PNG，动图请先保存需要的单页"
        }
        require(!isAnimatedWebp(bytes, mime)) { "暂不支持动态 WebP；请先保存需要的单页为 JPG / PNG" }
        val plan = OmrImageSizing.plan(bounds.outWidth, bounds.outHeight)
        val hasExif = mime !in setOf("image/bmp", "image/x-ms-bmp")
        val orientation = if (hasExif) {
            try { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            catch (error: java.io.IOException) { throw IllegalArgumentException("无法读取图片方向信息；请另存为 JPG / PNG 后重试", error) }
        } else ExifInterface.ORIENTATION_NORMAL
        require(orientation in ExifInterface.ORIENTATION_UNDEFINED..ExifInterface.ORIENTATION_ROTATE_270) {
            "图片方向信息无效，请重新保存图片"
        }
        val rotate = orientation !in setOf(ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED)
        if (plan.sampleSize > 1) progress("原图 ${bounds.outWidth}×${bounds.outHeight}，正在生成有界识谱副本；原件保留…")
        NativeOmrModels.checkCancelled()
        val options = BitmapFactory.Options().apply { inSampleSize = plan.sampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("图片内容无法完整解码；请重新保存为 JPG / PNG 后导入")
        try {
            require(decoded.width >= OmrImageSizing.MIN_SIDE && decoded.height >= OmrImageSizing.MIN_SIDE &&
                decoded.width.toLong() * decoded.height <= OmrImageSizing.MAX_PIXELS) {
                "此手机解码后的尺寸超出安全范围；请按单页或谱表裁剪后导入"
            }
            val transformed = rotate || plan.sampleSize > 1
            val upright = if (rotate) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix(orientation), true) else decoded
            try {
                NativeOmrModels.checkCancelled()
                val pageBytes = if (!transformed) bytes else {
                    val output = BoundedImageOutput()
                    check(upright.compress(Bitmap.CompressFormat.PNG, 100, output)) { "保存识谱副本失败，请按单页导入" }
                    output.toByteArray()
                }
                val diagnostics = JSONObject().put("method", if (transformed) "BOUNDED_DECODE_AND_EXIF_V1" else "ORIGINAL_BYTES_UNCHANGED")
                    .put("sourceMimeType", mime).put("sourceWidth", bounds.outWidth).put("sourceHeight", bounds.outHeight)
                    .put("exifOrientation", orientation).put("exifApplicable", hasExif).put("exifApplied", rotate)
                    .put("decodeSampleSize", plan.sampleSize).put("decodedWidth", decoded.width).put("decodedHeight", decoded.height)
                    .put("outputWidth", upright.width).put("outputHeight", upright.height)
                    .put("outputMimeType", if (transformed) "image/png" else mime)
                    .put("sourceDocumentModified", false).put("inferencePageModified", transformed)
                    .put("coordinateSystem", "PREPARED_INFERENCE_PAGE_PIXELS").put("cropApplied", false)
                    .put("maxInferencePixels", OmrImageSizing.MAX_PIXELS).put("musicAccuracy", "UNVERIFIED")
                return OmrPreparedImage(pageBytes, diagnostics)
            } finally { if (upright !== decoded) upright.recycle() }
        } finally { decoded.recycle() }
    }

    private fun matrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }

    private fun isAnimatedWebp(bytes: ByteArray, mime: String): Boolean = mime == "image/webp" && bytes.size >= 21 &&
        bytes.copyOfRange(12, 16).contentEquals("VP8X".toByteArray(Charsets.US_ASCII)) && (bytes[20].toInt() and 2) != 0

    private class BoundedImageOutput : ByteArrayOutputStream() {
        override fun write(value: Int) {
            require(count < OmrImageSizing.MAX_IMAGE_BYTES) { "识谱副本超过 20 MiB，请按谱表裁剪；原件未修改" }
            super.write(value)
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(length <= OmrImageSizing.MAX_IMAGE_BYTES - count) { "识谱副本超过 20 MiB，请按谱表裁剪；原件未修改" }
            super.write(buffer, offset, length)
        }
    }
}
