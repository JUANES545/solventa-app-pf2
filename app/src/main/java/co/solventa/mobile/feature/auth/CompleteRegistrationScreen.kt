package co.solventa.mobile.feature.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.solventa.mobile.R
import co.solventa.mobile.core.ui.*
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun CompleteRegistrationScreen(busy:Boolean,error:AuthError?,allowPhotos:Boolean,back:()->Unit,
    submit:(RegistrationProfile,Boolean)->Unit) {
    var type by remember { mutableStateOf("cc") }; var menu by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") };var phone by remember { mutableStateOf("") }
    var birth by remember { mutableStateOf("") };var terms by remember { mutableStateOf(false) }
    var profiling by remember { mutableStateOf(false) };var photos by remember { mutableStateOf(false) }
    val types = mapOf("cc" to R.string.reg_cc,"ce" to R.string.reg_ce,"passport" to R.string.reg_passport,"ppt" to R.string.reg_ppt)
    val profile = birth.takeIf { it.isNotBlank() }?.let { RegistrationProfile(type,number.trim().uppercase(Locale.ROOT),"+57"+phone,LocalDate.parse(it),profiling) }
    SolventaPage(header={SolventaScreenHeader(stringResource(R.string.reg_profile),stringResource(R.string.catalog_back),back,!busy)},
        actions={Button(enabled=!busy && terms && profile?.valid()==true,onClick={submit(checkNotNull(profile),photos)},modifier=Modifier.fillMaxWidth()){
            Text(stringResource(if(photos) R.string.registration_continue else R.string.create_account))
        }}) {
        Box {
            OutlinedButton(enabled=!busy,onClick={menu=true}) { Text(stringResource(types.getValue(type))) }
            DropdownMenu(menu,{menu=false}) { types.forEach { (id,label) ->
                DropdownMenuItem(text={Text(stringResource(label))},onClick={type=id;menu=false})
            } }
        }
        OutlinedTextField(number,{number=it},Modifier.fillMaxWidth(),singleLine=true,enabled=!busy,label={Text(stringResource(R.string.reg_doc))},
            keyboardOptions=KeyboardOptions(keyboardType=if(type=="passport") KeyboardType.Text else KeyboardType.Number))
        OutlinedTextField(phone,{phone=it},Modifier.fillMaxWidth(),singleLine=true,enabled=!busy,prefix={Text("+57 ")},
            label={Text(stringResource(R.string.reg_phone))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone))
        SolventaDateField(stringResource(R.string.reg_birth),birth,!busy, latest=LocalDate.now().minusDays(1)){birth=it}
        ProfileCheck(R.string.registration_terms,terms,!busy){terms=it}
        ProfileCheck(R.string.reg_profiling,profiling,!busy){profiling=it}
        if(allowPhotos) ProfileCheck(R.string.reg_photos,photos,!busy){photos=it}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { AuthErrorNotice(stringResource(authErrorMessage(it))) }
    }
}

@Composable
private fun ProfileCheck(label:Int,value:Boolean,enabled:Boolean,change:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value,enabled=enabled,role=Role.Checkbox,onValueChange=change),
        verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(value,null);Text(stringResource(label),Modifier.padding(start=8.dp))
    }
}
