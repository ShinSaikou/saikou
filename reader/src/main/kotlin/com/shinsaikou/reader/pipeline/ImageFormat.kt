package com.shinsaikou.reader.pipeline

/**
 * The formats [ImageProbe] can name from a magic-byte header. Contract 07 section 4.5.
 *
 * The table is the one `com.shinsaikou.image.ImageUtil.ImageType` uses, plus [JP2],
 * which that enum does not carry (part 1b section 4.12 item 4), and [UNKNOWN], which stands for
 * "no signature matched" rather than for a format.
 */
enum class ImageFormat(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    GIF("image/gif", "gif"),
    AVIF("image/avif", "avif"),
    HEIF("image/heif", "heif"),
    JXL("image/jxl", "jxl"),
    JP2("image/jp2", "jp2"),
    UNKNOWN("application/octet-stream", "bin"),
    ;

    /**
     * AVIF, HEIF and JXL: formats the image view's own native decoder reads on every API level while
     * the platform reads them on some or none (HEIF from API 28, AVIF from 31, JXL never). Their
     * size comes from that decoder, and nothing that decodes through the platform, such as the
     * tall-strip split, is handed one.
     */
    val isNativeOnly: Boolean
        get() = this == AVIF || this == HEIF || this == JXL
}
