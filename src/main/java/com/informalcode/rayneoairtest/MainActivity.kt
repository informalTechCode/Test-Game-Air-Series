package com.informalcode.rayneoairtest

import android.graphics.Color
import android.media.MediaPlayer
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.informalcode.informalspacegame.R

class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: CubeRenderer
    private lateinit var statusText: TextView
    private lateinit var hudText: TextView
    private lateinit var affirmationText: TextView
    private lateinit var gameOverText: TextView
    private lateinit var restartButton: Button
    private lateinit var rayNeoSession: RayNeoSession
    private var bgmPlayer: MediaPlayer? = null
    private var isActivityVisible = false
    private var isGameAlive = true
    private var shouldAutoRecenter = true
    private var autoRecenterAfterMs = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private val clearAffirmationRunnable = Runnable { affirmationText.text = "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = CubeRenderer()
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    renderer.fireLaser()
                    true
                } else {
                    false
                }
            }
        }

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            setPadding(20, 20, 20, 20)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "Waiting for RayNeo Air 4 Pro..."
        }

        hudText = TextView(this).apply {
            setTextColor(Color.rgb(255, 226, 140))
            setBackgroundColor(0x44101020)
            setPadding(20, 14, 20, 14)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            text = "Score: 0  Level: 1"
        }

        affirmationText = TextView(this).apply {
            setTextColor(Color.rgb(170, 255, 195))
            setBackgroundColor(0x33101020)
            setPadding(20, 10, 20, 10)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            text = ""
        }

        gameOverText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA16090C.toInt())
            setPadding(34, 30, 34, 30)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            visibility = View.GONE
        }

        restartButton = Button(this).apply {
            text = "Restart"
            visibility = View.GONE
            setOnClickListener {
                renderer.restartGame()
                isGameAlive = true
                updateBackgroundMusicState()
                gameOverText.visibility = View.GONE
                visibility = View.GONE
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                glView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                statusText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                )
            )
            addView(
                hudText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply {
                    topMargin = 20
                }
            )
            addView(
                affirmationText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply {
                    topMargin = 78
                }
            )
            addView(
                gameOverText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
            addView(
                restartButton,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                ).apply {
                    bottomMargin = 60
                }
            )
        }
        setContentView(root)
        initBackgroundMusic()

        renderer.setGameEventListener(
            object : CubeRenderer.GameEventListener {
                override fun onHudChanged(score: Int, level: Int, isAlive: Boolean) {
                    runOnUiThread {
                        isGameAlive = isAlive
                        updateBackgroundMusicState()
                        hudText.text = "Score: $score  Level: $level"
                        if (isAlive) {
                            if (gameOverText.visibility != View.GONE) {
                                gameOverText.visibility = View.GONE
                            }
                            if (restartButton.visibility != View.GONE) {
                                restartButton.visibility = View.GONE
                            }
                        }
                    }
                }

                override fun onGameOver(finalScore: Int) {
                    runOnUiThread {
                        isGameAlive = false
                        updateBackgroundMusicState()
                        val leaderboard = addAndLoadTopScores(finalScore)
                        gameOverText.text = buildGameOverText(finalScore, leaderboard)
                        gameOverText.visibility = View.VISIBLE
                        restartButton.visibility = View.VISIBLE
                    }
                }

                override fun onAffirmation(message: String) {
                    runOnUiThread {
                        affirmationText.text = message
                        uiHandler.removeCallbacks(clearAffirmationRunnable)
                        uiHandler.postDelayed(clearAffirmationRunnable, 1200L)
                    }
                }
            }
        )

        rayNeoSession = RayNeoSession(
            context = this,
            onStatus = { msg -> statusText.text = msg },
            onOrientation = { quat ->
                renderer.setOrientation(quat)
                if (shouldAutoRecenter && SystemClock.elapsedRealtime() >= autoRecenterAfterMs) {
                    renderer.recenterYawOnly()
                    shouldAutoRecenter = false
                    statusText.text = "Sensor initialized and centered"
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        shouldAutoRecenter = true
        autoRecenterAfterMs = SystemClock.elapsedRealtime() + AUTO_RECENTER_DELAY_MS
        glView.onResume()
        rayNeoSession.start()
        updateBackgroundMusicState()
    }

    override fun onPause() {
        isActivityVisible = false
        updateBackgroundMusicState()
        rayNeoSession.stop()
        uiHandler.removeCallbacks(clearAffirmationRunnable)
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        bgmPlayer?.release()
        bgmPlayer = null
        super.onDestroy()
    }

    private fun initBackgroundMusic() {
        bgmPlayer = MediaPlayer.create(this, R.raw.last_fortress_rising)?.apply {
            isLooping = true
            setVolume(BGM_VOLUME, BGM_VOLUME)
        }
    }

    private fun updateBackgroundMusicState() {
        val player = bgmPlayer ?: return
        val shouldPlay = isActivityVisible && isGameAlive
        if (shouldPlay) {
            if (!player.isPlaying) {
                player.start()
            }
        } else if (player.isPlaying) {
            player.pause()
        }
    }

    private companion object {
        const val AUTO_RECENTER_DELAY_MS = 2000L
        const val PREFS_NAME = "game_scores"
        const val SCORES_KEY = "best_scores_csv"
        const val MAX_LEADERBOARD_ENTRIES = 8
        const val BGM_VOLUME = 0.14f
    }

    private fun addAndLoadTopScores(newScore: Int): List<Int> {
        val scores = loadSavedScores().toMutableList()
        scores.add(newScore)
        val topScores = scores
            .filter { it >= 0 }
            .sortedDescending()
            .take(MAX_LEADERBOARD_ENTRIES)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(SCORES_KEY, topScores.joinToString(","))
            .apply()
        return topScores
    }

    private fun loadSavedScores(): List<Int> {
        val csv = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(SCORES_KEY, "")
            .orEmpty()
        if (csv.isBlank()) {
            return emptyList()
        }
        return csv
            .split(",")
            .mapNotNull { token -> token.trim().toIntOrNull() }
    }

    private fun buildGameOverText(finalScore: Int, leaderboard: List<Int>): String {
        val lines = StringBuilder()
        lines.append("You blew up.\n")
        lines.append("Run score: ").append(finalScore).append('\n')
        lines.append('\n')
        lines.append("Leaderboard\n")
        if (leaderboard.isEmpty()) {
            lines.append("No scores yet.")
            return lines.toString()
        }
        leaderboard.forEachIndexed { index, score ->
            lines.append(index + 1).append(". ").append(score).append('\n')
        }
        return lines.toString().trimEnd()
    }
}
