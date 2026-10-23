package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.Locale

internal object RunnerArtifactSetProtocol {
    const val CAPABILITY = "artifact.set.v1"
    const val GATES = "control.gateSets.v1"
    const val SOURCE = "source.attestation.v1"
    const val COMMIT = "d349d5d269364f6967863952257aac069821c00e"
    const val SEMANTIC_COMMIT = "cb53a03fd75d938792664e7af88d1a6e2d8f6c6b"
    const val SHA256 = "4b254106f3ed68bedb1bb058cddf0deba74c7a643ed9f4bb59f57b7dcf1323ff"
    private fun bytes() = javaClass.getResourceAsStream("/runner-workflow-v1/artifact-set.schema.json")?.use { it.readBytes() }
    fun available() = bytes()?.let { sha256(it) == SHA256 } == true
    private val validator by lazy {
        val raw = requireNotNull(bytes()); require(sha256(raw) == SHA256)
        RunnerProtocol(runnerJson.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject)
    }
    fun validate(value: JsonElement, definition: String) = validator.validate(value, definition)
    fun parse(value: String, definition: String) = validator.parse(value, definition)
}

/** Frozen selectors are metadata authority only. This class never enumerates or reads business paths. */
class RunnerArtifactSetPolicy(private val bundle: FrozenWorkflowBundle) {
    companion object { const val PATH = "runtime/runner-artifact-sets.v1.json" }
    private val original = bundle.runtimeBytes(PATH)
    val hash = sha256(original)
    private val policy = RunnerArtifactSetProtocol.parse(original.toString(Charsets.UTF_8), "policy")
    private val declarations = policy.array("declarations").map { it.jsonObject }
    private data class Node(val name: String, val producer: Boolean, val code: Boolean, val child: SubWorkflowConfig?)
    private fun nodes(workflowId: String): List<Node> = bundle.definition(workflowId).workflow.flatMap { step ->
        // A loop step is never a producer: its items run concurrently under one step name (RunnerLoopPolicy).
        listOf(Node(step.step, (step.code != null || step.agent != null) && step.iterateOver == null && step.repeatUntil == null, step.code != null, step.subWorkflow)) + step.stateMachine?.states?.flatMap { (name, state) ->
            state.stepConfig?.let { listOf(Node("${step.step}.$name", it.code != null || it.agent != null, it.code != null, it.subWorkflow)) }.orEmpty()
        }.orEmpty()
    }.also { require(it.map { n -> n.name }.distinct().size == it.size) { "Ambiguous frozen step names" } }
    private fun edges(id: String): Map<String, Pair<String, String>> {
        val values = nodes(id).filter { it.child != null }.map { node ->
            val segment = node.name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            require('/' !in node.name && "__" !in segment && segment !in setOf("", ".", ".."))
            segment to (node.name to bundle.resolve(node.child!!.toReference()).identity)
        }
        require(values.map { it.first }.distinct().size == values.size) { "Frozen child aliases" }
        return values.toMap()
    }
    private fun logicalResolve(steps: List<String>): String {
        var id = bundle.rootWorkflowId
        steps.forEach { step -> id = edges(id).values.single { it.first == step }.second }
        return id
    }
    private fun actualResolve(path: List<String>): Pair<String, List<String>> {
        require(path.size % 2 == 0)
        var id = bundle.rootWorkflowId; val logical = mutableListOf<String>()
        path.chunked(2).forEach { pair ->
            require(pair[1].matches(Regex("call_[1-9][0-9]*")) && pair[1].removePrefix("call_").toLongOrNull()?.let { it > 0 } == true)
            val edge = edges(id)[pair[0]] ?: error("Actual child escaped frozen graph")
            logical += edge.first; id = edge.second
        }
        return id to logical
    }

