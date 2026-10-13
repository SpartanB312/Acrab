package net.evrt.acrab.analysis.android

import com.android.aapt.Resources
import com.google.protobuf.CodedInputStream
import com.google.protobuf.InvalidProtocolBufferException
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

internal data class ParsedManifest(
    val path: String?,
    val parser: String,
    val packageName: String?,
    val versionCode: String?,
    val permissions: List<String>,
    val declaredPermissions: List<AndroidDeclaredPermissionInfo>,
    val features: List<AndroidUsesFeatureInfo>,
    val components: List<AndroidManifestComponentInfo>,
    val warnings: List<String>,
    val versionName: String? = null,
    val minSdk: String? = null,
    val targetSdk: String? = null,
    val compileSdk: String? = null,
    val application: AndroidManifestApplicationInfo? = null,
    val modules: List<AndroidManifestModuleInfo> = emptyList(),
) {
    fun summary(projectId: String, sha: String, archive: String) = AndroidManifestSummaryResult(
        projectId = projectId,
        inputSha256 = sha,
        archivePath = archive,
        manifestPath = path,
        parser = parser,
        packageName = packageName,
        versionCode = versionCode,
        versionName = versionName,
        minSdkVersion = minSdk,
        targetSdkVersion = targetSdk,
        compileSdkVersion = compileSdk,
        application = application,
        usesPermissions = permissions,
        declaredPermissions = declaredPermissions,
        usesFeatures = features,
        componentCounts = components.groupingBy { it.type }.eachCount(),
        components = components,
        modules = modules,
        warnings = warnings,
    )
}

internal data class GenericXmlAttribute(
    val namespaceUri: String?,
    val localName: String,
    val value: String,
)

internal data class GenericXmlElement(
    val namespaceUri: String?,
    val localName: String,
    val attributes: List<GenericXmlAttribute>,
    val children: List<GenericXmlElement>,
) {
    fun attribute(namespaceUri: String?, name: String): String? = attributes
        .firstOrNull { it.localName == name && it.namespaceUri == namespaceUri }
        ?.value

    fun manifestChildren(name: String): List<GenericXmlElement> = children
        .filter { it.namespaceUri == null && it.localName == name }
}

internal object ManifestParser {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private val COMPONENT_TYPES = listOf("activity", "activity-alias", "service", "receiver", "provider")
    private const val MAX_XML_BYTES = 4 * 1024 * 1024
    private const val MAX_XML_DEPTH = 128
    private const val MAX_XML_NODES = 100_000

    fun parse(path: String?, bytes: ByteArray): ParsedManifest {
        preflightPlainXml(bytes)
        val document = safeDocument(bytes)
        val root = convertToGeneric(document.documentElement, XmlBudgetState())
        return fromGeneric(root, path, "plain-xml", emptyList())
    }

    fun parseBinaryXml(path: String?, bytes: ByteArray): ParsedManifest = BinaryAxmlParser.parse(path, bytes)
    fun parseAabProtobuf(path: String?, bytes: ByteArray): ParsedManifest = AabProtobufManifestParser.parse(path, bytes)

    fun fromGeneric(root: GenericXmlElement, path: String?, parser: String, warnings: List<String>): ParsedManifest {
        require(root.namespaceUri == null && root.localName == "manifest") { "Manifest root must be an unnamespaced <manifest>." }
        val permissions = root.manifestChildren("uses-permission").mapNotNull { it.androidAttribute("name") }
        val declared = root.manifestChildren("permission").map {
            AndroidDeclaredPermissionInfo(it.androidAttribute("name").orEmpty(), it.androidAttribute("protectionLevel"), it.androidAttribute("permissionGroup"))
        }
        val features = root.manifestChildren("uses-feature").map {
            AndroidUsesFeatureInfo(it.androidAttribute("name").orEmpty(), it.androidAttribute("required")?.toBooleanStrictOrNull())
        }
        val applicationElement = root.manifestChildren("application").firstOrNull()
        val application = applicationElement?.let {
            AndroidManifestApplicationInfo(
                it.androidAttribute("name"),
                it.androidAttribute("label"),
                it.androidAttribute("icon"),
                it.androidAttribute("theme"),
                it.androidAttribute("debuggable")?.toBooleanStrictOrNull(),
                it.androidAttribute("allowBackup")?.toBooleanStrictOrNull(),
                it.androidAttribute("usesCleartextTraffic")?.toBooleanStrictOrNull(),
                it.androidAttribute("networkSecurityConfig"),
            )
        }
        val sdk = root.manifestChildren("uses-sdk").firstOrNull()
        val parsedComponents = COMPONENT_TYPES.flatMap { type ->
            applicationElement?.manifestChildren(type).orEmpty().map { element ->
                val component = parseComponent(type, element, moduleName(path))
                component.copy(effectiveExported = effectiveExported(component, sdk?.androidAttribute("targetSdkVersion"), element.androidAttribute("exported")))
            }
        }
        return ParsedManifest(
            path = path,
            parser = parser,
            packageName = root.attribute(null, "package")?.takeIf(String::isNotBlank),
            versionCode = root.androidAttribute("versionCode"),
            permissions = permissions,
            declaredPermissions = declared,
            features = features,
            components = parsedComponents,
            warnings = warnings,
            versionName = root.androidAttribute("versionName"),
            minSdk = sdk?.androidAttribute("minSdkVersion"),
            targetSdk = sdk?.androidAttribute("targetSdkVersion"),
            compileSdk = root.androidAttribute("compileSdkVersion"),
            application = application,
            modules = moduleName(path)?.let { listOf(AndroidManifestModuleInfo(it, requireNotNull(path))) }.orEmpty(),
        )
    }

