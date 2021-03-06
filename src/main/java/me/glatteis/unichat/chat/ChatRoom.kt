package me.glatteis.unichat.chat

import com.google.gson.JsonObject
import me.glatteis.unichat.*
import me.glatteis.unichat.data.Room
import org.eclipse.jetty.util.ConcurrentHashSet
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.schedule


/**
 * Created by Linus on 21.12.2017!
 */
class ChatRoom(val id: String, val room: Room) {

    // Users that have logged out in the past 5 seconds
    private val bufferUsers = ConcurrentHashSet<User>()
    // Users that are online right now
    val onlineUsers = ConcurrentHashSet<User>()

    // Private IDs of users
    val privateIds = ConcurrentHashMap<User, String>()

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

    /**
     * Gets called by the ChatSocket when a WebSocket messages comes in
     */
    fun onMessage(message: JsonObject, user: User) {
        when (message.get("type").asString) {
            "message" -> {
                if (!message.has("message")) {
                    user.webSocket.error("Message has no attribute 'messsage'", ErrorCode.MESSAGE_EMPTY)
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
                    user.webSocket.error("Message has no attribute 'image'", ErrorCode.IMAGE_EMPTY)
                    return
                }
                val imageCode = message.get("image").asString
                FILE_DIRECTORY.listFiles { file ->
                    file.name == imageCode
                }.firstOrNull() ?: user.webSocket.error("This image does not exist", ErrorCode.NONEXISTENT_IMAGE)
                sendToAll(gson.jsonMap(
                        "type" to "image",
                        "username" to user.username,
                        "user-id" to user.publicId,
                        "image" to imageCode,
                        "time" to now().second.millisOfDay
                ))
            }
        }
    }

    /**
     * Return online users as json, for unichat.kt
     */
    fun onlineUsersAsJson(): List<JsonObject> {
        return onlineUsers.map {
            JsonObject().apply {
                addProperty("username", it.username)
                addProperty("user-id", it.publicId)
            }
        }
    }
}