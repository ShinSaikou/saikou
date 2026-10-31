package com.shinsaikou.reader.pipeline

/**
 * Pixel budget for one decode. [width] and [height] are the viewport in px; [maxPixels] caps the
 * product and is a hard ceiling, not a preference. Contract 07 section 4.6.
 */
data class TargetSize(val width: Int, val height: Int, val maxPixels: Long)

/**
 * The one sample-size rule for the whole module: the largest power of two whose result still
 * covers [target] in at least one dimension, raised further while the result would exceed
 * `target.maxPixels`. The pixel budget wins, so the rounding can only make a bitmap smaller.
 *
 * [width] and [height] are the dimensions of what is actually being decoded, which for a region
 * decode is the region rather than the image (part 1b section 4.12 item 2).
 *
 * Its one production caller is `AnimatedImagePolicy`, through `CappedAnimatedDecoder` (ruling R23).
 * Section 4.6's `ImageDecoder` seam, which was the other caller, is deleted: the image view fork
 * decodes inside itself and has no pluggable decoder, so the seam had no surface to attach to.
 */
fun sampleSizeFor(width: Int, height: Int, target: TargetSize): Int {
    if (width <= 0 || height <= 0) return 1
    val targetWidth = target.width.coerceAtLeast(1)
    val targetHeight = target.height.coerceAtLeast(1)

    var sample = 1
    while (width / (sample * 2) >= targetWidth || height / (sample * 2) >= targetHeight) {
        sample *= 2
    }
    while (target.maxPixels > 0 && (width / sample).toLong() * (height / sample) > target.maxPixels) {
        sample *= 2
    }
    return sample
}
