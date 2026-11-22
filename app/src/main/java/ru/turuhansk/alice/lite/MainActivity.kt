package ru.turuhansk.alice.lite

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "Тест: приложение живо?"
        tv.textSize = 20f
        tv.setPadding(32, 64, 32, 64)
        setContentView(tv)
    }
}