    private fun parseComponent(type: String, element: GenericXmlElement, moduleName: String?) = AndroidManifestComponentInfo(
        type,
        element.androidAttribute("name"),
        moduleName = moduleName,
        explicitExported = element.androidAttribute("exported")?.toBooleanStrictOrNull(),
        exported = element.androidAttribute("exported")?.toBooleanStrictOrNull(),
        enabled = element.androidAttribute("enabled")?.toBooleanStrictOrNull(),
        permission = element.androidAttribute("permission"),
        process = element.androidAttribute("process"),
        authorities = element.androidAttribute("authorities"),
        directBootAware = element.androidAttribute("directBootAware")?.toBooleanStrictOrNull(),
        intentFilters = element.manifestChildren("intent-filter").map { filter ->
            AndroidIntentFilterInfo(
                filter.manifestChildren("action").mapNotNull { it.androidAttribute("name") },
                filter.manifestChildren("category").mapNotNull { it.androidAttribute("name") },
                filter.manifestChildren("data").mapNotNull { it.androidAttribute("scheme") },
                filter.manifestChildren("data").mapNotNull { it.androidAttribute("host") },
                filter.manifestChildren("data").mapNotNull { it.androidAttribute("port") },
                filter.manifestChildren("data").mapNotNull { it.androidAttribute("path") },
                filter.manifestChildren("data").mapNotNull { it.androidAttribute("mimeType") },
            )
        },
    )

    private fun effectiveExported(component: AndroidManifestComponentInfo, targetSdk: String?, rawExported: String?): Boolean? {
        // An unresolved explicit resource must not be confused with an absent attribute.
        if (rawExported != null) return component.explicitExported
        val sdk = targetSdk?.toIntOrNull() ?: return null
        val hasIntentFilter = component.intentFilters.isNotEmpty()
        return when {
            component.type == "provider" && sdk <= 16 -> true
            component.type == "provider" -> false
            hasIntentFilter && sdk < 31 -> true
            hasIntentFilter -> null
            else -> false
        }
    }

    private fun GenericXmlElement.androidAttribute(name: String): String? = attribute(ANDROID_NS, name)

