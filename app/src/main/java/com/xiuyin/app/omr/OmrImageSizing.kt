package com.xiuyin.app.omr

/** Admission and power-of-two decode planning. The full camera image is never decoded first. */
internal object OmrImageSizing {
    const val MAX_PIXELS = 8_000_000L
    const val MIN_SIDE = 160
    const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

    data class Plan(val sourceWidth: Int, val sourceHeight: Int, val sampleSize: Int,
        val maximumDecodedWidth: Int, val maximumDecodedHeight: Int)

    fun plan(width: Int, height: Int): Plan {
        require(width >= MIN_SIDE && height >= MIN_SIDE) {
            "谱图至少需要 160×160 像素；请选清晰的完整谱页"
        }
        var sample = 1
        fun sampled(side: Int): Long = (side.toLong() + sample - 1) / sample
        while (sampled(width) * sampled(height) > MAX_PIXELS) {
            require(sample <= (1 shl 29)) { "图片尺寸异常，无法安全解码；请重新保存图片" }
            sample *= 2
        }
        val decodedWidth = sampled(width).toInt()
        val decodedHeight = sampled(height).toInt()
        require(decodedWidth >= MIN_SIDE && decodedHeight >= MIN_SIDE) {
            "图片过于狭长，安全缩小后谱线会过小；请分成单页或谱表导入"
        }
        return Plan(width, height, sample, decodedWidth, decodedHeight)
    }
}
