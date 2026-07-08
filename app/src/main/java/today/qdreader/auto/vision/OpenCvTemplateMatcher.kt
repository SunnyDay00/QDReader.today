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
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

class OpenCvTemplateMatcher(
    private val context: Context
) : TemplateMatcher {
    override suspend fun matchAny(bitmap: Bitmap, threshold: Double): Result<TemplateMatchResult> {
        return withContext(Dispatchers.Default) {
            runCatching {
                if (!OpenCVLoader.initLocal()) {
                    error("OpenCV 初始化失败")
                }

                val templates = context.assets.list(TEMPLATE_DIR)
                    ?.filter { it.endsWith(".png", ignoreCase = true) }
                    .orEmpty()

                if (templates.isEmpty()) {
                    return@runCatching TemplateMatchResult(
                        templateName = null,
                        matched = false,
                        score = 0.0,
                        threshold = threshold,
                        bounds = null,
                        elapsedMillis = 0,
                        message = "assets/templates 中没有 PNG 模板"
                    )
                }

                var best: TemplateMatchResult? = null
                val elapsed = measureTimeMillis {
                    templates.forEach { templateName ->
                        val templateBitmap = context.assets.open("$TEMPLATE_DIR/$templateName").use {
                            BitmapFactory.decodeStream(it)
                        }
                        val result = matchSingle(bitmap, templateBitmap, templateName, threshold)
                        if (best == null || result.score > best!!.score) {
                            best = result
                        }
                    }
                }

                best?.copy(elapsedMillis = elapsed)
                    ?: TemplateMatchResult(
                        templateName = null,
                        matched = false,
                        score = 0.0,
                        threshold = threshold,
                        bounds = null,
                        elapsedMillis = elapsed,
                        message = "没有可用模板"
                    )
            }
        }
    }

    private fun matchSingle(
        screenshot: Bitmap,
        template: Bitmap,
        templateName: String,
        threshold: Double
    ): TemplateMatchResult {
        val source = Mat()
        val sourceGray = Mat()
        val target = Mat()
        val targetGray = Mat()
        val result = Mat()

        try {
            Utils.bitmapToMat(screenshot, source)
            Utils.bitmapToMat(template, target)
            Imgproc.cvtColor(source, sourceGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(target, targetGray, Imgproc.COLOR_RGBA2GRAY)

            if (targetGray.cols() > sourceGray.cols() || targetGray.rows() > sourceGray.rows()) {
                return TemplateMatchResult(
                    templateName = templateName,
                    matched = false,
                    score = 0.0,
                    threshold = threshold,
                    bounds = null,
                    elapsedMillis = 0,
                    message = "模板尺寸大于截图"
                )
            }

            Imgproc.matchTemplate(sourceGray, targetGray, result, Imgproc.TM_CCOEFF_NORMED)
            val minMax = Core.minMaxLoc(result)
            val left = minMax.maxLoc.x.roundToInt()
            val top = minMax.maxLoc.y.roundToInt()
            val score = minMax.maxVal

            return TemplateMatchResult(
                templateName = templateName,
                matched = score >= threshold,
                score = score,
                threshold = threshold,
                bounds = Rect(left, top, left + template.width, top + template.height),
                elapsedMillis = 0,
                message = if (score >= threshold) "匹配成功" else "最高分低于阈值"
            )
        } finally {
            source.release()
            sourceGray.release()
            target.release()
            targetGray.release()
            result.release()
        }
    }

    companion object {
        private const val TEMPLATE_DIR = "templates"
    }
}