    init {
        require(policy.string("rootWorkflowId") == bundle.rootWorkflowId)
        require(declarations.map { it.string("declarationId") }.distinct().size == declarations.size)
        val old = if (bundle.hasFile(RunnerCollectionPolicy.PATH)) RunnerCollectionProtocol.parse(bundle.runtimeBytes(RunnerCollectionPolicy.PATH).toString(Charsets.UTF_8), "policy").array("declarations").map { it.jsonObject } else emptyList()
        require(declarations.none { d -> old.any { it.string("declarationId") == d.string("declarationId") } }) { "Declaration exists in both carriers" }
        val tuples = HashSet<String>(); val aliases = HashMap<String, String>()
        declarations.forEach { d ->
            val producer = d.obj("producer"); val workflow = producer.string("workflowId"); val step = producer.string("stepName")
            val logical = producer.array("childSteps").map { it.jsonPrimitive.content }
            require(logicalResolve(logical) == workflow && nodes(workflow).any { it.name == step && it.producer })
            val selector = d.obj("selector")
            for (field in listOf("path", "manifestPath", "allowedParent")) selector[field]?.let {
                val path = it.jsonPrimitive.content
                require(path.startsWith("run/") && ':' !in path && '\\' !in path && path.none { c -> c.isISOControl() })
                val parts = path.split('/'); val leaf = parts.last().lowercase(Locale.ROOT)
                require(parts.all { part -> part.isNotBlank() && !part.startsWith('.') })
                require(parts.none { part -> part.lowercase(Locale.ROOT) in setOf("credentials", "secrets", "keychains") })
                require(!Regex("(?i)(credential|secret|private.?key|service.?account)").containsMatchIn(leaf))
                require(leaf !in setOf("auth.json", "credentials.json") && leaf.substringAfterLast('.', "") !in setOf("p8", "p12", "pfx", "pem", "key", "keychain", "keychain-db"))
                val alias = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                require(aliases.putIfAbsent(alias, path)?.let { previous -> previous == path } != false)
            }
            if (selector.string("kind") == "TREE") {
                val extensions = selector.array("extensions").map { it.jsonPrimitive.content }
                require(extensions.distinct().size == extensions.size && extensions.all { it.matches(Regex("\\.[A-Za-z0-9]{1,15}")) })
            }
            if (selector.string("kind") == "JSON_FILE") {
                val pointer = selector.string("jsonPointer")
                require(d.long("maximumFiles") == 1L && pointer.length in 2..256 && pointer.startsWith('/') && !Regex("~(?![01])").containsMatchIn(pointer))
            }
            else if (selector.string("kind") == "FILE") require(d.long("maximumFiles") == 1L)
            require(d.long("maximumFileBytes") <= d.long("maximumTotalBytes"))
            require(tuples.add(canonical(buildJsonObject { put("producer", producer); put("selector", selector) })))
            require(old.none { o -> o.string("producerWorkflowId") == workflow && o.string("producerStep") == step && o.array("childSteps") == producer.array("childSteps") &&
                (o.string("logicalPath") == (selector["path"]?.jsonPrimitive?.content ?: selector.string("allowedParent")) || (selector.string("kind") != "FILE" && o.string("logicalPath").startsWith((selector["path"]?.jsonPrimitive?.content ?: selector.string("allowedParent")) + "/"))) }) { "Exact-file/set selector overlap" }
        }
    }
    fun verify() { require(sha256(bundle.runtimeBytes(PATH)) == hash) }
    fun declarationsFor(workflowId: String, executionId: String, step: String): List<String> {
        verify()
        val resolved = actualResolve(childPath(bundle.taskBinding.string("executionId"), executionId).map { it.jsonPrimitive.content })
        require(resolved.first == workflowId)
        return declarations.filter { d -> val p = d.obj("producer"); p.string("workflowId") == workflowId && p.string("stepName") == step && p.array("childSteps").map { it.jsonPrimitive.content } == resolved.second }.map { it.string("declarationId") }
    }
    fun hasDiagnostics(ids: List<String>) = declarations.any { it.string("declarationId") in ids && it.string("purpose") == "DIAGNOSTIC" }
    fun isCode(workflowId: String, step: String) = nodes(workflowId).single { it.name == step }.code
}
