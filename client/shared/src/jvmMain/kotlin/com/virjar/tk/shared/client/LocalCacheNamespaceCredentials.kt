package com.virjar.tk.shared.client

import com.virjar.tk.protocol.model.AuthRules
import com.virjar.tk.protocol.payload.AuthPayloadPolicy
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.Properties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Read raw credential slots without claiming a token owner, normalizing credentials or opening a
 * cache. The caller keeps the installation closed and holds its JVM lease throughout disposition;
 * Android paths refer only to an offline app-data export. Token values never leave this check.
 */
internal fun requireUnreferencedLocalCacheNamespace(
    root: Path,
    owner: LocalCacheDiagnosticOwner,
    layout: LocalCacheDiagnosticLayout,
) {
    try {
        if (layout == LocalCacheDiagnosticLayout.JVM) {
            // These stores publish by atomic replacement; neither reads a .bak recovery slot.
            // A foreign backup at that name is unexplained rather than evidence of a logged-out owner.
            for (name in listOf("auth.properties.bak", "credentials.properties.bak")) {
                if (readNamespaceCredentialSlot(root, name) != null) invalidNamespaceCredentials()
            }
            readNamespaceCredentialSlot(root, "auth.properties")?.let {
                requireGuiCredentialOwnerUnreferenced(namespaceProperties(it, utf8 = true), owner)
            }
            readNamespaceCredentialSlot(root, "credentials.properties")?.let {
                requireAgentCredentialOwnerUnreferenced(namespaceProperties(it, utf8 = false), owner)
            }
        } else {
            // SharedPreferences may recover .bak over the primary file. Neither copy can authorize
            // deleting an owner still referenced by the other, even if it looks like an older token.
            for (name in listOf("shared_prefs/teamtalk_auth.xml", "shared_prefs/teamtalk_auth.xml.bak")) {
                readNamespaceCredentialSlot(root, name)?.let {
                    requireGuiCredentialOwnerUnreferenced(namespaceAndroidPreferences(it), owner)
                }
            }
        }
    } catch (failure: LocalCacheArchiveFailure) {
        throw failure
    } catch (_: Exception) {
        invalidNamespaceCredentials()
    }
}

