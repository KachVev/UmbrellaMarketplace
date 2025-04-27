package dev.kachvev.serverscript.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.*
import com.github.kotlintelegrambot.dispatcher.handlers.TextHandlerEnvironment
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.logging.LogLevel
import com.mongodb.MongoTimeoutException
import dev.kachvev.serverscript.ext.downloadFileContent
import dev.kachvev.serverscript.ext.reply
import dev.kachvev.serverscript.ext.toProfileText
import dev.kachvev.serverscript.model.LuaScript
import dev.kachvev.serverscript.repository.LuaScriptRepository
import dev.kachvev.serverscript.service.AuthService
import dev.kachvev.serverscript.service.TokenService
import dev.kachvev.serverscript.telegram.enum.Buttons
import dev.kachvev.serverscript.telegram.menu.startMenu
import dev.kachvev.serverscript.wrapper.TelegramAwaiter
import jakarta.annotation.PostConstruct
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component

@Component
class TelegramBot(
    val luaRepository: LuaScriptRepository,
    val authService: AuthService,
    val tokenService: TokenService,
    val mongoTemplate: MongoTemplate
) {
    val awaiter = TelegramAwaiter()
    val pendingScripts = mutableMapOf<String, LuaScript>()
    val reviewChannelId = ChatId.fromId(-4708596381)

    val bot = bot {
        token = "7738587832:AAHe-DK6ptfFxI5gn46f-5s7SOCA57mIuVo"
        logLevel = LogLevel.Error

        dispatch {
            command("start") {
                val isUserLinked = authService.findUserByTelegramId(message.chat.id.toString()) != null
                startMenu(isUserLinked)
            }
            command("getchatid") {
                val chatId = message.chat.id
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = "🆔 ID этого чата: `$chatId`",
                    parseMode = com.github.kotlintelegrambot.entities.ParseMode.MARKDOWN
                )
            }
            command("admin") {
                val chatId = message.chat.id

                if (!isAdmin(chatId)) {
                    bot.sendMessage(ChatId.fromId(chatId), "❌ У вас нет доступа к админ-меню.")
                    return@command
                }

                val markup = InlineKeyboardMarkup.create(
                    listOf(
                        listOf(
                            InlineKeyboardButton.CallbackData("📊 Статистика входов", Buttons.AdminMenu.STATS),
                        ),
                        listOf(
                            InlineKeyboardButton.CallbackData("🗑 Удалить скрипт", Buttons.AdminMenu.DELETE_SCRIPT),
                        )
                    )
                )

                bot.sendMessage(
                    ChatId.fromId(chatId),
                    text = "🛠 *Админ-меню*",
                    parseMode = com.github.kotlintelegrambot.entities.ParseMode.MARKDOWN,
                    replyMarkup = markup
                )
            }
            callbackQuery(Buttons.StartMenu.LINK_ACCOUNT) {
                val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
                bot.sendMessage(ChatId.fromId(chatId), "📥 Пожалуйста, отправьте токен для привязки.")
                awaiter.awaitText(chatId, ::handleToken)
            }
            callbackQuery(Buttons.StartMenu.PROFILE) {
                val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
                val user = authService.findUserByTelegramId(chatId.toString())

                val messageText = user?.toProfileText() ?: "❌ Ваш аккаунт не привязан."
                val replyMarkup = user?.let {
                    InlineKeyboardMarkup.create(
                        listOf(
                            listOf(
                                InlineKeyboardButton.CallbackData(
                                    "📤 Отвязать Telegram",
                                    Buttons.StartMenu.UNLINK_ACCOUNT
                                )
                            )
                        )
                    )
                }

                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = messageText,
                    parseMode = com.github.kotlintelegrambot.entities.ParseMode.MARKDOWN,
                    replyMarkup = replyMarkup
                )
            }
            callbackQuery(Buttons.StartMenu.UNLINK_ACCOUNT) {
                val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
                val userId = authService.findUserByTelegramId(chatId.toString())?.uniqueId ?: return@callbackQuery
                tokenService.unlinkTelegram(userId)
                bot.sendMessage(ChatId.fromId(chatId), "❌ Ваш аккаунт успешно отвязан!")
            }
            callbackQuery(Buttons.StartMenu.MARKETPLACE) {
                val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery

                if (authService.findUserByTelegramId(chatId.toString()) == null) {
                    bot.answerCallbackQuery(callbackQuery.id, text = "❌ Ваш аккаунт не привязан.", showAlert = true)
                    return@callbackQuery
                }

                sendMarketplacePage(chatId, 0)
            }
            callbackQuery(Buttons.Marketplace.UPLOAD) {
                val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
                if (authService.findUserByTelegramId(chatId.toString()) == null) {
                    bot.sendMessage(ChatId.fromId(chatId), "❌ Ваш аккаунт не привязан.")
                    return@callbackQuery
                }

                bot.sendMessage(ChatId.fromId(chatId), "📥 Пожалуйста, отправьте Lua-скрипт для загрузки.")

                awaiter.awaitDocument(chatId) { env ->
                    val media = env.media
                    val message = env.message

                    val fileContent = downloadFileContent(media.fileId) ?: return@awaitDocument
                    val telegramId = message.chat.id.toString()
                    val user = authService.findUserByTelegramId(telegramId)

                    val script = LuaScript(
                        name = media.fileName ?: "unknown.lua",
                        content = fileContent,
                        author = user?.nickname ?: "unknown"
                    )

                    bot.sendMessage(
                        ChatId.fromId(message.chat.id),
                        "✅ Скрипт `${media.fileName}` успешно отправлен на проверку администраторам!"
                    )

                    bot.sendDocument(
                        chatId = reviewChannelId,
                        document = TelegramFile.ByFileId(media.fileId),
                        caption = "📄 Новый скрипт от ${script.author}: `${script.name}`",
                        replyMarkup = InlineKeyboardMarkup.create(
                            listOf(
                                listOf(
                                    InlineKeyboardButton.CallbackData("✅ Принять", "approve:${script.name}"),
                                    InlineKeyboardButton.CallbackData("❌ Отклонить", "reject:${script.name}")
                                )
                            )
                        )
                    )

                    pendingScripts[script.name] = script
                }
            }
            callbackQuery(Buttons.Marketplace.PAGE) {
                val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
                val messageId = callbackQuery.message?.messageId ?: return@callbackQuery

                val parts = callbackQuery.data.split(":")
                val pageIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0

                if (authService.findUserByTelegramId(chatId.toString()) == null) {
                    bot.answerCallbackQuery(callbackQuery.id, text = "❌ Ваш аккаунт не привязан.", showAlert = true)
                    return@callbackQuery
                }

                sendMarketplacePage(chatId, pageIndex, messageId)
            }
            callbackQuery(Buttons.Marketplace.ADD_SCRIPT) {
                val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
                val messageId = callbackQuery.message?.messageId ?: return@callbackQuery

                val parts = callbackQuery.data.split(":")
                val scriptName = parts.getOrNull(1) ?: return@callbackQuery

                val user = authService.findUserByTelegramId(chatId.toString()) ?: return@callbackQuery

                if (user.accessibleScripts.contains(scriptName)) {
                    user.accessibleScripts.remove(scriptName)
                    bot.answerCallbackQuery(callbackQuery.id, text = "❌ Скрипт удалён из списка.", showAlert = false)
                } else {
                    user.accessibleScripts.add(scriptName)
                    bot.answerCallbackQuery(callbackQuery.id, text = "✅ Скрипт добавлен в список.", showAlert = false)
                }

                authService.updateUser(user)

                val allScripts = luaRepository.findAll()
                val pageSize = 5

                val index = allScripts.indexOfFirst { it.name == scriptName }
                val currentPage = if (index != -1) index / pageSize else 0

                val newScriptsCount = allScripts.size
                val newTotalPages = (newScriptsCount + pageSize - 1) / pageSize
                val safePage = if (currentPage >= newTotalPages) (newTotalPages - 1).coerceAtLeast(0) else currentPage

                sendMarketplacePage(chatId, safePage, messageId)
            }
            callbackQuery(Buttons.AdminMenu.STATS) {
                val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery

                if (!isAdmin(chatId)) {
                    bot.answerCallbackQuery(callbackQuery.id, text = "❌ Нет доступа.", showAlert = true)
                    return@callbackQuery
                }

                val users = authService.findUsersLoggedInToday()
                if (users.isEmpty()) {
                    bot.sendMessage(ChatId.fromId(chatId), "📭 Сегодня ещё никто не заходил.")
                    return@callbackQuery
                }

                val text = buildString {
                    append("📊 *Статистика за сегодня:*\n\n")
                    users.forEach { user ->
                        val minutesAgo = java.time.Duration.between(user.lastLogin, dev.kachvev.serverscript.ext.nowMoscow()).toMinutes()
                        val niceMinutesAgo = if (minutesAgo < 1) "Только что" else "$minutesAgo минут назад"
                        val usernameLink = user.telegramId?.let { "[${user.nickname}](tg://user?id=$it)" } ?: user.nickname
                        append("• $usernameLink — $niceMinutesAgo\n")
                    }
                }

                bot.sendMessage(
                    ChatId.fromId(chatId),
                    text = text,
                    parseMode = com.github.kotlintelegrambot.entities.ParseMode.MARKDOWN
                )
            }
            callbackQuery(Buttons.AdminMenu.DELETE_SCRIPT) {
                val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery

                if (!isAdmin(chatId)) {
                    bot.answerCallbackQuery(callbackQuery.id, text = "❌ Нет доступа.", showAlert = true)
                    return@callbackQuery
                }

                bot.sendMessage(ChatId.fromId(chatId), "✏️ Отправьте название скрипта, который хотите удалить.")

                awaiter.awaitText(chatId) { env ->
                    val scriptName = env.message.text?.trim()

                    if (scriptName.isNullOrBlank()) {
                        env.reply("❌ Пустое название.")
                        return@awaitText
                    }

                    val script = luaRepository.findByName(scriptName)
                    if (script == null) {
                        env.reply("❌ Скрипт `$scriptName` не найден.")
                        return@awaitText
                    }

                    luaRepository.delete(script)
                    env.reply("✅ Скрипт `$scriptName` успешно удалён.")
                }
            }


            callbackQuery {
                val data = callbackQuery.data
                if (data.startsWith("approve:") || data.startsWith("reject:")) {
                    val chatId = callbackQuery.message?.chat?.id ?: return@callbackQuery
                    val parts = data.split(":")
                    val action = parts[0]
                    val scriptName = parts.getOrNull(1) ?: return@callbackQuery

                    val script = pendingScripts[scriptName]
                    if (script == null) {
                        bot.answerCallbackQuery(callbackQuery.id, text = "❌ Скрипт не найден в ожидании.", showAlert = true)
                        return@callbackQuery
                    }

                    when (action) {
                        "approve" -> {
                            luaRepository.save(script)
                            bot.sendMessage(
                                chatId = ChatId.fromId(chatId),
                                text = "✅ Скрипт `${script.name}` принят и добавлен в ваш список доступных скриптов."
                            )
                        }
                        "reject" -> {
                            bot.sendMessage(
                                chatId = ChatId.fromId(chatId),
                                text = "❌ Скрипт `${script.name}` отклонён. Вы можете загрузить новый."
                            )

                        }
                    }

                    pendingScripts.remove(scriptName)

                    bot.answerCallbackQuery(callbackQuery.id, text = "Готово!")
                }
            }

            text {
                if (awaiter.handleIfAwaitingText(this)) return@text
            }
            document {
                if (awaiter.handleIfAwaitingDocument(this)) return@document
            }
            photos {
                if (awaiter.handleIfAwaitingPhoto(this)) return@photos
            }
            video {
                if (awaiter.handleIfAwaitingVideo(this)) return@video
            }
            audio {
                if (awaiter.handleIfAwaitingAudio(this)) return@audio
            }
            sticker {
                if (awaiter.handleIfAwaitingSticker(this)) return@sticker
            }
            voice {
                if (awaiter.handleIfAwaitingVoice(this)) return@voice
            }
        }
    }

    @PostConstruct
    fun start() {
        Thread {
            while (true) {
                try {
                    mongoTemplate.executeCommand("{ ping: 1 }")
                    println("✅ MongoDB подключение установлено. Запускаем Telegram-бота.")
                    bot.startPolling()
                    break
                } catch (ex: MongoTimeoutException) {
                    println("❌ [MongoTimeoutException] Ошибка подключения к MongoDB: ${ex.localizedMessage}")
                } catch (ex: Exception) {
                    println("❌ [Exception] Непредвиденная ошибка при проверке MongoDB: ${ex.localizedMessage}")
                }

                println("⏳ Повторная попытка через 5 секунд...")
                Thread.sleep(5000)
            }
        }.start()
    }

    fun isAdmin(chatId: Long): Boolean {
        return chatId in listOf(7582738324L, 873934194L)
    }

    fun handleToken(env: TextHandlerEnvironment) {
        val token = env.message.text?.trim()
        val chatId = env.message.chat.id.toString()

        when {
            token.isNullOrBlank() -> env.reply("❌ Пожалуйста, отправьте корректный токен.")
            authService.findUserByTelegramId(chatId) != null -> env.reply("❌ Ваш Telegram уже привязан к аккаунту.")
            authService.linkTelegramByToken(token, env.message.chat.id) -> env.reply("✅ Telegram успешно привязан к вашему аккаунту!")
            else -> env.reply("❌ Неверный или просроченный токен.")
        }
    }

    fun sendMarketplacePage(chatId: Long, pageIndex: Int, messageId: Long? = null) {
        val user = authService.findUserByTelegramId(chatId.toString()) ?: return
        val scripts = luaRepository.findAll()
        val pageSize = 5
        val totalPages = (scripts.size + pageSize - 1) / pageSize

        val pageScripts = scripts.drop(pageIndex * pageSize).take(pageSize)

        val text = buildString {
            if (pageScripts.isEmpty()) {
                append("❌ На этой странице нет скриптов.")
            } else {
                append("🛒 Страница ${pageIndex + 1} из $totalPages\n")
                append("Доступные скрипты:")
            }
        }

        val buttons = mutableListOf<List<InlineKeyboardButton>>()

        pageScripts.forEach { script ->
            val isSelected = script.id != null && user.accessibleScripts.contains(script.name)
            val buttonText = (if (isSelected) "✅ " else "") + script.name

            buttons.add(
                listOf(
                    InlineKeyboardButton.CallbackData(
                        text = buttonText,
                        callbackData = "${Buttons.Marketplace.ADD_SCRIPT}:${script.name}"
                    )
                )
            )
        }

        val navButtons = mutableListOf<InlineKeyboardButton>()
        if (pageIndex > 0) {
            navButtons.add(InlineKeyboardButton.CallbackData("⬅️ Назад", "${Buttons.Marketplace.PAGE}:${pageIndex - 1}"))
        }
        if ((pageIndex + 1) * pageSize < scripts.size) {
            navButtons.add(InlineKeyboardButton.CallbackData("➡️ Вперёд", "${Buttons.Marketplace.PAGE}:${pageIndex + 1}"))
        }
        if (navButtons.isNotEmpty()) {
            buttons.add(navButtons)
        }

        buttons.add(
            listOf(
                InlineKeyboardButton.CallbackData("📝 Загрузить скрипт", Buttons.Marketplace.UPLOAD)
            )
        )

        val markup = InlineKeyboardMarkup.create(buttons)

        if (messageId == null) {
            bot.sendMessage(
                ChatId.fromId(chatId),
                text = text,
                replyMarkup = markup
            )
        } else {
            bot.editMessageText(
                chatId = ChatId.fromId(chatId),
                messageId = messageId,
                text = text,
                replyMarkup = markup
            )
        }
    }
}
