package release.publish

import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class GitHubPublication(
    val repository: String,
    val version: String,
    val sourceCommit: String,
    /** The checked-in release notes, copied without generating or rewriting prose. */
    val notes: String,
    val assets: List<File>,
    val createTag: Boolean = false,
    val prerelease: Boolean = true,
)

/** GitHub is one optional destination. All requests and uploads use the JDK, including on Windows. */
class GitHubPublisher(
    private val apiBase: URI = URI("https://api.github.com"),
    private val uploadsBase: URI = URI("https://uploads.github.com"),
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    fun publish(publication: GitHubPublication, token: String): PublicationResult {
        requireVersion(publication.version)
        require(publication.repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Invalid GitHub repository" }
        require(publication.sourceCommit.matches(Regex("[a-fA-F0-9]{40}"))) { "Release source must be a full Git commit SHA" }
        require(publication.notes.isNotBlank()) { "Checked-in release notes must not be empty" }
        require(token.isNotBlank()) { "GITHUB_TOKEN is required only when publishing to GitHub" }
        require(publication.assets.isNotEmpty()) { "A GitHub release requires sealed release assets" }
        publication.assets.forEach(::requireAsset)
        require(publication.assets.map(File::getName).distinct().size == publication.assets.size) { "Duplicate asset names" }
        val hashes = publication.assets.associate { it.name to sha256(it) }
        val api = GitHubApi(publication.repository, token)
        val tag = "v${publication.version}"
        api.ensureTag(tag, publication.sourceCommit, publication.createTag)
        var release = api.findRelease(tag) ?: api.createRelease(buildJsonObject {
            put("tag_name", tag)
            put("target_commitish", publication.sourceCommit)
            put("name", "TeamTalk ${publication.version}")
            put("body", publication.notes)
            put("draft", true)
            put("prerelease", publication.prerelease)
        })
        require(release.getValue("body").jsonPrimitive.contentOrNull.orEmpty() == publication.notes) {
            "GitHub release notes differ from the sealed bundle; refusing to replace an existing release"
        }
        val releaseId = release.getValue("id").jsonPrimitive.long
        val draft = release.getValue("draft").jsonPrimitive.boolean
        val existing = api.listAssets(releaseId).associateBy { it.getValue("name").jsonPrimitive.content }.toMutableMap()
        require(existing.keys.all(hashes::containsKey)) { "GitHub release contains assets outside this sealed bundle" }
        // Check every existing asset before writing anything. An interrupted upload leaves a draft that can be resumed.
        val incomplete = existing.filterValues { it["state"]?.jsonPrimitive?.contentOrNull == "starter" }
        require(incomplete.isEmpty() || draft) { "Published GitHub release contains incomplete assets; refusing to modify it" }
        existing.filterKeys { it !in incomplete }.forEach { (name, asset) ->
            require(api.assetHash(asset) == hashes.getValue(name)) {
                "GitHub asset $name already exists with different bytes; use a new release version"
            }
        }
        if (!draft) {
            require(existing.keys == hashes.keys) { "Published GitHub release is incomplete; refusing to modify it" }
            require(release.getValue("prerelease").jsonPrimitive.boolean == publication.prerelease) {
                "Published GitHub release has a different prerelease policy"
            }
            return PublicationResult("github:${publication.repository}", publication.version, true)
        }
        // GitHub may leave a zero-byte starter after an interrupted upload. It is an unpublished placeholder,
        // not a conflicting artifact; delete only this bundle's named placeholders before retrying them.
        incomplete.forEach { (name, asset) ->
            api.removeIncompleteAsset(asset)
            existing.remove(name)
        }
        for (file in publication.assets) {
            if (file.name in existing) continue
            val asset = api.upload(releaseId, file)
            require(api.assetHash(asset) == hashes.getValue(file.name)) { "GitHub upload checksum mismatch: ${file.name}" }
        }
        // Re-read after upload: do not publish a draft with missing, extra or concurrently changed assets.
        val complete = api.listAssets(releaseId).associateBy { it.getValue("name").jsonPrimitive.content }
        require(complete.keys == hashes.keys) { "GitHub draft assets changed during publication" }
        complete.forEach { (name, asset) -> require(api.assetHash(asset) == hashes.getValue(name)) { "GitHub draft asset changed: $name" } }
        api.ensureTag(tag, publication.sourceCommit, create = false)
        release = api.json("PATCH", "/releases/$releaseId", buildJsonObject {
            put("draft", false)
            put("prerelease", publication.prerelease)
        })
        check(!release.getValue("draft").jsonPrimitive.boolean) { "GitHub did not publish the completed draft" }
        return PublicationResult("github:${publication.repository}", publication.version, false)
    }

    private inner class GitHubApi(private val repository: String, private val token: String) {
        fun ensureTag(tag: String, commit: String, create: Boolean) {
            val refPath = "/git/ref/tags/$tag"
            var ref = optional(refPath)
            if (ref == null) {
                require(create) { "Git tag $tag does not exist; create it from the root version commit or enable release tag creation" }
                val response = send("POST", "/git/refs", buildJsonObject {
                    put("ref", "refs/tags/$tag")
                    put("sha", commit)
                })
                require(response.statusCode() == 201 || response.statusCode() == 422) { failure("create tag", response.statusCode()) }
                ref = optional(refPath) ?: error("GitHub did not create tag $tag")
            }
            var target = ref.getValue("object").jsonObject
            repeat(8) {
                if (target.getValue("type").jsonPrimitive.content == "tag") {
                    target = json("GET", "/git/tags/${target.getValue("sha").jsonPrimitive.content}").getValue("object").jsonObject
                }
            }
            require(target.getValue("type").jsonPrimitive.content == "commit" &&
                target.getValue("sha").jsonPrimitive.content.equals(commit, ignoreCase = true)) {
                "Git tag $tag must point to the exact source commit recorded by the sealed bundle"
            }
        }

        fun findRelease(tag: String): JsonObject? {
            optional("/releases/tags/$tag")?.let { return it }
            // Authenticated release listings include drafts, also on Enterprise versions whose by-tag API omits them.
            return pages("/releases").singleOrNull { it.getValue("tag_name").jsonPrimitive.content == tag }
        }

        fun createRelease(body: JsonObject): JsonObject {
            val response = send("POST", "/releases", body)
            if (response.statusCode() == 201) return Json.parseToJsonElement(response.body()).jsonObject
            if (response.statusCode() == 422) {
                findRelease(body.getValue("tag_name").jsonPrimitive.content)?.let { return it }
            }
            error(failure("create release", response.statusCode()))
        }

        fun removeIncompleteAsset(asset: JsonObject) {
            require(asset.getValue("state").jsonPrimitive.content == "starter" && asset.getValue("size").jsonPrimitive.long == 0L) {
                "Only zero-byte draft upload placeholders can be replaced automatically"
            }
            val response = send("DELETE", "/releases/assets/${asset.getValue("id").jsonPrimitive.long}")
            require(response.statusCode() == 204) { failure("remove incomplete draft upload", response.statusCode()) }
        }

        fun listAssets(id: Long): List<JsonObject> = pages("/releases/$id/assets")

        private fun pages(path: String): List<JsonObject> {
            val all = mutableListOf<JsonObject>()
            for (page in 1..100) {
                val response = send("GET", "$path?per_page=100&page=$page")
                require(response.statusCode() == 200) { failure("list releases/assets", response.statusCode()) }
                val items = Json.parseToJsonElement(response.body()).jsonArray.map { it.jsonObject }
                all += items
                if (items.size < 100) return all
            }
            error("GitHub release listing exceeds 10,000 entries; narrow the repository's retained release history")
        }

        fun assetHash(asset: JsonObject): String {
            val state = asset["state"]?.jsonPrimitive?.contentOrNull
            require(state == null || state == "uploaded") { "GitHub contains an incomplete asset; delete that failed draft asset before retrying" }
            val digest = asset["digest"]?.jsonPrimitive?.contentOrNull
            if (digest != null && digest.matches(Regex("sha256:[a-fA-F0-9]{64}"))) return digest.substringAfter(':').lowercase()
            // Older Enterprise versions do not expose digest. Download through the authenticated asset API.
            val id = asset.getValue("id").jsonPrimitive.long
            var request = request("/releases/assets/$id").header("Accept", "application/octet-stream").GET().build()
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() in listOf(301, 302, 303, 307, 308)) {
                response.body().close()
                val location = URI(response.headers().firstValue("Location").orElseThrow())
                require(location.scheme == "https" || isLoopback(location)) { "GitHub asset redirect must use HTTPS" }
                // Signed CDN URLs must never receive the repository token.
                request = HttpRequest.newBuilder(location).timeout(Duration.ofMinutes(20)).GET().build()
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            }
            return response.body().use { input ->
                require(response.statusCode() == 200) { failure("verify asset", response.statusCode()) }
                sha256(input)
            }
        }

        fun upload(releaseId: Long, file: File): JsonObject {
            val path = "/repos/$repository/releases/$releaseId/assets?name=${URLEncoder.encode(file.name, Charsets.UTF_8)}"
            val request = HttpRequest.newBuilder(uploadsBase.resolvePath(path))
                .timeout(Duration.ofMinutes(30))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(file.toPath())).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            require(response.statusCode() == 201) { failure("upload ${file.name}", response.statusCode()) }
            return Json.parseToJsonElement(response.body()).jsonObject
        }

        fun json(method: String, path: String, body: JsonObject? = null, expected: Int = 200): JsonObject {
            val response = send(method, path, body)
            require(response.statusCode() == expected) { failure("$method $path", response.statusCode()) }
            return Json.parseToJsonElement(response.body()).jsonObject
        }

        private fun optional(path: String): JsonObject? {
            val response = send("GET", path)
            if (response.statusCode() == 404) return null
            require(response.statusCode() == 200) { failure("GET $path", response.statusCode()) }
            return Json.parseToJsonElement(response.body()).jsonObject
        }

        private fun send(method: String, path: String, body: JsonObject? = null): HttpResponse<String> {
            val request = request(path).header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
                .build()
            return http.send(request, HttpResponse.BodyHandlers.ofString())
        }

        private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder(apiBase.resolvePath("/repos/$repository$path"))
            .timeout(Duration.ofMinutes(2))
            .header("Authorization", "Bearer $token")
            .header("X-GitHub-Api-Version", "2022-11-28")

        private fun failure(action: String, status: Int): String = "GitHub $action failed (HTTP $status); the draft and successful uploads are retained for retry"
    }

    private fun URI.resolvePath(path: String): URI {
        require(scheme == "https" || isLoopback(this)) { "GitHub endpoints must use HTTPS" }
        return URI(toString().trimEnd('/') + path)
    }

    private fun isLoopback(uri: URI): Boolean = uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "::1")
}
