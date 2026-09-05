package release.publish

import deployment.posixShellQuote
import java.io.BufferedReader
import java.io.File
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory

/** Explicit paths work identically on Windows, macOS and Linux; no local OpenSSH executable or agent is needed. */
data class SiteConnection(
    val host: String,
    val port: Int,
    val user: String,
    val downloadsPath: String,
    val privateKey: File,
    val knownHosts: File,
    val privateKeyPassphrase: String? = null,
) {
    internal fun validate() {
        require(host.matches(Regex("[A-Za-z0-9._-]+")) && port in 1..65535 && user.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) {
            "Invalid SSH publication coordinates"
        }
        require(downloadsPath.startsWith('/') && downloadsPath.endsWith("/static/downloads") &&
            downloadsPath.none { it.code < 32 || it.code == 127 } &&
            downloadsPath.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Client publication path must be an absolute <deployment>/static/downloads directory"
        }
        require(privateKey.isFile) { "SSH private key file is missing" }
        require(knownHosts.isFile && knownHosts.length() > 0) { "Provide a known_hosts file containing the verified deployment host key" }
    }

    // A data class containing a passphrase must never inherit a secret-bearing toString().
    override fun toString(): String = "SiteConnection($user@$host:$port, downloadsPath=$downloadsPath)"
}

internal inline fun <T> SiteConnection.connect(block: (ClientSession, SftpClient) -> T): T {
    validate()
    val keys = SecurityUtils.getKeyPairResourceParser().loadKeyPairs(
        null,
        privateKey.toPath(),
        privateKeyPassphrase?.let(FilePasswordProvider::of) ?: FilePasswordProvider.EMPTY,
    )
    require(!keys.isNullOrEmpty()) { "SSH private key file contains no supported key" }
    SshClient.setUpDefaultClient().use { client ->
        client.serverKeyVerifier = KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, knownHosts.toPath())
        client.keyIdentityProvider = KeyIdentityProvider.wrapKeyPairs(keys)
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofSeconds(10))
        CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(client, 3)
        client.start()
        client.connect(user, host, port).verify(Duration.ofSeconds(30)).session.use { session ->
            session.auth().verify(Duration.ofSeconds(30))
            SftpClientFactory.instance().createSftpClient(session).use { sftp -> return block(session, sftp) }
        }
    }
}

/**
 * A separate client-publication lease; it never acquires, changes or bypasses the server deployment lease.
 * Only the remote Linux host needs flock and coreutils, already used by server provisioning. Uploads use
 * SFTP, but rename/remove operations run inside this same lock-holding process. A queued old mutation
 * therefore completes before the lease is released, never after the next publisher acquires it.
 */
internal class ClientPublicationLease private constructor(
    private val session: ClientSession,
    private val channel: ChannelExec,
    private val output: BufferedReader,
    private val downloadsPath: String,
) : AutoCloseable {
    @Volatile
    private var closing = false

    init {
        channel.addCloseFutureListener { if (!closing) session.close(true) }
    }

    fun requireHeld() {
        check(session.isOpen && channel.isOpen && !channel.isClosing && channel.exitStatus == null) {
            "Client publication lease was lost; public paths will not be modified"
        }
    }

    fun rename(source: String, target: String, replace: Boolean) {
        mutate(if (replace) "REPLACE" else "MOVE", source, target)
    }

    fun remove(path: String, directory: Boolean) {
        mutate(if (directory) "RMDIR" else "REMOVE", path, "-")
    }

    /** A fixed tab-delimited protocol, never shell evaluation of stdin or interpolation of file paths. */
    @Synchronized
    private fun mutate(operation: String, source: String, target: String) {
        requirePath(source)
        if (target != "-") requirePath(target)
        requireHeld()
        try {
            val command = "$operation\t$source\t$target\n"
            channel.invertedIn.write(command.toByteArray(Charsets.UTF_8))
            channel.invertedIn.flush()
            val result = CompletableFuture.supplyAsync { output.readLine() }.get(30, TimeUnit.SECONDS)
            check(result == "TEAMTALK_RESULT:0") { "Remote client publication $operation failed; pending journal is retained for recovery" }
        } catch (failure: Exception) {
            // A timed-out result is ambiguous. Closing the session forbids another command on this lease;
            // the remote process keeps flock while completing an already-read filesystem operation.
            channel.close(true)
            session.close(true)
            throw failure
        }
    }

    private fun requirePath(path: String) {
        require(path.startsWith("$downloadsPath/") &&
            path.none { it.code < 32 || it.code == 127 } &&
            path.removePrefix("$downloadsPath/").split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Publication mutation must remain inside the configured downloads directory"
        }
    }

    override fun close() {
        closing = true
        channel.close(true)
    }

    companion object {
        fun acquire(session: ClientSession, downloadsPath: String): ClientPublicationLease {
            val lock = downloadsPath + "/.teamtalk-client-release.lock"
            // All arguments are read as data and quoted by the shell. No eval, command substitution of
            // input, wildcard deletion or arbitrary command from the client is accepted.
            val script = """
                root=${posixShellQuote(downloadsPath)};
                printf 'TEAMTALK_CLIENT_RELEASE_LOCKED\n';
                while IFS="${'$'}(printf '\t')" read -r operation source target; do
                    case "${'$'}source" in "${'$'}root/"*) ;; *) exit 64 ;; esac;
                    case "${'$'}operation" in
                        MOVE|REPLACE)
                            case "${'$'}target" in "${'$'}root/"*) ;; *) exit 64 ;; esac;
                            if [ "${'$'}operation" = MOVE ] && { [ -e "${'$'}target" ] || [ -L "${'$'}target" ]; }; then
                                result=73;
                            else
                                mv -fT -- "${'$'}source" "${'$'}target";
                                result=${'$'}?;
                            fi ;;
                        REMOVE) rm -- "${'$'}source"; result=${'$'}? ;;
                        RMDIR) rmdir -- "${'$'}source"; result=${'$'}? ;;
                        *) exit 64 ;;
                    esac;
                    printf 'TEAMTALK_RESULT:%s\n' "${'$'}result";
                done
            """.trimIndent().replace('\n', ' ')
            val channel = session.createExecChannel("flock -n ${posixShellQuote(lock)} sh -c ${posixShellQuote(script)}")
            try {
                channel.open().verify(Duration.ofSeconds(15))
                val output = channel.invertedOut.bufferedReader(Charsets.UTF_8)
                val ready = CompletableFuture.supplyAsync { output.readLine() }
                    .get(20, TimeUnit.SECONDS)
                check(ready == "TEAMTALK_CLIENT_RELEASE_LOCKED") {
                    "Another client release holds the publication lock, or remote flock is unavailable"
                }
                return ClientPublicationLease(session, channel, output, downloadsPath).also { it.requireHeld() }
            } catch (failure: Exception) {
                channel.close(true)
                throw failure
            }
        }
    }
}
