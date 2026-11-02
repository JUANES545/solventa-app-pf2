package co.solventa.mobile.feature.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.Signature
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class KeyStoreBoundaryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store = mock(KeyStore::class.java)
    private lateinit var keyStores: MockedStatic<KeyStore>
    private val alias = "solventa.mobile.biometric.11111111-1111-4111-8111-111111111111"
    private val grant = AuthGrant(
        AuthUser("11111111-1111-4111-8111-111111111111", "Cliente", "cliente@example.org"),
        "synthetic-access", "synthetic-refresh", 4_000_000_000_000, 4_000_000_060_000,
    )

    @Before fun setUp() {
        keyStores = mockStatic(KeyStore::class.java)
        keyStores.`when`<KeyStore> { KeyStore.getInstance("AndroidKeyStore") }.thenReturn(store)
    }

    @After fun tearDown() { keyStores.close() }

    @Test fun encryptedGrantRoundTripsWithRandomIvWithoutPlaintextPersistence() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val generator = mock(KeyGenerator::class.java)
        `when`(generator.generateKey()).thenReturn(key)
        mockStatic(KeyGenerator::class.java).use { generators ->
            generators.`when`<KeyGenerator> { KeyGenerator.getInstance("AES", "AndroidKeyStore") }.thenReturn(generator)
            val auth = EncryptedAuthStore(context)
            assertNull(auth.read())
            auth.write(grant)
            val preferences = context.getSharedPreferences("mobile-auth", Context.MODE_PRIVATE)
            val first = requireNotNull(preferences.getString("grant", null))
            assertFalse(first.contains(grant.accessToken) || first.contains(grant.refreshToken))
            verify(generator).init(org.mockito.ArgumentMatchers.argThat<AlgorithmParameterSpec> {
                it is KeyGenParameterSpec && it.keystoreAlias == "solventa.mobile.auth.v1" &&
                    it.blockModes.contentEquals(arrayOf("GCM"))
            })
            `when`(store.getKey("solventa.mobile.auth.v1", null)).thenReturn(key)
            assertEquals(grant, auth.read())
            auth.write(grant)
            assertNotEquals(first, preferences.getString("grant", null))
            assertEquals(grant, auth.read())
            auth.clear()
            assertNull(auth.read())
        }
    }

    @Test fun corruptShortAndTamperedCiphertextClearsTheGrantAndReportsStorageFailure() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        `when`(store.getKey("solventa.mobile.auth.v1", null)).thenReturn(key)
        val auth = EncryptedAuthStore(context)
        val preferences = context.getSharedPreferences("mobile-auth", Context.MODE_PRIVATE)
        auth.write(grant)
        val encoded = requireNotNull(preferences.getString("grant", null))
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        for (invalid in listOf("!", "AA==", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))) {
            preferences.edit().putString("grant", invalid).commit()
            assertEquals(AuthError.STORAGE, assertThrows(AuthProblem::class.java) { auth.read() }.reason)
            assertNull(auth.read())
        }
    }

    @Test fun failedPreferenceCommitsNeverReportSuccessfulPersistenceOrRemoval() {
        val fakeContext = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(fakeContext.getSharedPreferences("isolated", Context.MODE_PRIVATE)).thenReturn(preferences)
        `when`(preferences.edit()).thenReturn(editor)
        val key = KeyGenerator.getInstance("AES").generateKey()
        `when`(store.getKey("isolated", null)).thenReturn(key)
        val auth = EncryptedAuthStore(fakeContext, "isolated", "isolated")
        assertEquals(AuthError.STORAGE, assertThrows(AuthProblem::class.java) { auth.write(grant) }.reason)
        assertEquals(AuthError.STORAGE, assertThrows(AuthProblem::class.java) { auth.clear() }.reason)
    }

    @Test fun biometricSignatureUsesTheOwnedPrivateKeyAndDeleteOnlyRemovesThatAlias() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        `when`(store.getKey(alias, null)).thenReturn(pair.private)
        val signature = AndroidBiometricKeys().signature(alias)
        val message = byteArrayOf(1, 2, 3)
        signature.update(message)
        val signed = signature.sign()
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public); update(message); verify(signed)
        })
        AndroidBiometricKeys().delete(alias)
        verify(store).deleteEntry(alias)
        `when`(store.getKey(alias, null)).thenReturn(null)
        assertEquals(BiometricFailure.KEY_MISSING,
            assertThrows(BiometricProblem::class.java) { AndroidBiometricKeys().signature(alias) }.reason)
    }

    @Suppress("DEPRECATION")
    @Test fun generatedKeyMustRequireStrongBiometricsForEveryOperation() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val generator = mock(KeyPairGenerator::class.java)
        val factory = mock(KeyFactory::class.java)
        val info = mock(KeyInfo::class.java)
        `when`(generator.generateKeyPair()).thenReturn(pair)
        `when`(factory.getKeySpec(pair.private, KeyInfo::class.java)).thenReturn(info)
        mockStatic(KeyPairGenerator::class.java).use { generators ->
            mockStatic(KeyFactory::class.java).use { factories ->
                generators.`when`<KeyPairGenerator> { KeyPairGenerator.getInstance("EC", "AndroidKeyStore") }.thenReturn(generator)
                factories.`when`<KeyFactory> { KeyFactory.getInstance(pair.private.algorithm, "AndroidKeyStore") }.thenReturn(factory)
                for (rejectedPolicy in 0..4) {
                    `when`(info.isUserAuthenticationRequired).thenReturn(rejectedPolicy != 1)
                    `when`(info.isInvalidatedByBiometricEnrollment).thenReturn(rejectedPolicy != 2)
                    `when`(info.userAuthenticationValidityDurationSeconds).thenReturn(if (rejectedPolicy == 3) 30 else 0)
                    `when`(info.userAuthenticationType).thenReturn(
                        if (rejectedPolicy == 4) KeyProperties.AUTH_DEVICE_CREDENTIAL else KeyProperties.AUTH_BIOMETRIC_STRONG)
                    if (rejectedPolicy == 0) {
                        val created = AndroidBiometricKeys().create()
                        assertTrue(created.alias.startsWith("solventa.mobile.biometric."))
                        assertArrayEquals(pair.public.encoded, created.publicKeySpki)
                    } else {
                        assertEquals(BiometricFailure.INSECURE_KEY,
                            assertThrows(BiometricProblem::class.java) { AndroidBiometricKeys().create() }.reason)
                    }
                }
                verify(store, times(4)).deleteEntry(anyString())
                for (failure in listOf(java.security.InvalidAlgorithmParameterException("unavailable"), ProviderException("unavailable"))) {
                    doThrow(failure).`when`(generator).initialize(any(AlgorithmParameterSpec::class.java))
                    assertEquals(BiometricFailure.KEY_UNAVAILABLE,
                        assertThrows(BiometricProblem::class.java) { AndroidBiometricKeys().create() }.reason)
                }
                verify(store, times(6)).deleteEntry(anyString())
            }
        }
    }

    @Test fun keystoreFailuresNeverBecomeAnAuthorizedSignature() {
        for (failure in listOf(KeyStoreException("unavailable"), ProviderException("unavailable"))) {
            doThrow(failure).`when`(store).getKey(alias, null)
            assertEquals(BiometricFailure.KEY_UNAVAILABLE,
                assertThrows(BiometricProblem::class.java) { AndroidBiometricKeys().signature(alias) }.reason)
            doThrow(failure).`when`(store).deleteEntry(alias)
            assertEquals(BiometricFailure.KEY_UNAVAILABLE,
                assertThrows(BiometricProblem::class.java) { AndroidBiometricKeys().delete(alias) }.reason)
        }
        for (failure in listOf(java.security.cert.CertificateException("unavailable"), ProviderException("unavailable"), IOException("unavailable"))) {
            doThrow(failure).`when`(store).load(null as KeyStore.LoadStoreParameter?)
            assertEquals(BiometricFailure.KEY_UNAVAILABLE,
                assertThrows(BiometricProblem::class.java) { AndroidBiometricKeys().create() }.reason)
        }
    }
}
