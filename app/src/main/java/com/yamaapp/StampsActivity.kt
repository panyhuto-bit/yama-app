package com.yamaapp

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 登頂スタンプ帳 */
class StampsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mountains = Storage.mountains(this)
        val stamps = Storage.stamps(this)
        val got = mountains.count { stamps[it.name]?.isNotEmpty() == true }

        val title = TextView(this).apply {
            text = "スタンプ帳　$got / ${mountains.size}"
            textSize = 22f
            setPadding(48, 64, 48, 24)
        }
        val items = mountains.map { m ->
            val d = stamps[m.name]
            if (d.isNullOrEmpty()) "☆　${m.name}　まだ"
            else "★　${m.name}　${d.size}回（${d.joinToString(", ")}）"
        }
        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@StampsActivity, android.R.layout.simple_list_item_1, items)
        }
        val hint = TextView(this).apply {
            text = "山を増やすには、地図で山頂を長押しして名前を付けてください"
            setPadding(48, 24, 48, 48)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(hint)
            addView(list)
        })
    }
}