    private fun preflightPlainXml(bytes: ByteArray) {
        require(bytes.size <= MAX_XML_BYTES) { "Plain manifest XML exceeds the byte limit." }
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
            setProperty(XMLInputFactory.IS_COALESCING, false)
        }
        val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
        var nodes = 0
        var depth = 0
        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        nodes += 1 + reader.attributeCount
                        depth++
                        require(nodes <= MAX_XML_NODES) { "Plain manifest XML exceeds the node limit." }
                        require(depth <= MAX_XML_DEPTH) { "Plain manifest XML exceeds the depth limit." }
                    }
                    XMLStreamConstants.END_ELEMENT -> depth--
                }
            }
        } finally {
            reader.close()
        }
    }

    private fun safeDocument(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setXIncludeAware(false)
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        return document
    }

    private class XmlBudgetState {
        var nodes = 0
    }

    private fun convertToGeneric(element: Element, state: XmlBudgetState, depth: Int = 0): GenericXmlElement {
        state.nodes += 1 + element.attributes.length
        require(state.nodes <= MAX_XML_NODES) { "Plain manifest XML exceeds the node limit." }
        require(depth <= MAX_XML_DEPTH) { "Plain manifest XML exceeds the depth limit." }
        val attributes = (0 until element.attributes.length).map { index ->
            val attribute = element.attributes.item(index)
            GenericXmlAttribute(attribute.namespaceURI, attribute.localName ?: attribute.nodeName.substringAfter(':'), attribute.nodeValue.orEmpty())
        }
        val children = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it).asElementOrNull() }
            .map { child -> convertToGeneric(child, state, depth + 1) }
        return GenericXmlElement(element.namespaceURI, element.localName.ifBlank { element.nodeName.substringAfter(':') }, attributes, children)
    }

    private fun Node.asElementOrNull(): Element? = if (nodeType == Node.ELEMENT_NODE) this as Element else null

    fun error(path: String?, parser: String, warning: String) = ParsedManifest(
        path,
        parser,
        null,
        null,
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        listOf(warning),
    )

    fun mergeModules(results: List<ParsedManifest>): ParsedManifest {
        require(results.isNotEmpty()) { "At least one manifest module is required." }
        val warnings = mutableListOf<String>()
        results.forEachIndexed { index, result ->
            if (index != 0) warnings += "Manifest module ${result.path ?: "unknown"} was merged into the aggregate result."
            warnings += result.warnings
        }
        val primary = results.firstOrNull { it.path == "base/manifest/AndroidManifest.xml" }
            ?: results.firstOrNull { it.path == "AndroidManifest.xml" }
            ?: error(null, "missing-base", "Base manifest is absent; feature metadata is not used as application metadata.")
        warnings += primary.warnings
        val extras = results.filter { it !== primary }
        val modules = results.mapNotNull { result ->
            val path = result.path ?: return@mapNotNull null
            if (path == "AndroidManifest.xml") return@mapNotNull null
            AndroidManifestModuleInfo(moduleName(path) ?: "base", path)
        }
        val featureWarnings = extras.flatMap { result ->
            result.components.map { component -> "Feature module ${moduleName(result.path) ?: "unknown"} includes ${component.type} ${component.name ?: "unknown"}." } +
                result.features.map { feature -> "Feature module ${moduleName(result.path) ?: "unknown"} includes feature ${feature.name}." } +
                result.permissions.map { permission -> "Feature module ${moduleName(result.path) ?: "unknown"} includes permission $permission." }
        }
        return primary.copy(
            path = results.mapNotNull { it.path }.joinToString(","),
            permissions = results.flatMap { it.permissions }.distinct(),
            declaredPermissions = results.flatMap { it.declaredPermissions }.distinctBy { it.name },
            features = results.flatMap { it.features }.distinct(),
            components = results.flatMap { result ->
                result.components.map { component -> component.copy(moduleName = result.path?.let(::moduleName) ?: component.moduleName) }
            },
            modules = modules,
            warnings = (warnings + featureWarnings).distinct(),
        )
    }

    internal fun moduleName(path: String?): String? {
        if (path == null || path == "AndroidManifest.xml") return null
        return path.substringBefore("/manifest/AndroidManifest.xml").ifBlank { path.substringBefore('/') }
    }
}

internal fun isPlainXml(bytes: ByteArray): Boolean {
    if (bytes.firstOrNull()?.toInt()?.and(0xff) == '<'.code) return true
    val preview = bytes.take(64).toByteArray().toString(StandardCharsets.UTF_8)
    val beforeRoot = preview.substringBefore("<")
    return preview.contains('<') && beforeRoot.isBlank()
}

internal object BinaryAxmlParser {
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val UTF8_FLAG = 0x100
    private const val MAX_STRINGS = 65_536
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_DECODED_CHARACTERS = 4 * 1024 * 1024
    private const val MAX_DEPTH = 128
    private const val MAX_NODES = 100_000

    fun parse(path: String?, bytes: ByteArray): ParsedManifest {
        require(bytes.size <= MAX_BYTES) { "Binary AXML exceeds the byte limit." }
        val reader = Reader(bytes)
        val stringPool = reader.stringPool()
        val root = reader.xmlDocument(stringPool) ?: return ManifestParser.error(path, "binary-axml-error", "Binary AXML contains no document element.")
        val warnings = mutableListOf<String>()
        if (reader.ignoredChunkCount > 0) warnings += "Binary AXML skipped ${reader.ignoredChunkCount} unsupported chunk(s)."
        return ManifestParser.fromGeneric(root, path, "binary-axml", warnings)
    }

