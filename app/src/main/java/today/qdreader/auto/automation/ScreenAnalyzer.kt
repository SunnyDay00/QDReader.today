package today.qdreader.auto.automation

import today.qdreader.auto.accessibility.AccessibilityBridge
import today.qdreader.auto.accessibility.UiTreeSnapshot
import today.qdreader.auto.vision.OcrEngine
import today.qdreader.auto.vision.OcrResult
import today.qdreader.auto.vision.TemplateMatchResult
import today.qdreader.auto.vision.TemplateMatcher
import kotlin.system.measureTimeMillis

data class ScreenAnalysis(
    val uiTree: UiTreeSnapshot?,
    val ocrResult: OcrResult?,
    val templateMatchResult: TemplateMatchResult?,
    val elapsedMillis: Long
)

class ScreenAnalyzer(
    private val bridge: AccessibilityBridge,
    private val ocrEngine: OcrEngine,
    private val templateMatcher: TemplateMatcher
) {
    suspend fun analyzeCurrentScreen(
        runOcr: Boolean = true,
        runTemplateMatching: Boolean = true
    ): Result<ScreenAnalysis> {
        return runCatching {
            var uiTree: UiTreeSnapshot? = null
            var ocrResult: OcrResult? = null
            var templateMatchResult: TemplateMatchResult? = null
            val elapsed = measureTimeMillis {
                uiTree = bridge.readActiveWindow()
                val screenshot = bridge.captureScreenshot().getOrThrow()
                if (runOcr) {
                    ocrResult = ocrEngine.recognize(screenshot).getOrThrow()
                }
                if (runTemplateMatching) {
                    templateMatchResult = templateMatcher.matchAny(screenshot).getOrThrow()
                }
            }
            ScreenAnalysis(
                uiTree = uiTree,
                ocrResult = ocrResult,
                templateMatchResult = templateMatchResult,
                elapsedMillis = elapsed
            )
        }
    }
}
