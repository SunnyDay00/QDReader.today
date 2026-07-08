package today.qdreader.auto.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import today.qdreader.auto.accessibility.ScreenPoint
import kotlin.math.roundToInt

class OpenCvAdCloseButtonDetector(
    private val context: Context
) : CloseButtonDetector {
    override suspend fun detectCloseButton(
        bitmap: Bitmap,
        threshold: Double
    ): Result<CloseButtonMatch?> = withContext(Dispatchers.Default) {
        runCatching {
            if (!OpenCVLoader.initLocal()) {
                error("OpenCV 初始化失败")
            }

            val templateNames = context.assets.list(TEMPLATE_DIR)
                ?.filter { it.endsWith(".png", ignoreCase = true) }
                .orEmpty()
            val roi = topRightRoi(bitmap.width, bitmap.height)
            val source = Mat()
            val sourceGray = Mat()
            val roiGray = Mat()
            val roiEdges = Mat()

            try {
                Utils.bitmapToMat(bitmap, source)
                Imgproc.cvtColor(source, sourceGray, Imgproc.COLOR_RGBA2GRAY)
                val cvRoi = org.opencv.core.Rect(roi.left, roi.top, roi.width(), roi.height())
                sourceGray.submat(cvRoi).copyTo(roiGray)
                Imgproc.Canny(roiGray, roiEdges, 60.0, 160.0)

                val templateMatch = templateNames
                    .mapNotNull { templateName ->
                        context.assets.open("$TEMPLATE_DIR/$templateName").use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }?.let { template -> matchTemplate(roiGray, roiEdges, roi, template, templateName) }
                    }
                    .filter { it.score >= threshold }
                    .maxWithOrNull(compareBy<CloseButtonMatch> { it.score }.thenBy { it.bounds.centerX() })

                templateMatch ?: detectGeometricCloseButton(roiGray, roi, bitmap.width)
            } finally {
                source.release()
                sourceGray.release()
                roiGray.release()
                roiEdges.release()
            }
        }
    }

    private fun matchTemplate(
        roiGray: Mat,
        roiEdges: Mat,
        roi: Rect,
        template: Bitmap,
        templateName: String
    ): CloseButtonMatch? {
        val templateMat = Mat()
        val templateGray = Mat()
        val scaled = Mat()
        val scaledEdges = Mat()
        val result = Mat()

        try {
            Utils.bitmapToMat(template, templateMat)
            Imgproc.cvtColor(templateMat, templateGray, Imgproc.COLOR_RGBA2GRAY)

            var best: CloseButtonMatch? = null
            TEMPLATE_SCALES.forEach { scale ->
                val targetWidth = (template.width * scale).roundToInt()
                val targetHeight = (template.height * scale).roundToInt()
                if (targetWidth < MIN_TEMPLATE_SIZE || targetHeight < MIN_TEMPLATE_SIZE) return@forEach
                if (targetWidth > roiGray.cols() || targetHeight > roiGray.rows()) return@forEach

                Imgproc.resize(templateGray, scaled, Size(targetWidth.toDouble(), targetHeight.toDouble()))
                Imgproc.Canny(scaled, scaledEdges, 60.0, 160.0)
                Imgproc.matchTemplate(roiEdges, scaledEdges, result, Imgproc.TM_CCOEFF_NORMED)
                val minMax = Core.minMaxLoc(result)
                val left = roi.left + minMax.maxLoc.x.roundToInt()
                val top = roi.top + minMax.maxLoc.y.roundToInt()
                val bounds = Rect(left, top, left + targetWidth, top + targetHeight)
                val match = CloseButtonMatch(
                    point = ScreenPoint(bounds.exactCenterX(), bounds.exactCenterY()),
                    bounds = bounds,
                    score = minMax.maxVal,
                    templateName = "$templateName@${"%.2f".format(scale)}"
                )
                if (best == null || match.score > best!!.score) {
                    best = match
                }
            }
            return best
        } finally {
            templateMat.release()
            templateGray.release()
            scaled.release()
            scaledEdges.release()
            result.release()
            template.recycle()
        }
    }

    private fun detectGeometricCloseButton(
        roiGray: Mat,
        roi: Rect,
        screenWidth: Int
    ): CloseButtonMatch? {
        val circles = Mat()
        return try {
            Imgproc.HoughCircles(
                roiGray,
                circles,
                Imgproc.HOUGH_GRADIENT,
                1.2,
                28.0,
                120.0,
                18.0,
                MIN_GEOMETRIC_RADIUS,
                MAX_GEOMETRIC_RADIUS
            )

            val matches = (0 until circles.cols()).mapNotNull { index ->
                val circle = circles.get(0, index) ?: return@mapNotNull null
                if (circle.size < 3) return@mapNotNull null

                val centerX = roi.left + circle[0].roundToInt()
                val centerY = roi.top + circle[1].roundToInt()
                val radius = circle[2].roundToInt()
                if (centerX.toFloat() < screenWidth * GEOMETRIC_MIN_X_FRACTION) return@mapNotNull null
                if (centerY.toFloat() > roi.height() * GEOMETRIC_MAX_Y_FRACTION) return@mapNotNull null

                val bounds = Rect(
                    centerX - radius,
                    centerY - radius,
                    centerX + radius,
                    centerY + radius
                )
                CloseButtonMatch(
                    point = ScreenPoint(centerX.toFloat(), centerY.toFloat()),
                    bounds = bounds,
                    score = GEOMETRIC_FALLBACK_SCORE,
                    templateName = "geometric_top_right_circle"
                )
            }

            matches.maxByOrNull { it.bounds.centerX() }
        } finally {
            circles.release()
        }
    }

    private fun topRightRoi(width: Int, height: Int): Rect {
        val left = (width * 0.68f).roundToInt()
        val bottom = (height * 0.22f).roundToInt()
        return Rect(left, 0, width, bottom)
    }

    companion object {
        private const val TEMPLATE_DIR = "templates/ad_close"
        private const val MIN_TEMPLATE_SIZE = 18
        private const val MIN_GEOMETRIC_RADIUS = 14
        private const val MAX_GEOMETRIC_RADIUS = 38
        private const val GEOMETRIC_MIN_X_FRACTION = 0.84f
        private const val GEOMETRIC_MAX_Y_FRACTION = 0.60f
        private const val GEOMETRIC_FALLBACK_SCORE = 0.60
        private val TEMPLATE_SCALES = listOf(0.75, 0.9, 1.0, 1.15, 1.3)
    }
}
