package com.infinitefamily

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infinitefamily.ui.FamilyTreeTheme
import com.infinitefamily.ui.FamilyViewModel
import com.infinitefamily.ui.MainScreen
import com.infinitefamily.ui.Pencil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FamilyTreeTheme {
                Surface(Modifier.fillMaxSize(), color = Pencil.Paper) {
                    val vm: FamilyViewModel = viewModel()
                    MainScreen(vm)
                }
            }
        }
    }
}
