package co.solventa.mobile.feature.policy

import co.solventa.mobile.BuildConfig
import co.solventa.mobile.feature.claim.claimUuid
import co.solventa.mobile.feature.evidence.MobileEvidenceApi
import co.solventa.mobile.feature.auth.AuthHttpError
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import org.json.JSONObject

internal class PolicyActionsApi @Inject constructor(private val http: MobileEvidenceApi) {
    suspend fun eligibility(actor: String, policy: String) = http.session(actor) {
        http.request("GET","/polizas/${claimUuid(policy)}/cancelacion",it.accessToken).also { response ->
            require(response.getString("polizaId")==policy)
        }
    }
    suspend fun cancel(actor: String, policy: String, hash: String, reason: String, key: String) = http.session(actor) {
        require(hash.matches(Regex("[a-f0-9]{64}")) && reason in setOf("ya_no_la_necesito","costo","cambio_de_aseguradora","insatisfaccion","otro"))
        http.request("POST","/polizas/${claimUuid(policy)}/cancelaciones",it.accessToken,JSONObject()
            .put("confirma",true).put("motivoCancelacion",reason).put("liquidacionHash",hash),claimUuid(key),operationId=null)
            .also { response -> require(response.getString("polizaId")==policy && response.getString("estado")=="cancelada") }
    }
    suspend fun document(actor: String, policy: String) = http.session(actor) { grant ->
        val root = "/polizas/${claimUuid(policy)}"
        val meta = http.request("GET","$root/documento",grant.accessToken)
        require(meta.getString("polizaId")==policy)
        val doc = claimUuid(meta.getString("documentoId"))
        val connection = URL("${BuildConfig.AUTH_BASE_URL}$root/documentos/$doc/descarga").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects=false
            connection.useCaches=false
            connection.connectTimeout=10000;connection.readTimeout=20000
            connection.setRequestProperty("Cache-Control","no-store")
            connection.setRequestProperty("Accept","text/html")
            connection.setRequestProperty("Authorization","Bearer ${grant.accessToken}")
            if(connection.responseCode!=200) throw AuthHttpError(connection.responseCode)
            require(connection.contentType.substringBefore(';').trim().equals("text/html",true))
            val bytes=connection.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream()
                val buf=ByteArray(8192)
                while(true){val count=input.read(buf); if(count<0) break; require(output.size()+count<=4*1024*1024);output.write(buf,0,count)}
                output.toByteArray()
            }
            require(policyDocumentMatches(bytes,connection.getHeaderField("X-Content-SHA256")))
            bytes
        } finally {connection.disconnect()}
    }
}