    private class Reader(private val bytes: ByteArray) {
        var ignoredChunkCount = 0
            private set
        private var nodeCount = 0
        private var resourceMap: List<Int> = emptyList()

        class StringPool(val values: List<String>)

        fun stringPool(): StringPool {
            require(bytes.size >= 36) { "Binary AXML is too short for the required headers." }
            val type = u16(0)
            require(type == RES_XML_TYPE) { "Binary AXML has invalid top-level chunk type 0x${type.toString(16)}." }
            val headerSize = u16(2)
            val fileSize = u32(4)
            require(headerSize == 8) { "Binary AXML has unsupported top header size $headerSize." }
            require(fileSize <= bytes.size) { "Binary AXML declares a size beyond the input bounds." }
            require(fileSize == bytes.size.toLong()) { "Binary AXML input contains bytes beyond the declared file size." }
            val poolType = u16(8)
            require(poolType == RES_STRING_POOL_TYPE) { "Binary AXML does not begin with a string pool." }
            val poolHeaderSize = u16(10)
            val poolSize = u32(12)
            require(poolHeaderSize == 28) { "Binary AXML has unsupported string-pool header $poolHeaderSize." }
            require(poolSize >= 28 && 8 + poolSize <= fileSize) { "Binary AXML string pool has invalid size $poolSize." }
            val count = u32(16)
            require(count <= MAX_STRINGS) { "Binary AXML string pool exceeds the $MAX_STRINGS entry limit." }
            val styleCount = u32(20)
            val flags = u32(24)
            val stringsStart = i32(28)
            val stylesStart = i32(32)
            val utf8 = flags.and(UTF8_FLAG.toLong()) != 0L
            require(styleCount == 0L) { "Binary AXML styled string pools are not supported." }
            require(stylesStart == 0) { "Binary AXML styled string pools are not supported." }
            require(stringsStart >= poolHeaderSize && stringsStart <= poolSize) { "Binary AXML string data is outside the pool." }
            val offsetEnd = (8 + poolHeaderSize).toLong() + count.toLong() * 4L
            require(offsetEnd <= 8 + stringsStart) { "Binary AXML string offsets exceed the pool bounds." }
            val offsetsBase = 8 + poolHeaderSize
            val unitSize = if (utf8) 1L else 2L
            val stringDataSize = poolSize - stringsStart
            val offsets = (0 until count.toInt()).map { index -> i32(offsetsBase + index * 4) }
            require(stringDataSize >= unitSize) { "Binary AXML string pool has no terminated string data." }
            require(offsets.all { it >= 0 && it.toLong() <= stringDataSize - unitSize }) { "Binary AXML string offset exceeds string data." }
            val decodedByOffset = HashMap<Int, String>()
            var decodedCharacters = 0L
            val values = offsets.map { offset ->
                decodedByOffset.getOrPut(offset) {
                    val value = stringAt(8 + stringsStart + offset, (8 + poolSize).toInt(), utf8)
                    decodedCharacters += value.length
                    require(decodedCharacters <= MAX_DECODED_CHARACTERS) { "Binary AXML string pool exceeds the decoded character limit." }
                    value
                }
            }
            return StringPool(values)
        }

        fun topChunks(): List<Int> {
            var offset = (8 + u32(12)).toInt()
            val fileSize = u32(4).toInt()
            var resourceIds = emptyList<Int>()
            while (offset < fileSize) {
                val type = u16(offset)
                val size = u32(offset + 4).toInt()
                require(size >= 8 && offset % 4 == 0 && size % 4 == 0 && offset.toLong() + size.toLong() <= fileSize.toLong()) { "Binary AXML contains a malformed chunk at offset $offset type=0x${type.toString(16)} size=$size." }
                when (type) {
                    RES_XML_RESOURCE_MAP_TYPE -> {
                        resourceIds = (0 until ((size - 8) / 4)).map { i32(offset + 8 + it * 4) }
                    }
                    else -> Unit
                }
                offset += size
            }
            require(offset == fileSize) { "Binary AXML chunks do not end at the declared file size." }
            return resourceIds
        }

