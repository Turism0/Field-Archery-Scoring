package uk.co.fnurble.nfas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import uk.co.fnurble.nfas.ui.NfasNavHost
import uk.co.fnurble.nfas.ui.theme.NfasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NfasTheme {
                NfasNavHost()
            }
        }
    }
}
