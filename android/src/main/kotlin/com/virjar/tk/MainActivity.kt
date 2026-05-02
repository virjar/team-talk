package com.virjar.tk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.virjar.tk.database.AppDatabase
import com.virjar.tk.storage.TokenStorage
import com.virjar.tk.util.initClipboardHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        AppDatabase.init(this)
        TokenStorage.init(this)
        initClipboardHelper(this)
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppPreview() {
    App()
}