        fun xmlDocument(pool: StringPool): GenericXmlElement? {
            resourceMap = topChunks()
            var offset = (8 + u32(12)).toInt()
            val fileSize = u32(4).toInt()
            val namespaceStack = ArrayDeque<Pair<String, String>>()
            val elementStack = ArrayDeque<Builder>()
            var depth = 0
            var root: GenericXmlElement? = null
            while (offset < fileSize) {
                val type = u16(offset)
                val size = i32(offset + 4)
                require(size >= 8 && offset % 4 == 0 && size % 4 == 0 && offset.toLong() + size.toLong() <= fileSize.toLong()) { "Binary AXML contains a malformed XML chunk at offset $offset type=0x${type.toString(16)} size=$size." }
                when (type) {
                    RES_XML_RESOURCE_MAP_TYPE -> Unit
                    RES_XML_START_NAMESPACE_TYPE -> {
                        require(u16(offset + 2) == 16) { "Binary AXML namespace node header is unsupported." }
                        require(size >= 24) { "Binary AXML namespace chunk is truncated." }
                        namespaceStack.addLast(string(pool, s32(offset + 16)) to string(pool, s32(offset + 20)))
                    }
                    RES_XML_END_NAMESPACE_TYPE -> {
                        require(u16(offset + 2) == 16) { "Binary AXML namespace node header is unsupported." }
                        require(size >= 24 && namespaceStack.isNotEmpty()) { "Binary AXML has an unbalanced end namespace." }
                        val prefix = string(pool, s32(offset + 16))
                        val uri = string(pool, s32(offset + 20))
                        val declared = namespaceStack.removeLast()
                        require(declared.first == prefix && declared.second == uri) { "Binary AXML namespace declarations are unbalanced." }
                    }
                    RES_XML_START_ELEMENT_TYPE -> {
                        require(u16(offset + 2) == 16) { "Binary AXML element node header is unsupported." }
                        require(size >= 36) { "Binary AXML start element is truncated." }
                        depth++
                        require(depth <= MAX_DEPTH) { "Binary AXML nesting exceeds the depth limit." }
                        val builder = startElement(pool, offset, size, namespaceStack)
                        elementStack.addLast(builder)
                    }
                    RES_XML_END_ELEMENT_TYPE -> {
                        require(u16(offset + 2) == 16) { "Binary AXML element node header is unsupported." }
                        require(size >= 24) { "Binary AXML end element is truncated." }
                        require(depth > 0 && elementStack.isNotEmpty()) { "Binary AXML has an unbalanced end element." }
                        val finished = elementStack.removeLast()
                        val endNamespace = namespace(pool, s32(offset + 16), namespaceStack)
                        val endName = string(pool, s32(offset + 20))
                        require(finished.namespaceUri == endNamespace && finished.localName == endName) {
                            "Binary AXML end element </$endName> does not match <${finished.localName}>."
                        }
                        depth--
                        val element = finished.build()
                        val parent = elementStack.lastOrNull() ?: run {
                            require(root == null) { "Binary AXML contains more than one root element." }
                            root = element
                            null
                        }
                        parent?.children?.add(element)
                    }
                    else -> ignoredChunkCount++
                }
                offset += size
            }
            require(depth == 0 && elementStack.isEmpty()) { "Binary AXML has unclosed elements." }
            require(namespaceStack.isEmpty()) { "Binary AXML has unclosed namespaces." }
            return root
        }

        private fun startElement(pool: StringPool, offset: Int, size: Int, namespaceStack: ArrayDeque<Pair<String, String>>): Builder {
            nodeCount++
            require(nodeCount <= MAX_NODES) { "Binary AXML exceeds the node limit." }
            val attributeStart = u16(offset + 24)
            val attributeSize = u16(offset + 26)
            val attributeCount = u16(offset + 28)
            require(attributeStart >= 20) { "Binary AXML attribute start is invalid." }
            require(attributeSize >= 20) { "Binary AXML attribute size is invalid." }
            val attributesBase = offset + 16 + attributeStart
            require(attributesBase.toLong() + attributeCount.toLong() * attributeSize.toLong() <= offset.toLong() + size.toLong()) {
                "Binary AXML attributes exceed chunk bounds."
            }
            val attributes = (0 until attributeCount).map { index ->
                nodeCount++
                require(nodeCount <= MAX_NODES) { "Binary AXML exceeds the node limit." }
                val base = attributesBase + index * attributeSize
                attribute(pool, base, namespaceStack)
            }
            return Builder(namespace(pool, s32(offset + 16), namespaceStack), string(pool, s32(offset + 20)), attributes)
        }

