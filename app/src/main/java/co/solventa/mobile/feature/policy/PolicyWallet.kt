package co.solventa.mobile.feature.policy

import android.content.Context
import co.solventa.mobile.feature.auth.androidAesKey
import co.solventa.mobile.feature.auth.AuthError
import co.solventa.mobile.feature.auth.AuthProblem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject

internal data class WalletSnapshot(val updated: Long, val policies: List<Policy>)

internal class PolicyWallet @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("policy-wallet", Context.MODE_PRIVATE)
    private val alias = "solventa.mobile.wallet.v1"

    fun clear() { if (!prefs.edit().clear().commit()) throw AuthProblem(AuthError.STORAGE) }

    fun write(actor: String, policies: List<Policy>) {
        val rows = JSONArray()
        for (p in policies) rows.put(JSONArray().put(p.id).put(p.product.name).put(p.start.toString())
            .put(p.end.toString()).put(p.status.name).put(p.simulated).put(p.number).put(p.mainCoverage))
        val data = JSONObject().put("time", System.currentTimeMillis()).put("policies", rows)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, androidAesKey(alias)) }
        cipher.updateAAD(actor.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(data.toString().toByteArray())
        if (!prefs.edit().putString("snapshot", Base64.getEncoder().encodeToString(bytes)).commit()) throw AuthProblem(AuthError.STORAGE)
    }

    fun read(actor: String): WalletSnapshot? {
        val value = prefs.getString("snapshot", null) ?: return null
        val bytes = Base64.getDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, androidAesKey(alias), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(actor.toByteArray())
        }
        val plaintext = try { cipher.doFinal(bytes.copyOfRange(12, bytes.size)) }
        catch (_: GeneralSecurityException) { clear(); return null }
        val data = JSONObject(String(plaintext, Charsets.UTF_8))
        val rows = data.getJSONArray("policies")
        return WalletSnapshot(data.getLong("time"), List(rows.length()) { i ->
            val row = rows.getJSONArray(i)
            Policy(row.getString(0), PolicyProduct.valueOf(row.getString(1)), LocalDate.parse(row.getString(2)),
                LocalDate.parse(row.getString(3)), PolicyStatus.valueOf(row.getString(4)), row.getBoolean(5),
                row.getString(6), row.getString(7))
        })
    }
}
