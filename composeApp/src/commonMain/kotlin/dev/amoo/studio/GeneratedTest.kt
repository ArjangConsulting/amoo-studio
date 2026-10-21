package dev.amoo.studio

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A compiled plan's note about a recorded action, mirroring Amoo's `StudioPlanWarning`. */
@Immutable
@Serializable
data class PlanWarning(
	val kind: PlanWarningKind,
	val actionIndex: Int,
	val toolName: String,
	val reason: String,
	val selector: String? = null,
)

@Serializable
enum class PlanWarningKind {
	@SerialName("excluded") Excluded,
	@SerialName("notApplicable") NotApplicable,
	@SerialName("approximate") Approximate,
	@SerialName("redacted") Redacted,
	@SerialName("preserved") Preserved;

	/** Excluded and redacted actions make the generated test incomplete or unrunnable. */
	val isSerious: Boolean get() = this == Excluded || this == Redacted
}

/** Where one plan operation landed in the generated source, 1-based inclusive lines. */
@Immutable
@Serializable
data class SourceMapEntry(val operationIndex: Int, val operationId: String, val startLine: Int, val endLine: Int)

@Immutable
@Serializable
data class GeneratedSource(val fileName: String, val source: String, val language: String, val sourceMap: List<SourceMapEntry> = emptyList())

@Immutable
@Serializable
data class GenerationProblem(val kind: GenerationProblemKind, val message: String, val warnings: List<PlanWarning> = emptyList())

@Serializable
enum class GenerationProblemKind { @SerialName("incomplete") Incomplete, @SerialName("redacted") Redacted, @SerialName("noEmitter") NoEmitter }

/** What generated code is made from. */
sealed interface GenerationSource {
	/** A recorded session, such as an agent run's. */
	data class Session(val sessionId: String, val title: String) : GenerationSource
	/** The test open in the Tests editor. */
	data object AuthoredTest : GenerationSource
}

@Immutable
data class GeneratedTestState(
	val source: GenerationSource,
	val loading: Boolean = true,
	val generated: GeneratedSource? = null,
	val problem: GenerationProblem? = null,
	/** The plan as generated: operations, steps, and warnings. */
	val test: AmooTest? = null,
	val selectedOperationId: String? = null,
	/** Step screenshots by operation id (`step-<n>`), from the agent run that recorded the session. */
	val stepScreenshots: Map<String, PinnedScreenshot> = emptyMap(),
	val allowIncomplete: Boolean = false,
	val uiToolkit: UiToolkit? = null,
	val testName: String = "",
) {
	val operations: List<ToolOperation> get() = test?.compiledPlan?.toolOperations.orEmpty()
	val warnings: List<PlanWarning> get() = test?.compiledPlan?.warnings.orEmpty()

	fun span(operationId: String): SourceMapEntry? = generated?.sourceMap?.firstOrNull { it.operationId == operationId }

	/** The operation whose generated lines contain [line] (1-based), for clicking in the source. */
	fun operationAt(line: Int): String? = generated?.sourceMap?.firstOrNull { line in it.startLine..it.endLine }?.operationId

	/** Warnings about the action an operation recorded (`step-<n>` names action n). */
	fun warningsFor(operationId: String): List<PlanWarning> {
		val actionIndex = operationId.removePrefix("step-").toIntOrNull() ?: return emptyList()
		return warnings.filter { it.actionIndex == actionIndex }
	}
}

@Serializable
enum class UiToolkit(val label: String) { @SerialName("view") View("Views"), @SerialName("compose") Compose("Compose") }