        private fun attribute(pool: StringPool, base: Int, namespaceStack: ArrayDeque<Pair<String, String>>): GenericXmlAttribute {
            require(u16(base + 12) == 8) { "Binary AXML typed value size is unsupported." }
            val namespaceIndex = s32(base)
            val nameIndex = s32(base + 4)
            val rawValueIndex = s32(base + 8)
            val valueType = u8(base + 15).toInt()
            val valueData = u32(base + 16).toInt()
            require(nameIndex >= 0) { "Binary AXML contains a missing required string index." }
            require(rawValueIndex >= -1) { "Binary AXML raw value index is invalid." }
            val resourceId = nameIndex.takeIf { it in resourceMap.indices }?.let(resourceMap::get) ?: 0
            val poolName = string(pool, nameIndex)
            val name = when {
                poolName.isNotBlank() -> poolName
                resourceId != 0 -> AndroidResourceIds.attrName(resourceId) ?: "0x${resourceId.toString(16).padStart(8, '0')}"
                else -> "unknown"
            }
            val value = if (rawValueIndex == -1) typedValue(pool, valueType, valueData) else string(pool, rawValueIndex)
            return GenericXmlAttribute(namespace(pool, namespaceIndex, namespaceStack), name, value)
        }

        private fun typedValue(pool: StringPool, type: Int, data: Int): String = when (type) {
            0x00 -> "@null"
            0x01 -> "@0x${data.toLong().and(0xffffffffL).toString(16).padStart(8, '0')}"
            0x02 -> "?0x${data.toLong().and(0xffffffffL).toString(16).padStart(8, '0')}"
            0x03 -> string(pool, data)
            0x04 -> Float.fromBits(data).toString()
            0x05 -> "@dimension:0x${data.toLong().and(0xffffffffL).toString(16)}"
            0x06 -> "@fraction:0x${data.toLong().and(0xffffffffL).toString(16)}"
            0x07 -> "@dynamic:0x${data.toLong().and(0xffffffffL).toString(16).padStart(8, '0')}"
            0x08 -> "?dynamic:0x${data.toLong().and(0xffffffffL).toString(16).padStart(8, '0')}"
            0x10 -> data.toString()
            0x11 -> "0x${data.toLong().and(0xffffffffL).toString(16)}"
            0x12 -> if (data != 0) "true" else "false"
            0x1c -> "#" + data.toLong().and(0xffffffffL).toString(16).padStart(8, '0')
            0x1d -> "#" + data.toLong().and(0xffffffL).toString(16).padStart(6, '0')
            0x1e -> "#" + data.toLong().and(0xffffL).toString(16).padStart(4, '0')
            0x1f -> "#" + data.toLong().and(0xfffL).toString(16).padStart(3, '0')
            else -> "@typed:0x${type.toString(16).padStart(2, '0')}/0x${data.toLong().and(0xffffffffL).toString(16).padStart(8, '0')}"
        }

        private fun namespace(pool: StringPool, index: Int, namespaceStack: ArrayDeque<Pair<String, String>>): String? {
            if (index == -1) return null
            require(index >= 0) { "Binary AXML contains an invalid negative namespace index." }
            val uri = string(pool, index)
            require(namespaceStack.any { it.second == uri }) {
                "Binary AXML uses undeclared namespace URI '$uri'."
            }
            return uri
        }

        private fun string(pool: StringPool, index: Int): String {
            require(index >= 0) { "Binary AXML contains a missing required string index." }
            require(index < pool.values.size) { "Binary AXML string index is outside the pool." }
            return pool.values[index]
        }

        private fun stringAt(offset: Int, limit: Int, utf8: Boolean): String {
            require(offset < limit) { "Binary AXML string header exceeds bounds." }
            return if (utf8) utf8String(offset, limit) else utf16String(offset, limit)
        }

        private fun utf16String(offset: Int, limit: Int): String {
            var cursor = offset
            var length = u16(cursor)
            cursor += 2
            if (length.and(0x8000) != 0) {
                length = length.and(0x7fff).shl(16).or(u16(cursor))
                cursor += 2
            }
            val terminator = cursor.toLong() + length.toLong() * 2L
            require(terminator + 2L <= limit.toLong()) { "Binary AXML UTF-16 string exceeds bounds." }
            require(u16(terminator.toInt()) == 0) { "Binary AXML UTF-16 string is not terminated." }
            val result = StringBuilder(length)
            repeat(length) { result.append(u16(cursor + it * 2).toChar()) }
            return result.toString()
        }

