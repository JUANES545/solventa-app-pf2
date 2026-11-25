package co.solventa.mobile.feature.auth

import java.time.LocalDate
import org.json.JSONObject

interface CompleteRegistrationApi {
    fun registerComplete(name: String, email: String, password: String, profile: RegistrationProfile): AuthUser
}
data class RegistrationProfile(val documentType:String, val number:String, val phone:String, val birth:LocalDate, val profiling:Boolean) {
    fun valid(today:LocalDate = LocalDate.now()):Boolean {
        val pattern=when(documentType) {
            "cc" -> "[0-9]{6,10}"
            "ce" -> "[0-9]{6,12}"
            "passport" -> "[A-Z0-9]{5,16}"
            "ppt" -> "[0-9]{7,10}"
            else -> return false
        }
        return number.matches(Regex(pattern)) && phone.matches(Regex("\\+57[0-9]{10}")) && birth < today
    }
    internal fun json() = JSONObject().put("tipoDocumento",documentType).put("numeroDocumento",number)
        .put("telefono",phone).put("fechaNacimiento",birth.toString())
        .put("consentimiento",JSONObject().put("autorizado",profiling).put("versionTerminos","1.0"))
}
