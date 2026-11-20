package dev.emusync.app.debug

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class DebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "EmuSync (debug)" })
    }
}
