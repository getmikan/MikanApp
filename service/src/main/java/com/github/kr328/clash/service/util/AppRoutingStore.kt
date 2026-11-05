package com.github.kr328.clash.service.util

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the proxy passwords out of the plain settings file. */
interface ProxySecrets {
    fun get(proxyId: String): String?
    fun set(proxyId: String, password: String)
    fun delete(proxyId: String)
}

/**
 * Per-app routing settings in `filesDir/app_routing.json`, shared by the UI and the service
 * process (both read the file fresh). Passwords live in [secrets].
 */
class AppRoutingStore(private val filesDir: File, private val secrets: ProxySecrets = KeystoreProxySecrets(filesDir)) {
    private val file get() = File(filesDir, "app_routing.json")

    fun load(): AppRoutingSettings =
        runCatching { json.decodeFromString(AppRoutingSettings.serializer(), file.readText()) }
            .getOrDefault(AppRoutingSettings())

    fun save(settings: AppRoutingSettings) {
        atomicWrite(file, json.encodeToString(AppRoutingSettings.serializer(), settings))
    }

    /** Settings plus the passwords that could be read; composition blocks the rest. */
    fun loadConfig(): AppRoutingConfig {
        val settings = load()
        if (!supported) return AppRoutingConfig.Empty
        val passwords = HashMap<String, String>()
        for (p in settings.proxies) {
            if (p.username.isEmpty()) continue
            runCatching { secrets.get(p.id) }.getOrNull()?.let { passwords[p.id] = it }
        }
        return AppRoutingConfig(settings, passwords)
    }

    fun hasPassword(proxyId: String): Boolean = runCatching { secrets.get(proxyId) != null }.getOrDefault(false)

    /** Saves [proxy]; a non-empty [password] replaces the stored one, an empty one keeps it. */
    fun saveProxy(proxy: CustomProxy, password: String): AppRoutingSettings {
        val current = load()
        val p = AppRoutingRules.normalizeProxy(proxy, current.proxies)
        when {
            p.username.isEmpty() -> secrets.delete(p.id)
            password.isNotEmpty() -> secrets.set(p.id, password)
        }
        val next = current.copy(
            proxies = if (current.proxies.any { it.id == p.id }) current.proxies.map { if (it.id == p.id) p else it }
            else current.proxies + p,
        )
        save(next)
        return next
    }

    fun deleteProxy(id: String): AppRoutingSettings {
        secrets.delete(id)
        return AppRoutingRules.removeProxy(load(), id).also(::save)
    }

    fun saveRoute(route: AppRoute): AppRoutingSettings {
        val current = load()
        val r = AppRoutingRules.normalizeRoute(route, current.proxies)
        return current.copy(routes = AppRoutingRules.upsertRoute(current.routes, r)).also(::save)
    }

    fun deleteRoute(id: String): AppRoutingSettings =
        load().let { it.copy(routes = it.routes.filterNot { r -> r.id == id }) }.also(::save)

    /** Packages that must stay inside the VPN, or their routes could never apply. */
    fun routedPackages(): Set<String> =
        if (supported) load().routes.mapTo(HashSet()) { it.packageName } else emptySet()

    companion object {
        /** The engine learns which app owns a socket only on Android 10+, so PROCESS-NAME rules
         *  cannot match below that. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

private fun atomicWrite(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.writeText(text)
        tmp.delete()
    }
}

/** Passwords encrypted with an AES-GCM key that never leaves the Android Keystore. */
class KeystoreProxySecrets(filesDir: File) : ProxySecrets {
    private val file = File(filesDir, "app_routing_secrets.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    private fun read(): Map<String, String> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())

    private fun write(map: Map<String, String>) {
        atomicWrite(file, json.encodeToString(serializer, map))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun get(proxyId: String): String? {
        val sealed = Base64.decode(read()[proxyId] ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_SIZE))
        return String(cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE), Charsets.UTF_8)
    }

    override fun set(proxyId: String, password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        write(read() + (proxyId to Base64.encodeToString(sealed, Base64.NO_WRAP)))
    }

    override fun delete(proxyId: String) {
        val current = read()
        if (proxyId in current) write(current - proxyId)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "mikan_app_routing"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
