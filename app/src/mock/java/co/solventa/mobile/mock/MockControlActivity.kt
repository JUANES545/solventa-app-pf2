package co.solventa.mobile.mock

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.solventa.mobile.MainActivity

class MockControlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var scenario by remember { mutableStateOf(MockInitializer.backend.scenario) }
            MaterialTheme {
                Surface {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Solventa · Flujos simulados", style = MaterialTheme.typography.headlineSmall)
                        Text("App aislada, sin AWS. Usa solo datos y fotos ficticios. " +
                            "Las respuestas se simulan; no confirman operaciones reales.")
                        Text("Ingreso y registro: ${MockBackend.EMAIL}\nClave ficticia: ${MockBackend.PASSWORD}")
                        MockScenario.entries.forEach { option ->
                            TextButton(onClick = {
                                MockInitializer.backend.choose(option)
                                scenario = option
                            }) {
                                RadioButton(selected = scenario == option, onClick = null)
                                Text(option.label, Modifier.padding(start = 8.dp))
                            }
                        }
                        Text("Vuelve a este panel desde el icono para cambiar de escenario y reintenta " +
                            "la consulta. No se borran borradores ni pendientes. Cámara, permisos, " +
                            "ubicación y biometría usan el dispositivo real.")
                        Button(onClick = { startActivity(Intent(this@MockControlActivity, MainActivity::class.java)) }) {
                            Text("Abrir Solventa")
                        }
                    }
                }
            }
        }
    }
}
