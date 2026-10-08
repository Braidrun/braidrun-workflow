package com.fartech.agents.runner

import com.fartech.agents.workflow.*
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.Locale

internal object RunnerCollectionProtocol {
    const val CAPABILITY = "artifact.collection.v1"
    const val COMMIT = "b2a0e01b301a76f108f06e2b92e4ba8dfa26e7bd"
    const val SHA256 = "26eee80c21e982a56d8d96897ca688c68d628e9935ec0839d34de8d89ddb66fa"
    private fun bytes() = javaClass.getResourceAsStream("/runner-workflow-v1/artifact-collection.schema.json")?.use { it.readBytes() }
    fun available() = bytes()?.let { sha256(it) == SHA256 } == true
    private val validator by lazy {
        val source = requireNotNull(bytes()); require(sha256(source) == SHA256)
        RunnerProtocol(runnerJson.parseToJsonElement(source.toString(Charsets.UTF_8)).jsonObject)
    }
    fun validate(frame: JsonElement, definition: String) = validator.validate(frame, definition)
    fun parse(text: String, definition: String) = validator.parse(text, definition)
}

/** Only the exact manifest-hashed P carrier creates declarations. No claimant file discovery. */
class RunnerCollectionPolicy(private val bundle: FrozenWorkflowBundle) {
    companion object { const val PATH = "runtime/runner-artifact-policy.v1.json" }
    private val original = bundle.runtimeBytes(PATH)
    val hash = sha256(original)
    private val policy = RunnerCollectionProtocol.parse(original.toString(Charsets.UTF_8), "policy")
    private val declarations = policy.array("declarations").map { it.jsonObject }
    private data class Node(val name: String, val producer: Boolean, val code: Boolean, val child: SubWorkflowConfig?)
    private fun nodes(workflowId: String): List<Node> = bundle.definition(workflowId).workflow.flatMap { step ->
        listOf(Node(step.step, step.code != null || step.agent != null, step.code != null, step.subWorkflow)) + step.stateMachine?.states?.flatMap { (name, state) ->
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
        val tuples = HashSet<String>(); val aliases = HashMap<String, String>(); val paths = mutableSetOf<String>()
        declarations.forEach { declaration ->
            val workflow = declaration.string("producerWorkflowId"); val step = declaration.string("producerStep")
            val logical = declaration.array("childSteps").map { it.jsonPrimitive.content }
            require(logicalResolve(logical) == workflow && nodes(workflow).any { it.name == step && it.producer }) { "Declaration producer is not a frozen code/agent step" }
            val path = declaration.string("logicalPath")
            require(path.startsWith("run/") && ':' !in path && '\\' !in path && path.none { it.isISOControl() })
            require(path.split('/').all { it.isNotBlank() && !it.startsWith('.') })
            confinedPath(bundle.executionDirectory, path)
            val key = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            require(aliases.putIfAbsent(key, path)?.let { it == path } != false) { "Output path normalization alias" }
            paths += path
            require(tuples.add(canonical(buildJsonArray { add(workflow); add(step); add(JsonArray(logical.map(::JsonPrimitive))); add(path) })))
            val source = declaration["codeSourceSha256"]
            when (declaration.string("sourcePolicy")) {
                "UNAVAILABLE" -> require(source == JsonNull)
                "VERIFIED_CURRENT" -> require(source is JsonPrimitive && source.isString && source.content !in bundle.definitionHashes()) { "Source attestation cannot be a definition hash" }
                else -> error("Source policy invalid")
            }
        }
        require(paths.none { parent -> paths.any { it.startsWith("$parent/") } }) { "File/directory output collision" }
    }
    fun verify() { require(sha256(bundle.runtimeBytes(PATH)) == hash) { "Frozen declaration policy changed" } }
    fun validateRequest(body: JsonObject): Pair<JsonObject, String> {
        verify()
        require(body.string("policySha256") == hash)
        val declaration = declarations.single { it.string("declarationId") == body.string("declarationId") }
        val path = body.array("childExecutionPath").map { it.jsonPrimitive.content }
        val resolved = actualResolve(path)
        require(resolved.first == declaration.string("producerWorkflowId") && resolved.second == declaration.array("childSteps").map { it.jsonPrimitive.content })
        require(body.string("producerWorkflowId") == resolved.first && body.string("producerStep") == declaration.string("producerStep"))
        require(body.string("logicalPath") == declaration.string("logicalPath") && body.long("size") in 0..declaration.long("maximumBytes"))
        require(body["codeSourceSha256"] == declaration["codeSourceSha256"])
        confinedPath(bundle.executionDirectory, declaration.string("logicalPath")) // S exclusively reads/snapshots bytes.
        val execution = bundle.taskBinding.string("executionId") + if (path.isEmpty()) "" else "__" + path.joinToString("__")
        return declaration to execution
    }
    fun declarationsFor(workflowId: String, executionId: String, step: String): List<String> {
        val candidates = declarations.filter { it.string("producerWorkflowId") == workflowId && it.string("producerStep") == step }
        if (candidates.isEmpty()) return emptyList()
        val actual = childPath(bundle.taskBinding.string("executionId"), executionId).map { it.jsonPrimitive.content }
        val resolved = actualResolve(actual)
        require(resolved.first == workflowId)
        return candidates.filter { it.array("childSteps").map { part -> part.jsonPrimitive.content } == resolved.second }.map { it.string("declarationId") }
    }
    fun isCode(workflowId: String, step: String) = nodes(workflowId).single { it.name == step }.code
}
