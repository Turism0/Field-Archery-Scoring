package uk.co.fieldarcheryscoring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import uk.co.fieldarcheryscoring.ui.FieldArcheryNavHost
import uk.co.fieldarcheryscoring.ui.theme.FieldArcheryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FieldArcheryTheme {
                FieldArcheryNavHost()
            }
        }
    }
}
