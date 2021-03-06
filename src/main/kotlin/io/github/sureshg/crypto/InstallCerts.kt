package io.github.sureshg.crypto

import io.github.sureshg.cmd.Install
import io.github.sureshg.extn.*
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Creates a PKCS12 TrustStore by retrieving server's
 * certificates with JDK trusted certificates.
 *
 * @author Suresh
 */
object InstallCerts {

    /**
     * Executes the action.
     */
    fun exec(args: Install) {
        val (host, port) = args.hostPort
        val storePasswd = args.storePasswd
        val keystoreFile = File(host.replace(".", "_").plus(".p12"))

        when {
            !args.all && keystoreFile.isFile -> {
                val uin = System.console()?.readLine(" $keystoreFile file exists. Do you want to overwrite it (y/n)? ".warn) ?: "n"
                if (uin.toLowerCase() != "y") exit(-1) { "Existing...".red }
            }

            args.debug -> {
                println("Enabling TLS debug tracing...".warn)
                JSSEProp.Debug.set("all")
            }
        }

        println("Loading default ca truststore...".cyan)
        val trustStore = CACertsKeyStore
        println("Opening connection to $host:$port...".cyan)
        val result = validateCerts(host, port, trustStore, args)

        when {
            result.chain.isEmpty() -> exit(-1) { "Could not obtain server certificate chain!".err }

            args.all -> {
                // Print cert chain and last TLS session info.
                result.chain.forEachIndexed { idx, cert ->
                    val info = if (args.verbose) cert.toString() else cert.info()
                    println("\n${idx + 1}) ${info.fg256()}")
                }
                exit(0) { "\n${result.sessionInfo?.fg256()}" }
            }

            result.valid -> exit(0) { "No errors, certificate is already trusted!".done }
        }

        println("Server sent ${result.chain.size} certificate(s)...".yellow)
        result.chain
                .filter { it.subjectX500Principal?.x500Name?.commonName != host }
                .reversed()
                .forEachIndexed { idx, cert ->
                    val alias = "$host-${idx + 1}"
                    println("\n${idx + 1}) Adding certificate to keystore using alias ${alias.bold}...")
                    println(cert.info().fg256())
                    trustStore.setCertificateEntry(alias, cert)
                }

        val filter = if (args.noJdkCaCerts) "$host-.*".toRegex() else null
        println("\n${"Default JDK trust store is ${if (args.noJdkCaCerts) "excluded." else "included."}".warn}")
        val keystore = trustStore.toPKCS12(aliasFilter = filter)

        if (validateCerts(host, port, keystore, args).valid) {
            println("Certificate is trusted. Saving the trust-store...".cyan)
            keystore.store(keystoreFile.outputStream(), storePasswd.toCharArray())
            val size = keystoreFile.length().toBinaryPrefixString()
            exit(0) {
                """|
                   |${"PKCS12 truststore saved to ${keystoreFile.absolutePath.bold} (${size.bold}) ".done}
                   |
                   |To lists entries in the keystore, run
                   |${"keytool -list -keystore $keystoreFile --storetype pkcs12 -v".yellow}
                """.trimMargin()
            }
        } else {
            exit(1) { "Something went wrong. Can't validate the cert chain or require client certs!".err }
        }
    }

    /**
     * Validate the TLS server using given keystore. It will skip the
     * cert chain validation if print certs (--all) option is enabled.
     *
     * @param host server host
     * @param port server port
     * @param keystore trustore to make TLS connection
     * @param args install cli config.
     */
    private fun validateCerts(host: String, port: Int, keystore: KeyStore, args: Install): Result {
        val validateChain = !args.all
        val tm = keystore.defaultTrustManager.saving(validateChain)
        val sslCtx = getSSLContext("TLS", trustManagers = arrayOf(tm))
        val socket = sslCtx.socketFactory.createSocket() as SSLSocket

        try {
            println("\nStarting SSL handshake...".cyan)
            socket.use {
                it.soTimeout = args.timeout
                it.connect(InetSocketAddress(host, port), args.timeout)
                it.startHandshake()
            }
            return Result(true, tm.chain, socket.session?.info())
        } catch(e: SSLException) {
            if (args.verbose) {
                e.printStackTrace()
            }
            return Result(false, tm.chain, socket.session?.info())
        }
    }
}

/**
 * Holds the cert validation result. Mainly validation status and cert chain.
 */
data class Result(val valid: Boolean, val chain: List<X509Certificate>, val sessionInfo: String?)







