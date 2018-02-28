package me.glatteis.unichat.chat

import com.google.gson.JsonObject
import me.glatteis.unichat.data.Room
import me.glatteis.unichat.error
import me.glatteis.unichat.gson
import me.glatteis.unichat.jsonMap
import me.glatteis.unichat.now
import org.eclipse.jetty.util.ConcurrentHashSet
import java.awt.Image
import java.awt.image.BufferedImage
import java.awt.image.RenderedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import javax.imageio.ImageIO
import kotlin.concurrent.schedule
import kotlin.math.sqrt


/**
 * Created by Linus on 21.12.2017!
 */
class ChatRoom(val id: String, val room: Room) {

    // Users that have logged out in the past 5 seconds
    private val bufferUsers = ConcurrentHashSet<User>()
    val onlineUsers = ConcurrentHashSet<User>()

    // Removes online users that have closed the connection
    fun removeClosed() {
        onlineUsers.retainAll {
            it.isOpen()
        }
    }

    /**
     * Called by the ChatSocket when someone logs out
     */

    fun onLogout(user: User) {
        // Add this user to buffer users. If the users logs back in within 5 seconds,
        // We will not treat them as logged out.
        bufferUsers.add(user)
        Timer().schedule(5000) {
            removeClosed()
            bufferUsers.remove(user)
            if (onlineUsers.any { it.publicId == user.publicId }) {
                return@schedule
            }
            sendToAll(gson.jsonMap(
                    "type" to "info-logout",
                    "username" to user.username
            ))
        }
    }

    // Send a message to all users in this room
    fun sendToAll(message: String) {
        removeClosed()
        for (u in onlineUsers) {
            u.webSocket.remote.sendString(message)
        }
    }

    /**
     * Called by ChatSocket when a user logs in, before they are added to onlineUsers
     */
    fun onLogin(user: User) {
        if (bufferUsers.any { it.publicId == user.publicId }) {
            return // This user is logging back in after an unexpected web socket close. Do not send alert
        }
        sendToAll(gson.jsonMap(
                "type" to "info-login",
                "username" to user.username,
                "user-id" to user.publicId
        ))
    }

    // Gets called by the ChatSocket when a WebSocket messages comes in
    fun onMessage(message: JsonObject, user: User) {
        when (message.get("type").asString) {
            "message" -> {
                if (!message.has("message")) {
                    user.webSocket.error("Message has no attribute 'messsage'")
                    return
                }
                sendToAll(gson.jsonMap(
                        "type" to "message",
                        "username" to user.username,
                        "user-id" to user.publicId,
                        "message" to message.get("message"),
                        "time" to now().second.millisOfDay
                ))
            }
            "image" -> {
                if (!message.has("image")) {
                    user.webSocket.error("Message has no attribute 'image'")
                    return
                }
                val image = message.get("image").asString
                val splitImage = image.split(",")
                if (splitImage.size == 1) {
                    user.webSocket.error("The provided image is not a valid base64 image (missing comma)")
                    return
                }
                val bufferedImage: BufferedImage
                try {
                    bufferedImage = base64StringToImg(splitImage[1])
                } catch (e: Exception) {
                    e.printStackTrace()
                    user.webSocket.error("The provided image is not a valid base64 image")
                    return
                }
                // If the image is bigger than N pixels, scale it down to N pixels
                val maxSize = 1_000_000
                val imageToSend = if (bufferedImage.width * bufferedImage.height > maxSize) {
                    val scaleFactor = sqrt(maxSize / (bufferedImage.width * bufferedImage.height).toDouble())
                    bufferedImage.getScaledInstance(
                            (bufferedImage.width * scaleFactor).toInt(),
                            (bufferedImage.height * scaleFactor).toInt(),
                            Image.SCALE_DEFAULT
                    )
                    val newImage = BufferedImage(bufferedImage.width, bufferedImage.height, BufferedImage.TYPE_INT_ARGB)
                    val g = newImage.graphics
                    g.drawImage(bufferedImage, 0, 0, null)
                    g.dispose()
                    newImage
                } else {
                    bufferedImage
                }

                val base64String: String
                try {
                    base64String = splitImage[0] + "," + imgToBase64String(imageToSend, "png")
                } catch (e: Exception) {
                    e.printStackTrace()
                    user.webSocket.error("The provided image is not a valid base64 image")
                    return
                }

                sendToAll(gson.jsonMap(
                        "type" to "image",
                        "username" to user.username,
                        "user-id" to user.publicId,
                        "image" to base64String,
                        "time" to now().second.millisOfDay
                ))
            }
        }
    }

    private fun imgToBase64String(img: RenderedImage, formatName: String): String {
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(img, formatName, Base64.getEncoder().wrap(outputStream))
        return outputStream.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun base64StringToImg(base64String: String): BufferedImage {
        return ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(base64String)))
    }
}