        private fun utf8String(offset: Int, limit: Int): String {
            var cursor = offset
            var decodedLength = u8(cursor).toInt()
            cursor++
            if (decodedLength.and(0x80) != 0) {
                decodedLength = decodedLength.and(0x7f).shl(8).or(u8(cursor).toInt())
                cursor++
            }
            var encodedLength = u8(cursor).toInt()
            cursor++
            if (encodedLength.and(0x80) != 0) {
                encodedLength = encodedLength.and(0x7f).shl(8).or(u8(cursor).toInt())
                cursor++
            }
            require(cursor.toLong() + encodedLength.toLong() + 1L <= limit.toLong()) { "Binary AXML UTF-8 string exceeds bounds." }
            require(u8(cursor + encodedLength) == 0L) { "Binary AXML UTF-8 string is not terminated." }
            val encoded = bytes.sliceArray(cursor until cursor + encodedLength)
            val decoded = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString()
            } catch (error: CharacterCodingException) {
                throw IllegalArgumentException("Binary AXML UTF-8 string is malformed.", error)
            }
            require((decoded.length and 0x7fff) == decodedLength) { "Binary AXML UTF-8 decoded length is inconsistent." }
            return decoded
        }

        private fun u8(offset: Int): Long {
            require(offset in bytes.indices) { "Binary AXML read exceeds input bounds." }
            return bytes[offset].toInt().and(0xff).toLong()
        }

        private fun u16(offset: Int): Int {
            require(offset in 0..bytes.size - 2) { "Binary AXML read exceeds input bounds." }
            return bytes[offset].toInt().and(0xff).or(bytes[offset + 1].toInt().and(0xff).shl(8))
        }

        private fun s32(offset: Int): Int {
            require(offset in 0..bytes.size - 4) { "Binary AXML read exceeds input bounds." }
            return bytes[offset].toInt().and(0xff).or(bytes[offset + 1].toInt().and(0xff).shl(8))
                .or(bytes[offset + 2].toInt().and(0xff).shl(16)).or(bytes[offset + 3].toInt().and(0xff).shl(24))
        }

        private fun u32(offset: Int): Long = s32(offset).toLong().and(0xffffffffL)

        private fun i32(offset: Int): Int {
            val value = u32(offset)
            require(value <= Int.MAX_VALUE.toLong()) { "Binary AXML offset or size exceeds supported bounds." }
            return value.toInt()
        }

        private class Builder(val namespaceUri: String?, val localName: String, val attributes: List<GenericXmlAttribute>) {
            val children = mutableListOf<GenericXmlElement>()
            fun build() = GenericXmlElement(namespaceUri, localName, attributes, children.toList())
        }
    }
}

internal object AabProtobufManifestParser {
    private const val MAX_PROTO_BYTES = 4 * 1024 * 1024
    private const val MAX_DEPTH = 128
    private const val MAX_NODES = 100_000

    fun parse(path: String?, bytes: ByteArray): ParsedManifest {
        require(bytes.size <= MAX_PROTO_BYTES) { "AAB manifest protobuf exceeds the byte limit." }
        val input = CodedInputStream.newInstance(bytes).apply {
            setRecursionLimit(MAX_DEPTH)
            setSizeLimit(MAX_PROTO_BYTES)
        }
        val root = try {
            Resources.XmlNode.parseFrom(input)
        } catch (error: InvalidProtocolBufferException) {
            val message = error.message.orEmpty()
            if (message.contains("recursion", ignoreCase = true)) {
                throw IllegalArgumentException("AAB manifest protobuf exceeds the depth limit: $message", error)
            }
            throw IllegalArgumentException("AAB manifest protobuf is malformed: $message", error)
        }
        require(root.nodeCase == Resources.XmlNode.NodeCase.ELEMENT) { "AAB manifest root is not an XmlElement." }
        val state = State()
        return ManifestParser.fromGeneric(element(root, state), path, "aab-protobuf", emptyList())
    }