private fun readNamespaceCredentialSlot(root: Path, relative: String): ByteArray? {
    fun checkedPath(): Path? {
        requireRealDirectory(basicAttributes(root), "Credential root")
        var path = root
        val components = relative.split('/')
        for ((index, component) in components.withIndex()) {
            path = path.resolve(component)
            if (!Files.exists(path, NOFOLLOW_LINKS)) {
                if (Files.notExists(path, NOFOLLOW_LINKS)) return null
                invalidNamespaceCredentials()
            }
            val attributes = basicAttributes(path)
            if (index != components.lastIndex) requireRealDirectory(attributes, "Credential parent")
            else {
                requireRealFile(attributes, "Credential file")
                if ("unix" in path.fileSystem.supportedFileAttributeViews() &&
                    (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() != 1L
                ) invalidNamespaceCredentials()
            }
        }
        return path
    }

    val path = checkedPath() ?: return null
    val before = basicAttributes(path)
    if (before.size() !in 0..MAX_NAMESPACE_CREDENTIAL_BYTES.toLong()) invalidNamespaceCredentials()
    val bytes = Files.newInputStream(path, READ, NOFOLLOW_LINKS).use {
        it.readNBytes(MAX_NAMESPACE_CREDENTIAL_BYTES + 1)
    }
    if (bytes.size.toLong() != before.size() || checkedPath() != path) invalidNamespaceCredentials()
    val after = basicAttributes(path)
    if (before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
        before.fileKey() != after.fileKey()
    ) invalidNamespaceCredentials()
    return bytes
}

private fun namespaceProperties(bytes: ByteArray, utf8: Boolean): Map<String, String> {
    val properties = object : Properties() {
        override fun put(key: Any, value: Any): Any? {
            if (containsKey(key)) invalidNamespaceCredentials()
            return super.put(key, value)
        }
    }
    if (utf8) {
        val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        properties.load(StringReader(decoded))
    } else {
        // AgentCredentials.persist uses Properties.store(OutputStream), including Unicode escapes.
        properties.load(ByteArrayInputStream(bytes))
    }
    return properties.stringPropertyNames().associateWith(properties::getProperty)
}

private fun namespaceAndroidPreferences(bytes: ByteArray): Map<String, String> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val builder = factory.newDocumentBuilder().apply {
        // The default parser error handler can print input excerpts to stderr.
        setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = throw exception
            override fun error(exception: SAXParseException) = throw exception
            override fun fatalError(exception: SAXParseException) = throw exception
        })
    }
    val map = builder.parse(ByteArrayInputStream(bytes)).documentElement
    if (map.tagName != "map" || map.attributes.length != 0) invalidNamespaceCredentials()
    val values = linkedMapOf<String, String>()
    for (element in namespacePreferenceChildren(map)) {
        val name = element.getAttribute("name")
        if (name !in GUI_NAMESPACE_CREDENTIAL_KEYS || name in values) invalidNamespaceCredentials()
        val value = when (name) {
            "owner_generation" -> {
                if (element.tagName != "long" || element.attributes.length != 2 ||
                    !element.hasAttribute("value") || namespacePreferenceChildren(element).isNotEmpty()
                ) invalidNamespaceCredentials()
                element.getAttribute("value")
            }
            "rejected_protocol_versions", "rejected_protocol_version_ids" -> {
                if (element.tagName != "set" || element.attributes.length != 1) invalidNamespaceCredentials()
                namespacePreferenceChildren(element).joinToString(",") { entry ->
                    if (entry.tagName != "string" || entry.attributes.length != 0 ||
                        namespacePreferenceChildren(entry, allowText = true).isNotEmpty()
                    ) invalidNamespaceCredentials()
                    entry.textContent
                }
            }
            else -> {
                if (element.tagName != "string" || element.attributes.length != 1 ||
                    namespacePreferenceChildren(element, allowText = true).isNotEmpty()
                ) invalidNamespaceCredentials()
                element.textContent
            }
        }
        values[name] = value
    }
    return values
}

private fun namespacePreferenceChildren(parent: Element, allowText: Boolean = false): List<Element> = buildList {
    for (index in 0 until parent.childNodes.length) {
        when (val child = parent.childNodes.item(index)) {
            is Element -> add(child)
            else -> when (child.nodeType) {
                Node.COMMENT_NODE -> Unit
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                    if (!allowText && !child.nodeValue.isNullOrBlank()) invalidNamespaceCredentials()
                else -> invalidNamespaceCredentials()
            }
        }
    }
}

private fun requireGuiCredentialOwnerUnreferenced(values: Map<String, String>, owner: LocalCacheDiagnosticOwner) {
    if ((values.keys - GUI_NAMESPACE_CREDENTIAL_KEYS).isNotEmpty()) invalidNamespaceCredentials()
    values["owner_generation"]?.let { if (it.toLongOrNull() == null) invalidNamespaceCredentials() }
    for (key in listOf("rejected_protocol_versions", "rejected_protocol_version_ids")) {
        values[key]?.let { encoded ->
            if (encoded.isNotEmpty() && encoded.split(',').any { it.trim().toIntOrNull()?.let { it >= 0 } != true })
                invalidNamespaceCredentials()
        }
    }
    val fingerprint = values["deployment_fingerprint"]?.also(::validatedDeploymentFingerprint)
    val identityKeys = setOf("uid", "refresh_token", "dataset_id")
    if (values.keys.none { it in identityKeys }) return // Empty or normally logged-out credential slot.
    if (fingerprint == null || !values.keys.containsAll(identityKeys) || values["refresh_token"].isNullOrBlank())
        invalidNamespaceCredentials()
    val uid = validatedLocalCacheOwnerId(values.getValue("uid"))
    val dataset = validatedLocalCacheDatasetId(values.getValue("dataset_id"))
    if (fingerprint == owner.deploymentFingerprint && uid == owner.uid && dataset == owner.datasetId)
        archiveFailure("NAMESPACE_REFERENCED_BY_CREDENTIALS")
}

