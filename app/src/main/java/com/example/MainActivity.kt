package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.network.configureAppCheck
import com.example.ui.EksinViewModel
import com.example.ui.MainScaffold
import com.example.ui.theme.EksinOledTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            EksinOledTheme {
                val context = LocalContext.current
                val viewModel: EksinViewModel = viewModel(
                    factory = EksinViewModel.provideFactory(context)
                )
                MainScaffold(viewModel = viewModel)
            }
        }
    }
}