    private fun element(node: Resources.XmlNode, state: State): GenericXmlElement {
        state.nodes += 1 + node.element.attributeCount
        require(state.nodes <= MAX_NODES) { "AAB manifest exceeds the node limit." }
        require(state.depth < MAX_DEPTH) { "AAB manifest exceeds the depth limit." }
        val source = node.element
        val attributes = source.attributeList.map { attribute ->
            GenericXmlAttribute(
                attribute.namespaceUri.takeIf(String::isNotBlank),
                attribute.name,
                attribute.compiledValue(),
            )
        }
        state.depth++
        val children = source.childList.mapNotNull { child ->
            if (child.nodeCase == Resources.XmlNode.NodeCase.ELEMENT) element(child, state) else null
        }
        state.depth--
        return GenericXmlElement(source.namespaceUri.takeIf(String::isNotBlank), source.name, attributes, children)
    }

    private fun Resources.XmlAttribute.compiledValue(): String {
        if (!hasCompiledItem()) return value
        val item = compiledItem
        return when (item.valueCase) {
            Resources.Item.ValueCase.REF -> item.ref.spelling()
            Resources.Item.ValueCase.STR -> item.str.value
            Resources.Item.ValueCase.RAW_STR -> item.rawStr.value
            Resources.Item.ValueCase.PRIM -> item.prim.spelling()
            else -> value
        }
    }

    private fun Resources.Reference.spelling(): String {
        val prefix = if (type == Resources.Reference.Type.ATTRIBUTE) "?" else "@"
        val id = this.id.toLong().and(0xffffffffL)
        return when {
            id != 0L -> prefix + "0x" + id.toString(16).padStart(8, '0')
            name.isNotBlank() -> prefix + name
            else -> "@null"
        }
    }

    private fun Resources.Primitive.spelling(): String = when {
        hasNullValue() -> "@null"
        hasEmptyValue() -> "@empty"
        hasBooleanValue() -> this.booleanValue.toString()
        hasIntDecimalValue() -> this.intDecimalValue.toString()
        hasIntHexadecimalValue() -> "0x" + unsignedHex(this.intHexadecimalValue)
        hasFloatValue() -> this.floatValue.toString()
        hasColorArgb8Value() -> "#" + unsignedHex(this.colorArgb8Value).padStart(8, '0')
        hasColorRgb8Value() -> "#" + unsignedHex(this.colorRgb8Value).takeLast(6).padStart(6, '0')
        hasColorArgb4Value() -> "#" + unsignedHex(this.colorArgb4Value).takeLast(4).padStart(4, '0')
        hasColorRgb4Value() -> "#" + unsignedHex(this.colorRgb4Value).takeLast(3).padStart(3, '0')
        hasDimensionValue() -> "@dimension:0x" + unsignedHex(this.dimensionValue)
        hasFractionValue() -> "@fraction:0x" + unsignedHex(this.fractionValue)
        hasDimensionValueDeprecated() -> this.dimensionValueDeprecated.toString()
        hasFractionValueDeprecated() -> this.fractionValueDeprecated.toString()
        else -> "@primitive:unset"
    }

    private fun unsignedHex(value: Int): String = value.toLong().and(0xffffffffL).toString(16)

    private class State(var depth: Int = 0, var nodes: Int = 0)
}

internal object AndroidResourceIds {
    // Verified against AOSP android15-release core/res/res/values/public-final.xml.
    private val ATTRS = mapOf(
        0x01010000 to "theme",
        0x01010001 to "label",
        0x01010002 to "icon",
        0x01010003 to "name",
        0x01010006 to "permission",
        0x01010009 to "protectionLevel",
        0x0101000a to "permissionGroup",
        0x0101000e to "enabled",
        0x0101000f to "debuggable",
        0x01010010 to "exported",
        0x01010011 to "process",
        0x01010018 to "authorities",
        0x01010024 to "value",
        0x01010026 to "mimeType",
        0x01010027 to "scheme",
        0x01010028 to "host",
        0x01010029 to "port",
        0x0101002a to "path",
        0x0101002b to "pathPrefix",
        0x0101002c to "pathPattern",
        0x0101020c to "minSdkVersion",
        0x0101021b to "versionCode",
        0x0101021c to "versionName",
        0x01010270 to "targetSdkVersion",
        0x01010280 to "allowBackup",
        0x0101028e to "required",
        0x010104ec to "usesCleartextTraffic",
        0x01010505 to "directBootAware",
        0x01010527 to "networkSecurityConfig",
        0x01010572 to "compileSdkVersion",
    )

    fun attrName(id: Int): String? = ATTRS[id]
}
