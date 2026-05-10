package moe.telecom.xbclient

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    private val viewModel: XbClientViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is XbClientEvent.Message -> Toast.makeText(this@SettingsActivity, event.text, Toast.LENGTH_SHORT).show()
                        is XbClientEvent.OpenExternalUrl -> startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                                .addCategory(Intent.CATEGORY_BROWSABLE)
                        )
                        else -> Unit
                    }
                }
            }
        }
        setContent {
            XbClientSettingsApp(viewModel, onClose = ::finish)
        }
    }
}
