package com.gottago.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RemoveToiletActivity : AppCompatActivity() {

    private lateinit var toilet: Toilet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = Repo.find(intent.getStringExtra("id"))
        if (t == null) { finish(); return }
        toilet = t
        setContentView(R.layout.activity_remove)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvName).text = t.name
        findViewById<TextView>(R.id.tvMeta).text = "${Geo.fmt(t.distanceM)} away"
        showVotes()

        val send = findViewById<Button>(R.id.btnSend)
        if (t.myVote == "remove") {
            send.isEnabled = false
            send.text = "You already voted to remove it"
        }
        send.setOnClickListener { sendVote(send) }
    }

    private fun net() = maxOf(0, toilet.removeVotes - toilet.confirmVotes)

    private fun showVotes() {
        val n = net().coerceAtMost(3)
        findViewById<TextView>(R.id.tvVotes).text = "$n of 3"
        findViewById<ProgressBar>(R.id.progress).progress = n
    }

    private fun sendVote(button: Button) {
        val t = toilet
        val movedPin = findViewById<RadioGroup>(R.id.reasons).checkedRadioButtonId == R.id.rMoved
        button.isEnabled = false
        button.text = "Sending…"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Community.vote(t, remove = true) }.isSuccess }
            if (!ok) {
                button.isEnabled = true
                button.text = "Send removal vote"
                Toast.makeText(this@RemoveToiletActivity, "Couldn't send. Check your internet.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (t.myVote == "confirm") t.confirmVotes = maxOf(0, t.confirmVotes - 1)
            if (t.myVote != "remove") t.removeVotes += 1
            t.myVote = "remove"

            if (t.hidden) {
                Repo.removeLocal(t.id)
                Toast.makeText(this@RemoveToiletActivity, "Removed for everyone. Thanks!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@RemoveToiletActivity, "Vote sent (${net()} of 3)", Toast.LENGTH_LONG).show()
            }
            if (movedPin && Community.enabled) {
                Toast.makeText(this@RemoveToiletActivity, "Now add it at the right spot", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@RemoveToiletActivity, AddToiletActivity::class.java))
            }
            finish()
        }
    }
}
