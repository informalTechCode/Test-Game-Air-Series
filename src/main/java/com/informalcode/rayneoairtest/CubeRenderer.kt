package com.informalcode.rayneoairtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.tan
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class CubeRenderer : GLSurfaceView.Renderer {

    interface GameEventListener {
        fun onHudChanged(score: Int, level: Int, isAlive: Boolean)
        fun onGameOver(finalScore: Int)
        fun onAffirmation(message: String)
    }

    private data class Rock(
        var x: Float,
        var y: Float,
        var z: Float,
        var vx: Float,
        var vy: Float,
        var vz: Float,
        val radius: Float,
        val isBomb: Boolean,
        val isBigGold: Boolean
    )

    private data class Star(
        val x: Float,
        val y: Float,
        val z: Float,
        val size: Float,
        val brightness: Float
    )

    private data class GoldDot(
        var x: Float,
        var y: Float,
        var z: Float,
        val radius: Float
    )

    private data class Laser(
        var x: Float,
        var y: Float,
        var z: Float,
        var vx: Float,
        var vy: Float,
        var vz: Float
    )

    private val vertices = floatArrayOf(
        -1f, -1f, -1f,
        1f, -1f, -1f,
        1f, 1f, -1f,
        -1f, 1f, -1f,
        -1f, -1f, 1f,
        1f, -1f, 1f,
        1f, 1f, 1f,
        -1f, 1f, 1f
    )

    private val indices = shortArrayOf(
        0, 1, 2, 0, 2, 3,
        4, 5, 6, 4, 6, 7,
        0, 1, 5, 0, 5, 4,
        2, 3, 7, 2, 7, 6,
        0, 3, 7, 0, 7, 4,
        1, 2, 6, 1, 6, 5
    )

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(vertices)
            position(0)
        }

    private val indexBuffer: ShortBuffer =
        ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(indices)
            position(0)
        }
    private val spriteVertices = floatArrayOf(
        -0.5f, -0.5f, 0f,
        0.5f, -0.5f, 0f,
        0.5f, 0.5f, 0f,
        -0.5f, 0.5f, 0f
    )
    private val spriteTexCoords = floatArrayOf(
        0f, 1f,
        1f, 1f,
        1f, 0f,
        0f, 0f
    )
    private val spriteIndices = shortArrayOf(0, 1, 2, 0, 2, 3)
    private val spriteVertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(spriteVertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(spriteVertices)
            position(0)
        }
    private val spriteTexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(spriteTexCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(spriteTexCoords)
            position(0)
        }
    private val spriteIndexBuffer: ShortBuffer =
        ByteBuffer.allocateDirect(spriteIndices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(spriteIndices)
            position(0)
        }

    private val projection = FloatArray(16)
    private val baseView = FloatArray(16)
    private val view = FloatArray(16)
    private val headViewRot = FloatArray(16)
    private val model = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val mvp = FloatArray(16)

    private val orientationLock = Any()
    private val orientation = floatArrayOf(1f, 0f, 0f, 0f) // w, x, y, z
    private val recenterReference = floatArrayOf(1f, 0f, 0f, 0f) // w, x, y, z
    private val renderedHeadQRel = floatArrayOf(1f, 0f, 0f, 0f)
    private var renderedHeadQRelInitialized = false
    @Volatile
    private var snapViewOrientation = false

    @Volatile
    private var restartRequested = false
    @Volatile
    private var pendingShots = 0
    @Volatile
    private var gameEventListener: GameEventListener? = null

    private val random = Random(System.currentTimeMillis())
    private val rocks = mutableListOf<Rock>()
    private val goldDots = mutableListOf<GoldDot>()
    private val stars = mutableListOf<Star>()
    private val lasers = mutableListOf<Laser>()

    private var score = 0
    private var elapsedSec = 0f
    private var spawnTimerSec = 0.5f
    private var goldMissingSec = 0f
    private var alive = true
    private var gameOverSent = false
    private var lastFrameNs = 0L
    private var aspectRatio = 1f
    private var showGoldArrow = false
    private var arrowPosX = 0f
    private var arrowPosY = 0f
    private var arrowAngleDeg = 0f
    private var shipHeadingDeg = 0f
    private var prevForwardInitialized = false
    private val prevForward = FloatArray(3)
    private var shipAimX = 0f
    private var shipAimY = 1f
    private var shipOffsetX = 0f
    private var shipOffsetY = 0f
    private val shipWorldPos = FloatArray(3)
    private val shipWorldDir = FloatArray(3)

    private var program = 0
    private var posHandle = -1
    private var mvpHandle = -1
    private var colorHandle = -1
    private var spriteProgram = 0
    private var spritePosHandle = -1
    private var spriteUvHandle = -1
    private var spriteMvpHandle = -1
    private var spriteTextureHandle = -1
    private val iconTextures = IntArray(ICON_COUNT)

    fun setGameEventListener(listener: GameEventListener?) {
        gameEventListener = listener
    }

    fun restartGame() {
        restartRequested = true
    }

    fun fireLaser() {
        pendingShots = (pendingShots + 1).coerceAtMost(MAX_PENDING_SHOTS)
    }

    fun setOrientation(wxyz: FloatArray) {
        if (wxyz.size < 4) {
            return
        }
        synchronized(orientationLock) {
            normalizeInto(wxyz, orientation)
        }
    }

    fun recenter() {
        synchronized(orientationLock) {
            recenterReference[0] = orientation[0]
            recenterReference[1] = orientation[1]
            recenterReference[2] = orientation[2]
            recenterReference[3] = orientation[3]
        }
        snapViewOrientation = true
    }

    fun recenterYawOnly() {
        synchronized(orientationLock) {
            // Recenter only yaw. Including pitch/roll at init can make content jump vertically.
            val w = orientation[0]
            val x = orientation[1]
            val y = orientation[2]
            val z = orientation[3]
            val yaw = atan2(
                2f * (w * y + x * z),
                1f - 2f * (x * x + y * y)
            )
            val half = 0.5f * yaw
            recenterReference[0] = cos(half)
            recenterReference[1] = 0f
            recenterReference[2] = sin(half)
            recenterReference[3] = 0f
        }
        snapViewOrientation = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        spriteProgram = createProgram(SPRITE_VERTEX_SHADER, SPRITE_FRAGMENT_SHADER)
        spritePosHandle = GLES20.glGetAttribLocation(spriteProgram, "aPosition")
        spriteUvHandle = GLES20.glGetAttribLocation(spriteProgram, "aTexCoord")
        spriteMvpHandle = GLES20.glGetUniformLocation(spriteProgram, "uMvpMatrix")
        spriteTextureHandle = GLES20.glGetUniformLocation(spriteProgram, "uTexture")
        initIconTextures()

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(0.01f, 0.01f, 0.06f, 1f)
        if (stars.isEmpty()) {
            repeat(STAR_COUNT) {
                val dir = randomUnitVector()
                val dist = randomRange(STAR_MIN_DISTANCE, STAR_MAX_DISTANCE)
                stars.add(
                    Star(
                        x = dir[0] * dist,
                        y = dir[1] * dist,
                        z = dir[2] * dist,
                        size = randomRange(0.03f, 0.08f),
                        brightness = randomRange(0.55f, 1f)
                    )
                )
            }
        }
        lastFrameNs = 0L
        resetGame()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
        aspectRatio = aspect
        Matrix.perspectiveM(projection, 0, FOV_Y_DEG, aspect, 0.1f, 100f)
        Matrix.setIdentityM(baseView, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (restartRequested) {
            resetGame()
            restartRequested = false
        }

        val nowNs = System.nanoTime()
        var dtSec = 0.016f
        if (lastFrameNs > 0L) {
            dtSec = ((nowNs - lastFrameNs).toFloat() * 1e-9f).coerceIn(0.001f, 0.05f)
        }
        lastFrameNs = nowNs

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val q = FloatArray(4)
        val qRef = FloatArray(4)
        synchronized(orientationLock) {
            q[0] = orientation[0]
            q[1] = orientation[1]
            q[2] = orientation[2]
            q[3] = orientation[3]
            qRef[0] = recenterReference[0]
            qRef[1] = recenterReference[1]
            qRef[2] = recenterReference[2]
            qRef[3] = recenterReference[3]
        }

        val qRel = multiplyQuat(conjugateQuat(qRef), q)
        val smoothedQRel = smoothHeadOrientation(qRel, dtSec)

        // Camera rotation is the inverse of head pose.
        val qInv = conjugateQuat(smoothedQRel)
        quaternionToMatrix(qInv, headViewRot)
        Matrix.multiplyMM(view, 0, headViewRot, 0, baseView, 0)

        if (alive) {
            updateGame(dtSec, smoothedQRel)
            updateGoldArrow(smoothedQRel)
        } else {
            showGoldArrow = false
            shipHeadingDeg = 0f
        }

        renderStars()
        renderGoldDots()
        renderRocks()
        renderShip()
        renderGoldArrow()
        renderLasers()

        val level = 1 + (elapsedSec / LEVEL_DURATION_SEC).toInt()
        gameEventListener?.onHudChanged(score, level, alive)
        if (!alive && !gameOverSent) {
            gameOverSent = true
            gameEventListener?.onGameOver(score)
        }
    }

    private fun quaternionToMatrix(q: FloatArray, out: FloatArray) {
        val w = q[0]
        val x = q[1]
        val y = q[2]
        val z = q[3]

        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z

        out[0] = 1f - 2f * (yy + zz)
        out[1] = 2f * (xy + wz)
        out[2] = 2f * (xz - wy)
        out[3] = 0f

        out[4] = 2f * (xy - wz)
        out[5] = 1f - 2f * (xx + zz)
        out[6] = 2f * (yz + wx)
        out[7] = 0f

        out[8] = 2f * (xz + wy)
        out[9] = 2f * (yz - wx)
        out[10] = 1f - 2f * (xx + yy)
        out[11] = 0f

        out[12] = 0f
        out[13] = 0f
        out[14] = 0f
        out[15] = 1f
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)
        return programId
    }

    private fun compileShader(type: Int, source: String): Int {
        val shaderId = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shaderId, source)
        GLES20.glCompileShader(shaderId)
        return shaderId
    }

    private fun initIconTextures() {
        GLES20.glGenTextures(iconTextures.size, iconTextures, 0)
        loadIconTexture(ICON_SHIP, "➤", Color.argb(255, 220, 255, 235))
        loadIconTexture(ICON_BIG_GOLD, "\uD83D\uDFE1", Color.WHITE)
        loadIconTexture(ICON_GOLD, "\uD83D\uDFE1", Color.argb(255, 255, 230, 120))
        loadIconTexture(ICON_DOT, "•", Color.argb(255, 255, 236, 160))
        loadIconTexture(ICON_BOMB, "🪨", Color.WHITE)
        loadIconTexture(ICON_ARROW, "➣", Color.argb(255, 255, 240, 120))
        loadIconTexture(ICON_LASER, "✦", Color.argb(255, 140, 255, 180))
        loadIconTexture(ICON_STAR, "✦", Color.argb(255, 220, 235, 255))
    }

    private fun loadIconTexture(index: Int, glyph: String, color: Int) {
        val textureId = iconTextures[index]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val bitmap = Bitmap.createBitmap(ICON_TEX_SIZE, ICON_TEX_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            textSize = ICON_TEX_SIZE * 0.78f
            typeface = Typeface.DEFAULT_BOLD
        }
        val y = (ICON_TEX_SIZE * 0.5f) - ((paint.descent() + paint.ascent()) * 0.5f)
        canvas.drawText(glyph, ICON_TEX_SIZE * 0.5f, y, paint)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
    }

    private fun updateGame(dtSec: Float, headQRel: FloatArray) {
        elapsedSec += dtSec
        updateShipTurn(headQRel, dtSec)
        processPendingShots(headQRel)

        val difficulty = 1f + elapsedSec / LEVEL_DURATION_SEC
        val maxRocks = (12 + (difficulty * 3f).toInt()).coerceAtMost(34)
        var hasBigGold = rocks.any { it.isBigGold }
        goldMissingSec = if (hasBigGold) 0f else goldMissingSec + dtSec
        spawnTimerSec -= dtSec
        while (spawnTimerSec <= 0f && rocks.size < maxRocks) {
            val spawnGold = !hasBigGold && (
                goldMissingSec >= GOLD_FORCE_RESPAWN_SEC ||
                    random.nextFloat() < GOLD_SPAWN_CHANCE_WHEN_MISSING
                )
            rocks.add(createRock(difficulty, spawnGold))
            if (spawnGold) {
                hasBigGold = true
                goldMissingSec = 0f
            }
            spawnTimerSec += max(0.25f, 1.15f - 0.08f * difficulty)
        }
        fillGoldDots()

        val dirCamera = floatArrayOf(shipAimX * SHIP_AIM_LATERAL_GAIN, shipAimY * SHIP_AIM_LATERAL_GAIN, -1f)
        normalizeVec3InPlace(dirCamera)
        val shipPosWorld = rotateVectorByQuat(headQRel, floatArrayOf(shipOffsetX, shipOffsetY, -SHIP_DRAW_DISTANCE))
        val dirWorld = rotateVectorByQuat(headQRel, dirCamera)
        shipWorldPos[0] = shipPosWorld[0]
        shipWorldPos[1] = shipPosWorld[1]
        shipWorldPos[2] = shipPosWorld[2]
        shipWorldDir[0] = dirWorld[0]
        shipWorldDir[1] = dirWorld[1]
        shipWorldDir[2] = dirWorld[2]

        val iterator = rocks.iterator()
        while (iterator.hasNext()) {
            val rock = iterator.next()
            rock.x += rock.vx * dtSec
            rock.y += rock.vy * dtSec
            rock.z += rock.vz * dtSec

            val toCenter = sqrt(rock.x * rock.x + rock.y * rock.y + rock.z * rock.z)
            if (toCenter > ROCK_DESPAWN_DISTANCE) {
                iterator.remove()
                continue
            }

            val dxToShip = rock.x - shipPosWorld[0]
            val dyToShip = rock.y - shipPosWorld[1]
            val dzToShip = rock.z - shipPosWorld[2]
            val collisionDistance = if (rock.isBomb) {
                rock.radius * BOMB_COLLISION_RADIUS_MULTIPLIER +
                    SHIP_COLLISION_RADIUS * ROCK_SHIP_COLLISION_RADIUS_MULTIPLIER
            } else {
                rock.radius * GOLD_COLLISION_RADIUS_MULTIPLIER +
                    SHIP_COLLISION_RADIUS * GOLD_SHIP_COLLISION_RADIUS_MULTIPLIER
            }
            if (dxToShip * dxToShip + dyToShip * dyToShip + dzToShip * dzToShip <=
                collisionDistance * collisionDistance
            ) {
                if (rock.isBomb) {
                    alive = false
                    iterator.remove()
                    break
                } else {
                    score += BIG_GOLD_POINTS
                    gameEventListener?.onAffirmation(AFFIRMATIONS[random.nextInt(AFFIRMATIONS.size)])
                    iterator.remove()
                    hasBigGold = false
                }
            }
        }
        val dotIterator = goldDots.iterator()
        while (dotIterator.hasNext()) {
            val dot = dotIterator.next()
            val dxToShip = dot.x - shipPosWorld[0]
            val dyToShip = dot.y - shipPosWorld[1]
            val dzToShip = dot.z - shipPosWorld[2]
            val collectDistance =
                dot.radius * GOLD_DOT_COLLISION_RADIUS_MULTIPLIER +
                    SHIP_COLLISION_RADIUS * GOLD_DOT_SHIP_COLLISION_RADIUS_MULTIPLIER
            if (dxToShip * dxToShip + dyToShip * dyToShip + dzToShip * dzToShip <=
                collectDistance * collectDistance
            ) {
                score += SMALL_GOLD_POINTS
                dotIterator.remove()
            }
        }

        updateLasers(dtSec, headQRel)
    }

    private fun updateShipTurn(headQRel: FloatArray, dtSec: Float) {
        val forward = rotateVectorByQuat(headQRel, FORWARD_VECTOR)
        if (!prevForwardInitialized) {
            prevForward[0] = forward[0]
            prevForward[1] = forward[1]
            prevForward[2] = forward[2]
            prevForwardInitialized = true
            return
        }
        val dx = forward[0] - prevForward[0]
        val dy = forward[1] - prevForward[1]
        prevForward[0] = forward[0]
        prevForward[1] = forward[1]
        prevForward[2] = forward[2]

        var desiredX = -dx
        var desiredY = -dy
        val moveMag = sqrt(desiredX * desiredX + desiredY * desiredY)
        if (moveMag < SHIP_TURN_DEADZONE) {
            desiredX = 0f
            desiredY = 1f
        } else {
            desiredX /= moveMag
            desiredY /= moveMag
        }

        val follow = (dtSec * SHIP_TURN_FOLLOW_HZ).coerceIn(0f, 1f)
        shipAimX += (desiredX - shipAimX) * follow
        shipAimY += (desiredY - shipAimY) * follow

        shipOffsetX = 0f
        shipOffsetY = 0f

        val aimMag = sqrt(shipAimX * shipAimX + shipAimY * shipAimY)
        if (aimMag > 1e-4f) {
            shipAimX /= aimMag
            shipAimY /= aimMag
        } else {
            shipAimX = 0f
            shipAimY = 1f
        }
        shipHeadingDeg = (atan2(shipAimY, shipAimX) * RAD_TO_DEG)
    }

    private fun processPendingShots(headQRel: FloatArray) {
        if (pendingShots <= 0) {
            return
        }
        val shots = pendingShots
        pendingShots = 0
        repeat(shots) {
            val dirCamera = floatArrayOf(shipAimX * SHIP_AIM_LATERAL_GAIN, shipAimY * SHIP_AIM_LATERAL_GAIN, -1f)
            normalizeVec3InPlace(dirCamera)
            val forward = rotateVectorByQuat(headQRel, dirCamera)
            val shipPos = rotateVectorByQuat(headQRel, floatArrayOf(shipOffsetX, shipOffsetY, -SHIP_DRAW_DISTANCE))
            val muzzle = 0.38f
            lasers.add(
                Laser(
                    x = shipPos[0] + forward[0] * muzzle,
                    y = shipPos[1] + forward[1] * muzzle,
                    z = shipPos[2] + forward[2] * muzzle,
                    vx = forward[0] * LASER_SPEED,
                    vy = forward[1] * LASER_SPEED,
                    vz = forward[2] * LASER_SPEED
                )
            )
            if (lasers.size > MAX_LASERS) {
                lasers.removeAt(0)
            }
        }
    }

    private fun updateLasers(dtSec: Float, headQRel: FloatArray) {
        val laserIter = lasers.iterator()
        while (laserIter.hasNext()) {
            val laser = laserIter.next()
            laser.x += laser.vx * dtSec
            laser.y += laser.vy * dtSec
            laser.z += laser.vz * dtSec

            if (isLaserOffScreen(laser, headQRel)) {
                laserIter.remove()
                continue
            }

            var destroyed = false
            val rockIter = rocks.iterator()
            while (rockIter.hasNext()) {
                val rock = rockIter.next()
                if (!rock.isBomb) {
                    continue
                }
                val dx = laser.x - rock.x
                val dy = laser.y - rock.y
                val dz = laser.z - rock.z
                val hitDistance = rock.radius + LASER_RADIUS
                if (dx * dx + dy * dy + dz * dz <= hitDistance * hitDistance) {
                    rockIter.remove()
                    destroyed = true
                    gameEventListener?.onAffirmation("Direct hit!")
                    break
                }
            }
            if (destroyed) {
                laserIter.remove()
            }
        }
    }

    private fun isLaserOffScreen(laser: Laser, headQRel: FloatArray): Boolean {
        val cam = rotateVectorByQuat(conjugateQuat(headQRel), floatArrayOf(laser.x, laser.y, laser.z))
        val x = cam[0]
        val y = cam[1]
        val z = cam[2]
        if (z > -0.1f) {
            return true
        }
        val tanHalfFovY = tan(Math.toRadians((FOV_Y_DEG * 0.5f).toDouble())).toFloat()
        val tanHalfFovX = tanHalfFovY * aspectRatio
        val nx = x / (-z)
        val ny = y / (-z)
        return abs(nx) > tanHalfFovX * LASER_OFFSCREEN_MARGIN ||
            abs(ny) > tanHalfFovY * LASER_OFFSCREEN_MARGIN
    }

    private fun updateGoldArrow(headQRel: FloatArray) {
        val gold = rocks.firstOrNull { it.isBigGold } ?: run {
            showGoldArrow = false
            return
        }
        val cam = rotateVectorByQuat(conjugateQuat(headQRel), floatArrayOf(gold.x, gold.y, gold.z))
        val x = cam[0]
        val y = cam[1]
        val z = cam[2]
        val tanHalfFovY = tan(Math.toRadians((FOV_Y_DEG * 0.5f).toDouble())).toFloat()
        val tanHalfFovX = tanHalfFovY * aspectRatio
        val inFront = z < -0.2f
        val nx = if (-z > 1e-4f) x / (-z) else 0f
        val ny = if (-z > 1e-4f) y / (-z) else 0f
        val onScreen = inFront &&
            abs(nx) <= tanHalfFovX * ARROW_SCREEN_MARGIN &&
            abs(ny) <= tanHalfFovY * ARROW_SCREEN_MARGIN
        if (onScreen) {
            showGoldArrow = false
            return
        }

        var dirX = x
        var dirY = y
        if (!inFront) {
            dirX = -dirX
            dirY = -dirY
        }
        var norm = sqrt(dirX * dirX + dirY * dirY)
        if (norm < 1e-3f) {
            dirX = 0f
            dirY = 1f
            norm = 1f
        }
        dirX /= norm
        dirY /= norm

        val zPlane = -SHIP_DRAW_DISTANCE
        val halfPlaneY = -zPlane * tanHalfFovY
        val halfPlaneX = halfPlaneY * aspectRatio
        arrowPosX = dirX * halfPlaneX * ARROW_EDGE_FACTOR
        arrowPosY = dirY * halfPlaneY * ARROW_EDGE_FACTOR
        arrowAngleDeg = (atan2(dirY, dirX) * RAD_TO_DEG)
        showGoldArrow = true
    }

    private fun renderStars() {
        for (star in stars) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, star.x, star.y, star.z)
            Matrix.scaleM(model, 0, star.size, star.size, star.size)
            drawSprite(view, ICON_STAR)
        }
    }

    private fun renderRocks() {
        for (rock in rocks) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, rock.x, rock.y, rock.z)
            val scale = when {
                rock.isBomb -> rock.radius * BOMB_RENDER_SCALE
                rock.isBigGold -> rock.radius * BIG_GOLD_RENDER_SCALE
                else -> rock.radius * GOLD_RENDER_SCALE
            }
            Matrix.scaleM(model, 0, scale, scale, scale)
            if (rock.isBomb) {
                drawSprite(view, ICON_BOMB)
            } else if (rock.isBigGold) {
                drawSprite(view, ICON_BIG_GOLD)
            } else {
                drawSprite(view, ICON_GOLD)
            }
        }
    }

    private fun renderGoldDots() {
        for (dot in goldDots) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, dot.x, dot.y, dot.z)
            val scale = dot.radius * GOLD_DOT_RENDER_SCALE
            Matrix.scaleM(model, 0, scale, scale, scale)
            drawSprite(view, ICON_DOT)
        }
    }

    private fun isOnShipPath(x: Float, y: Float, z: Float, radius: Float): Boolean {
        val along = projectionOnRay(
            pointX = x,
            pointY = y,
            pointZ = z,
            rayX = shipWorldPos[0],
            rayY = shipWorldPos[1],
            rayZ = shipWorldPos[2],
            dirX = shipWorldDir[0],
            dirY = shipWorldDir[1],
            dirZ = shipWorldDir[2]
        )
        if (along <= 0f) {
            return false
        }
        val dist = distanceToRay(
            pointX = x,
            pointY = y,
            pointZ = z,
            rayX = shipWorldPos[0],
            rayY = shipWorldPos[1],
            rayZ = shipWorldPos[2],
            dirX = shipWorldDir[0],
            dirY = shipWorldDir[1],
            dirZ = shipWorldDir[2]
        )
        return dist <= PATH_SHOW_RADIUS + radius
    }

    private fun renderShip() {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, shipOffsetX, shipOffsetY, -SHIP_DRAW_DISTANCE)
        Matrix.rotateM(model, 0, shipHeadingDeg, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, SHIP_RENDER_SCALE, SHIP_RENDER_SCALE, SHIP_RENDER_SCALE)
        drawSprite(null, ICON_SHIP)
    }

    private fun renderGoldArrow() {
        if (!showGoldArrow) {
            return
        }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, arrowPosX, arrowPosY, -SHIP_DRAW_DISTANCE)
        Matrix.rotateM(model, 0, arrowAngleDeg, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, 0.28f, 0.28f, 0.28f)
        drawSprite(null, ICON_ARROW)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun renderLasers() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        for (laser in lasers) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, laser.x, laser.y, laser.z)
            val dirLen = sqrt(laser.vx * laser.vx + laser.vy * laser.vy + laser.vz * laser.vz)
            if (dirLen > 1e-4f) {
                val nx = laser.vx / dirLen
                val ny = laser.vy / dirLen
                val nz = laser.vz / dirLen
                val yaw = (atan2(nx, -nz) * RAD_TO_DEG)
                val pitch = (atan2(ny, sqrt(nx * nx + nz * nz)) * RAD_TO_DEG)
                Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
                Matrix.rotateM(model, 0, -pitch, 1f, 0f, 0f)
            }
            Matrix.scaleM(model, 0, 0.18f, 0.18f, 0.18f)
            drawSprite(view, ICON_LASER)
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawSprite(viewMatrix: FloatArray?, iconIndex: Int) {
        if (viewMatrix != null) {
            Matrix.multiplyMM(viewModel, 0, viewMatrix, 0, model, 0)
        } else {
            System.arraycopy(model, 0, viewModel, 0, 16)
        }
        Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)

        GLES20.glUseProgram(spriteProgram)
        GLES20.glUniformMatrix4fv(spriteMvpHandle, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, iconTextures[iconIndex])
        GLES20.glUniform1i(spriteTextureHandle, 0)

        GLES20.glEnableVertexAttribArray(spritePosHandle)
        GLES20.glVertexAttribPointer(spritePosHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, spriteVertexBuffer)
        GLES20.glEnableVertexAttribArray(spriteUvHandle)
        GLES20.glVertexAttribPointer(spriteUvHandle, 2, GLES20.GL_FLOAT, false, 2 * 4, spriteTexBuffer)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, spriteIndices.size, GLES20.GL_UNSIGNED_SHORT, spriteIndexBuffer)
        GLES20.glDisableVertexAttribArray(spritePosHandle)
        GLES20.glDisableVertexAttribArray(spriteUvHandle)
    }

    private fun createRock(difficulty: Float, forceGold: Boolean): Rock {
        val speed = randomRange(0.05f, 0.16f + 0.02f * difficulty)
        val bombChance = (0.35f + 0.03f * difficulty).coerceAtMost(0.72f)
        val isBomb = if (forceGold) false else random.nextFloat() < bombChance
        val radius = if (isBomb) {
            randomRange(0.32f, 0.62f)
        } else {
            randomRange(0.22f, 0.42f)
        }

        val spawn = randomPointOnCollectiblePlane(OBJECT_MIN_SPAWN_DISTANCE, OBJECT_MAX_SPAWN_DISTANCE)

        val velocity = run {
            val heading = randomRange(0f, (Math.PI * 2.0).toFloat())
            floatArrayOf(cos(heading) * speed, 0f, sin(heading) * speed)
        }

        return Rock(
            x = spawn[0],
            y = spawn[1],
            z = spawn[2],
            vx = velocity[0],
            vy = velocity[1],
            vz = velocity[2],
            radius = radius,
            isBomb = isBomb,
            isBigGold = forceGold
        )
    }

    private fun fillGoldDots() {
        while (goldDots.size < MAX_GOLD_DOTS) {
            val spawn = randomPointOnCollectiblePlane(DOT_MIN_SPAWN_DISTANCE, DOT_MAX_SPAWN_DISTANCE)
            goldDots.add(
                GoldDot(
                    x = spawn[0],
                    y = spawn[1],
                    z = spawn[2],
                    radius = randomRange(DOT_MIN_RADIUS, DOT_MAX_RADIUS)
                )
            )
        }
    }

    private fun randomPointOnCollectiblePlane(minDistance: Float, maxDistance: Float): FloatArray {
        val distance = randomRange(minDistance, maxDistance)
        val heading = randomRange(0f, (Math.PI * 2.0).toFloat())
        return floatArrayOf(
            cos(heading) * distance,
            COLLECTIBLE_PLANE_Y,
            sin(heading) * distance
        )
    }

    private fun randomUnitVector(): FloatArray {
        var x: Float
        var y: Float
        var z: Float
        var norm: Float
        do {
            x = randomRange(-1f, 1f)
            y = randomRange(-1f, 1f)
            z = randomRange(-1f, 1f)
            norm = sqrt(x * x + y * y + z * z)
        } while (norm < 1e-3f)
        return floatArrayOf(x / norm, y / norm, z / norm)
    }

    private fun randomRange(min: Float, max: Float): Float {
        return min + (max - min) * random.nextFloat()
    }

    private fun rotateVectorByQuat(q: FloatArray, v: FloatArray): FloatArray {
        val qv = floatArrayOf(0f, v[0], v[1], v[2])
        val rotated = multiplyQuat(multiplyQuat(q, qv), conjugateQuat(q))
        return floatArrayOf(rotated[1], rotated[2], rotated[3])
    }

    private fun normalizeVec3InPlace(v: FloatArray) {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (n < 1e-6f) {
            v[0] = 0f
            v[1] = 1f
            v[2] = -1f
            return
        }
        v[0] /= n
        v[1] /= n
        v[2] /= n
    }

    private fun moveToward(current: Float, target: Float, maxDelta: Float): Float {
        val delta = target - current
        return when {
            delta > maxDelta -> current + maxDelta
            delta < -maxDelta -> current - maxDelta
            else -> target
        }
    }

    private fun projectionOnRay(
        pointX: Float,
        pointY: Float,
        pointZ: Float,
        rayX: Float,
        rayY: Float,
        rayZ: Float,
        dirX: Float,
        dirY: Float,
        dirZ: Float
    ): Float {
        val vx = pointX - rayX
        val vy = pointY - rayY
        val vz = pointZ - rayZ
        return vx * dirX + vy * dirY + vz * dirZ
    }

    private fun distanceToRay(
        pointX: Float,
        pointY: Float,
        pointZ: Float,
        rayX: Float,
        rayY: Float,
        rayZ: Float,
        dirX: Float,
        dirY: Float,
        dirZ: Float
    ): Float {
        val along = projectionOnRay(pointX, pointY, pointZ, rayX, rayY, rayZ, dirX, dirY, dirZ)
        val cx = rayX + dirX * along
        val cy = rayY + dirY * along
        val cz = rayZ + dirZ * along
        val dx = pointX - cx
        val dy = pointY - cy
        val dz = pointZ - cz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun resetGame() {
        rocks.clear()
        goldDots.clear()
        lasers.clear()
        score = 0
        elapsedSec = 0f
        spawnTimerSec = 0.35f
        goldMissingSec = 0f
        alive = true
        gameOverSent = false
        showGoldArrow = false
        shipHeadingDeg = 0f
        shipAimX = 0f
        shipAimY = 1f
        shipOffsetX = 0f
        shipOffsetY = 0f
        prevForwardInitialized = false
        pendingShots = 0
    }

    private fun conjugateQuat(q: FloatArray): FloatArray {
        return floatArrayOf(q[0], -q[1], -q[2], -q[3])
    }

    private fun multiplyQuat(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
            a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
            a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
            a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
        )
    }

    private fun normalizeInto(src: FloatArray, dst: FloatArray) {
        val n = sqrt(
            src[0] * src[0] +
                src[1] * src[1] +
                src[2] * src[2] +
                src[3] * src[3]
        )
        if (n < 1e-6f) {
            dst[0] = 1f
            dst[1] = 0f
            dst[2] = 0f
            dst[3] = 0f
            return
        }
        dst[0] = src[0] / n
        dst[1] = src[1] / n
        dst[2] = src[2] / n
        dst[3] = src[3] / n
    }

    private fun smoothHeadOrientation(targetQRel: FloatArray, dtSec: Float): FloatArray {
        if (!renderedHeadQRelInitialized || snapViewOrientation) {
            normalizeInto(targetQRel, renderedHeadQRel)
            renderedHeadQRelInitialized = true
            snapViewOrientation = false
            return renderedHeadQRel
        }

        val target = floatArrayOf(targetQRel[0], targetQRel[1], targetQRel[2], targetQRel[3])
        var dot = renderedHeadQRel[0] * target[0] +
            renderedHeadQRel[1] * target[1] +
            renderedHeadQRel[2] * target[2] +
            renderedHeadQRel[3] * target[3]
        if (dot < 0f) {
            target[0] = -target[0]
            target[1] = -target[1]
            target[2] = -target[2]
            target[3] = -target[3]
            dot = -dot
        }

        val clampedDot = dot.coerceIn(-1f, 1f)
        val angle = 2f * acos(clampedDot)
        val maxStep = MAX_VIEW_ROTATION_SPEED_RAD_PER_SEC * dtSec
        if (angle <= maxStep || angle < 1e-5f) {
            normalizeInto(target, renderedHeadQRel)
            return renderedHeadQRel
        }

        val t = (maxStep / angle).coerceIn(0f, 1f)
        val invT = 1f - t
        val blended = floatArrayOf(
            renderedHeadQRel[0] * invT + target[0] * t,
            renderedHeadQRel[1] * invT + target[1] * t,
            renderedHeadQRel[2] * invT + target[2] * t,
            renderedHeadQRel[3] * invT + target[3] * t
        )
        normalizeInto(blended, renderedHeadQRel)
        return renderedHeadQRel
    }

    private companion object {
        const val ICON_SHIP = 0
        const val ICON_BIG_GOLD = 1
        const val ICON_GOLD = 2
        const val ICON_DOT = 3
        const val ICON_BOMB = 4
        const val ICON_ARROW = 5
        const val ICON_LASER = 6
        const val ICON_STAR = 7
        const val ICON_COUNT = 8
        const val ICON_TEX_SIZE = 128
        val FORWARD_VECTOR = floatArrayOf(0f, 0f, -1f)
        const val GAZE_DISTANCE = 6f
        const val SHIP_COLLISION_RADIUS = 0.62f
        const val SHIP_DRAW_DISTANCE = 1.5f
        const val FOV_Y_DEG = 60f
        const val RAD_TO_DEG = (180f / Math.PI.toFloat())
        const val MAX_VIEW_ROTATION_SPEED_RAD_PER_SEC = 0.95f
        const val OBJECT_MIN_SPAWN_DISTANCE = 4.4f
        const val OBJECT_MAX_SPAWN_DISTANCE = 6.6f
        const val ROCK_DESPAWN_DISTANCE = 7.4f
        const val STAR_COUNT = 180
        const val STAR_MIN_DISTANCE = 25f
        const val STAR_MAX_DISTANCE = 50f
        const val LEVEL_DURATION_SEC = 18f
        const val GOLD_FORCE_RESPAWN_SEC = 1.35f
        const val GOLD_SPAWN_CHANCE_WHEN_MISSING = 0.35f
        const val ARROW_EDGE_FACTOR = 0.76f
        const val ARROW_SCREEN_MARGIN = 0.88f
        const val LASER_SPEED = 11.5f
        const val LASER_RADIUS = 0.2f
        const val LASER_OFFSCREEN_MARGIN = 1.15f
        const val MAX_LASERS = 16
        const val MAX_PENDING_SHOTS = 3
        const val SHIP_TURN_FOLLOW_HZ = 14f
        const val SHIP_TURN_DEADZONE = 0.0028f
        const val SHIP_AIM_LATERAL_GAIN = 0.85f
        const val SHIP_MAX_OFFSET = 0.42f
        const val SHIP_GAZE_OFFSET_GAIN = 0.95f
        const val SHIP_POSITION_FOLLOW_HZ = 2.4f
        const val SHIP_MAX_OFFSET_SPEED = 0.35f
        const val BOMB_RENDER_SCALE = 2.6f
        const val BIG_GOLD_RENDER_SCALE = 2.2f
        const val GOLD_RENDER_SCALE = 2.0f
        const val GOLD_DOT_RENDER_SCALE = 1.75f
        const val BOMB_COLLISION_RADIUS_MULTIPLIER = 0.72f
        const val ROCK_SHIP_COLLISION_RADIUS_MULTIPLIER = 0.24f
        const val GOLD_COLLISION_RADIUS_MULTIPLIER = 0.72f
        const val GOLD_SHIP_COLLISION_RADIUS_MULTIPLIER = 0.2f
        const val GOLD_DOT_COLLISION_RADIUS_MULTIPLIER = 0.75f
        const val GOLD_DOT_SHIP_COLLISION_RADIUS_MULTIPLIER = 0.16f
        const val SHIP_RENDER_SCALE = 0.21f
        const val MAX_GOLD_DOTS = 34
        const val DOT_MIN_SPAWN_DISTANCE = 4.6f
        const val DOT_MAX_SPAWN_DISTANCE = 6.8f
        const val DOT_MIN_RADIUS = 0.05f
        const val DOT_MAX_RADIUS = 0.09f
        const val COLLECTIBLE_PLANE_Y = 0f
        const val SMALL_GOLD_POINTS = 1
        const val BIG_GOLD_POINTS = 25
        const val PATH_SHOW_RADIUS = 1.25f
        val AFFIRMATIONS = arrayOf(
            "You did it!",
            "Keep going!",
            "Nice work!",
            "Great focus!",
            "On a roll!",
            "Awesome!",
            "You got this!",
            "Strong run!"
        )

        const val VERTEX_SHADER = """
            attribute vec3 aPosition;
            uniform mat4 uMvpMatrix;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """

        const val SPRITE_VERTEX_SHADER = """
            attribute vec3 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMvpMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        const val SPRITE_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
