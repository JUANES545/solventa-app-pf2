package co.solventa.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.ViewModelProvider
import co.solventa.mobile.feature.auth.AuthViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val auth by lazy { ViewModelProvider(this)[AuthViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            SolventaApp(auth)
        }
    }

    override fun onResume() {
        super.onResume()
        auth.restore()
    }
}