private fun requireAgentCredentialOwnerUnreferenced(values: Map<String, String>, owner: LocalCacheDiagnosticOwner) {
    if ((values.keys - AGENT_NAMESPACE_CREDENTIAL_KEYS).isNotEmpty() ||
        values.values.any { it.isBlank() || it.any(Char::isISOControl) }
    ) invalidNamespaceCredentials()
    // Validate existing values using the same pure bounds as AgentCredentials. In particular, an
    // invalid identity-only slot must not be treated as a normal logged-out credential record.
    values["apiToken"]?.let {
        if (it.length !in 32..128 || it.any { character ->
                !character.isLetterOrDigit() && character != '-' && character != '_'
            }) invalidNamespaceCredentials()
    }
    values["deviceId"]?.let {
        if (it.length < 8 || AuthRules.validateDeviceId(it) != null) invalidNamespaceCredentials()
    }
    values["username"]?.let { if (AuthRules.validateUsername(it) != null) invalidNamespaceCredentials() }
    values["password"]?.let {
        if (AuthRules.validatePassword(it) != null || it.length > AuthPayloadPolicy.MAX_PASSWORD_LENGTH)
            invalidNamespaceCredentials()
    }
    values["uid"]?.let { if (it.length > AuthPayloadPolicy.MAX_UID_LENGTH) invalidNamespaceCredentials() }
    values["refreshToken"]?.let { if (it.length > AuthPayloadPolicy.MAX_TOKEN_LENGTH) invalidNamespaceCredentials() }
    val fingerprint = values["deploymentFingerprint"]?.also(::validatedDeploymentFingerprint)
    val authentication = setOf("registrationState", "username", "password", "uid", "refreshToken")
    if (values.keys.none { it in authentication }) return // Unauthenticated runtime identity or empty slot.
    if (fingerprint == null || values["apiToken"] == null || values["deviceId"] == null)
        invalidNamespaceCredentials()
    when (values["registrationState"]) {
        "REGISTER_PENDING" -> if (values["username"] == null || values["password"] == null ||
            values["uid"] != null || values["refreshToken"] != null
        ) invalidNamespaceCredentials()
        "ACTIVE" -> {
            if (values["username"] == null || values["password"] != null ||
                values["uid"] == null || values["refreshToken"] == null
            ) invalidNamespaceCredentials()
            val uid = validatedLocalCacheOwnerId(values.getValue("uid"))
            // Agent credentials deliberately do not persist datasetId. Never guess which dataset
            // a future authenticated handshake will reopen for this deployment and uid.
            if (fingerprint == owner.deploymentFingerprint && uid == owner.uid)
                archiveFailure("NAMESPACE_REFERENCED_BY_CREDENTIALS")
        }
        else -> invalidNamespaceCredentials()
    }
}

private fun invalidNamespaceCredentials(): Nothing = archiveFailure("CREDENTIALS_UNREADABLE_OR_INVALID")

private const val MAX_NAMESPACE_CREDENTIAL_BYTES = 64 * 1024
private val GUI_NAMESPACE_CREDENTIAL_KEYS = setOf(
    "owner_generation", "deployment_fingerprint", "uid", "refresh_token", "dataset_id",
    "rejected_protocol_versions", "rejected_protocol_version_ids",
)
private val AGENT_NAMESPACE_CREDENTIAL_KEYS = setOf(
    "registrationState", "deploymentFingerprint", "username", "password", "uid", "refreshToken", "apiToken", "deviceId",
)
