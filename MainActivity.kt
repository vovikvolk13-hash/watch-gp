package com.watchgpt.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import org.json.JSONArray

data class ChatMessage(val role: String, val text: String, val images: List<String>)
data class ChatHistoryItem(val title: String, val url: String)

enum class WatchScreen {
    VOICE_HOME, CHAT_VIEW, CHAT_LIST, TEXT_INPUT, WEB_LOGIN, IMAGE_VIEW
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WatchGptApp()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WatchGptApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("watch_gpt_prefs", Context.MODE_PRIVATE) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var projectUrl by remember {
        mutableStateOf(prefs.getString("project_url", "https://chatgpt.com/") ?: "https://chatgpt.com/")
    }
    var currentScreen by remember { mutableStateOf(WatchScreen.VOICE_HOME) }
    var statusText by remember { mutableStateOf("Готов (проект «С часов»)") }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var chatList by remember { mutableStateOf<List<ChatHistoryItem>>(emptyList()) }
    var selectedImageUrl by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun sendTextToChatGpt(prompt: String) {
        if (prompt.isBlank()) return
        statusText = "Отправка..."
        currentScreen = WatchScreen.CHAT_VIEW
        val b64 = Base64.encodeToString(prompt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val js = """
            (function() {
                const text = new TextDecoder().decode(Uint8Array.from(atob('$b64'), c => c.charCodeAt(0)));
                const el = document.querySelector('#prompt-textarea') || document.querySelector('textarea');
                if (!el) return;
                el.focus();
                if (el.tagName === 'TEXTAREA') {
                    const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
                    setter.call(el, text);
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                } else {
                    el.innerHTML = '<p>' + text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') + '</p>';
                    el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: text }));
                }
                setTimeout(() => {
                    const btn = document.querySelector('[data-testid="send-button"]') ||
                                document.querySelector('button[aria-label*="Send"]') ||
                                document.querySelector('button[aria-label*="Отправить"]') ||
                                document.querySelector('form button[type="submit"]');
                    if (btn && !btn.disabled) {
                        btn.click();
                    } else {
                        el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                    }
                }, 350);
            })();
        """.trimIndent()
        webViewRef?.evaluateJavascript(js, null)
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                sendTextToChatGpt(spoken)
            }
        }
    }

    fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите запрос...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            statusText = "Голосовой ввод недоступен"
        }
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onMessagesUpdated(json: String, isGenerating: Boolean) {
                mainHandler.post {
                    try {
                        val arr = JSONArray(json)
                        val parsed = mutableListOf<ChatMessage>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val role = obj.optString("role", "assistant")
                            val text = obj.optString("text", "")
                            val imgArr = obj.optJSONArray("images")
                            val imgs = mutableListOf<String>()
                            if (imgArr != null) {
                                for (j in 0 until imgArr.length()) {
                                    imgs.add(imgArr.getString(j))
                                }
                            }
                            if (text.isNotBlank() || imgs.isNotEmpty()) {
                                parsed.add(ChatMessage(role, text, imgs))
                            }
                        }
                        messages = parsed
                        statusText = if (isGenerating) "Печатает..." else "Готов"
                    } catch (_: Exception) {}
                }
            }

            @JavascriptInterface
            fun onChatsUpdated(json: String) {
                mainHandler.post {
                    try {
                        val arr = JSONArray(json)
                        val parsed = mutableListOf<ChatHistoryItem>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val title = obj.optString("title", "Чат")
                            val url = obj.optString("url", "")
                            if (url.isNotBlank()) {
                                parsed.add(ChatHistoryItem(title, url))
                                if (title.contains("с часов", ignoreCase = true) && url != projectUrl) {
                                    projectUrl = url
                                    prefs.edit().putString("project_url", url).apply()
                                }
                            }
                        }
                        if (parsed.isNotEmpty()) chatList = parsed
                    } catch (_: Exception) {}
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Фоновый (или видимый в режиме входа) WebView, который держит сессию ChatGPT
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        // Убираем маркер WebView (; wv), чтобы Google/OpenAI и Cloudflare пускали без ограничений
                        userAgentString = "Mozilla/5.0 (Linux; Android 14; SM-R910) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    addJavascriptInterface(bridge, "WatchBridge")
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            CookieManager.getInstance().flush()
                            val scraperJs = """
                                (function() {
                                    if (window.__watchScraperActive) return;
                                    window.__watchScraperActive = true;
                                    setInterval(() => {
                                        try {
                                            const nodes = document.querySelectorAll('[data-message-author-role]');
                                            const msgs = [];
                                            nodes.forEach(n => {
                                                const role = n.getAttribute('data-message-author-role') || 'assistant';
                                                const text = (n.innerText || '').trim();
                                                const imgs = [];
                                                n.querySelectorAll('img').forEach(img => {
                                                    if (img.src && !img.src.includes('avatar') && (img.naturalWidth === 0 || img.naturalWidth > 48)) {
                                                        imgs.push(img.src);
                                                    }
                                                });
                                                msgs.push({ role, text, images: imgs });
                                            });
                                            const stopBtn = document.querySelector('[data-testid="stop-button"]') ||
                                                            document.querySelector('button[aria-label*="Stop"]');
                                            window.WatchBridge.onMessagesUpdated(JSON.stringify(msgs), !!stopBtn);

                                            const links = document.querySelectorAll('nav a[href], a[href^="/c/"], a[href*="/project"]');
                                            const chats = [];
                                            const seen = new Set();
                                            links.forEach(a => {
                                                const href = a.href;
                                                const title = (a.innerText || '').trim().replace(/\n+/g, ' ');
                                                if (href && title && !seen.has(href) && (href.includes('/c/') || href.includes('/project') || href.includes('/g/'))) {
                                                    seen.add(href);
                                                    chats.push({ title, url: href });
                                                }
                                            });
                                            if (chats.length > 0) {
                                                window.WatchBridge.onChatsUpdated(JSON.stringify(chats));
                                            }
                                        } catch (e) {}
                                    }, 1000);
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(scraperJs, null)
                        }
                    }
                    loadUrl(projectUrl)
                    webViewRef = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (currentScreen == WatchScreen.WEB_LOGIN) 1f else 0f }
        )

        // Нативный интерфейс для часов поверх WebView
        when (currentScreen) {
            WatchScreen.VOICE_HOME -> {
                VoiceHomeScreen(
                    statusText = statusText,
                    onVoiceClick = { startVoiceInput() },
                    onNewProjectChat = {
                        messages = emptyList()
                        statusText = "Новый чат («С часов»)..."
                        webViewRef?.loadUrl(projectUrl)
                    },
                    onOpenChatView = { currentScreen = WatchScreen.CHAT_VIEW },
                    onOpenChatList = {
                        // Пробуем раскрыть боковое меню в фоне, чтобы подтянуть свежий список чатов
                        webViewRef?.evaluateJavascript(
                            """(document.querySelector('button[aria-label*="sidebar"]') || document.querySelector('button[aria-label*="меню"]'))?.click();""",
                            null
                        )
                        currentScreen = WatchScreen.CHAT_LIST
                    },
                    onOpenTextInput = { currentScreen = WatchScreen.TEXT_INPUT },
                    onOpenWebMode = { currentScreen = WatchScreen.WEB_LOGIN }
                )
            }

            WatchScreen.CHAT_VIEW -> {
                ChatViewScreen(
                    messages = messages,
                    statusText = statusText,
                    onVoiceReply = { startVoiceInput() },
                    onTextReply = { currentScreen = WatchScreen.TEXT_INPUT },
                    onImageClick = { url ->
                        selectedImageUrl = url
                        currentScreen = WatchScreen.IMAGE_VIEW
                    },
                    onBackHome = { currentScreen = WatchScreen.VOICE_HOME }
                )
            }

            WatchScreen.CHAT_LIST -> {
                ChatListScreen(
                    chats = chatList,
                    onSelectChat = { item ->
                        messages = emptyList()
                        statusText = "Загрузка: ${item.title}"
                        webViewRef?.loadUrl(item.url)
                        currentScreen = WatchScreen.CHAT_VIEW
                    },
                    onNewChatInProject = {
                        messages = emptyList()
                        webViewRef?.loadUrl(projectUrl)
                        currentScreen = WatchScreen.VOICE_HOME
                    },
                    onBack = { currentScreen = WatchScreen.VOICE_HOME }
                )
            }

            WatchScreen.TEXT_INPUT -> {
                TextInputScreen(
                    onSend = { text -> sendTextToChatGpt(text) },
                    onCancel = { currentScreen = WatchScreen.VOICE_HOME }
                )
            }

            WatchScreen.IMAGE_VIEW -> {
                ImageViewScreen(
                    imageUrl = selectedImageUrl ?: "",
                    onBack = { currentScreen = WatchScreen.CHAT_VIEW }
                )
            }

            WatchScreen.WEB_LOGIN -> {
                // Плавающие кнопки поверх открытого сайта ChatGPT
                Box(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactChip(
                            onClick = { currentScreen = WatchScreen.VOICE_HOME },
                            label = { Text("← Часы", fontSize = 11.sp) },
                            colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xCC1E88E5))
                        )
                        CompactChip(
                            onClick = {
                                val currentUrl = webViewRef?.url
                                if (!currentUrl.isNullOrBlank()) {
                                    projectUrl = currentUrl
                                    prefs.edit().putString("project_url", currentUrl).apply()
                                    statusText = "Проект «С часов» сохранен"
                                    currentScreen = WatchScreen.VOICE_HOME
                                }
                            },
                            label = { Text("📌 Сделать «С часов»", fontSize = 11.sp) },
                            colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xCC2E7D32))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceHomeScreen(
    statusText: String,
    onVoiceClick: () -> Unit,
    onNewProjectChat: () -> Unit,
    onOpenChatView: () -> Unit,
    onOpenChatList: () -> Unit,
    onOpenTextInput: () -> Unit,
    onOpenWebMode: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(timeText = { TimeText() }, vignette = { Vignette(VignettePosition.TopAndBottom) }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onRotaryScrollEvent {
                    coroutineScope.launch { listState.scrollBy(it.verticalScrollPixels) }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    color = Color(0xFF80CBC4),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
            }
            item {
                // Главная крупная кнопка микрофона
                Button(
                    onClick = onVoiceClick,
                    modifier = Modifier.size(76.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFF10A37F))
                ) {
                    Text("🎤", fontSize = 30.sp)
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    CompactChip(
                        onClick = onOpenTextInput,
                        label = { Text("⌨️ Текст", fontSize = 12.sp) }
                    )
                    CompactChip(
                        onClick = onOpenChatView,
                        label = { Text("📖 Ответ", fontSize = 12.sp) }
                    )
                }
            }
            item {
                Chip(
                    onClick = onNewProjectChat,
                    label = { Text("➕ Новый «С часов»", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(0.9f),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
            item {
                Chip(
                    onClick = onOpenChatList,
                    label = { Text("💬 Другие чаты", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(0.9f),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
            item {
                Chip(
                    onClick = onOpenWebMode,
                    label = { Text("🌐 Веб-режим / Вход", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(0.9f),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}

@Composable
fun ChatViewScreen(
    messages: List<ChatMessage>,
    statusText: String,
    onVoiceReply: () -> Unit,
    onTextReply: () -> Unit,
    onImageClick: (String) -> Unit,
    onBackHome: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current

    LaunchedEffect(messages.size) {
        focusRequester.requestFocus()
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onRotaryScrollEvent {
                    coroutineScope.launch { listState.scrollBy(it.verticalScrollPixels) }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    color = Color(0xFF80CBC4),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (messages.isEmpty()) {
                item {
                    Text(
                        text = "Сообщений пока нет.\nНажмите 🎤 или ⌨️ ниже.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            items(messages) { msg ->
                val isUser = msg.role == "user"
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isUser) Color(0xFF1E3A2F) else Color(0xFF1F2227))
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (isUser) "Вы:" else "ChatGPT:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isUser) Color(0xFF10A37F) else Color(0xFF90CAF9)
                    )
                    if (msg.text.isNotBlank()) {
                        Text(
                            text = msg.text,
                            fontSize = 13.sp,
                            color = Color.White,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    msg.images.forEach { imgUrl ->
                        val cookie = CookieManager.getInstance().getCookie(imgUrl) ?: ""
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imgUrl)
                                .addHeader("Cookie", cookie)
                                .diskCachePolicy(coil.request.CachePolicy.DISABLED) // Без сохранения на диск
                                .build(),
                            contentDescription = "Изображение",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                                .height(110.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onImageClick(imgUrl) }
                        )
                    }
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    CompactChip(onClick = onVoiceReply, label = { Text("🎤") })
                    CompactChip(onClick = onTextReply, label = { Text("⌨️") })
                    CompactChip(onClick = onBackHome, label = { Text("🏠") })
                }
            }
        }
    }
}

@Composable
fun ChatListScreen(
    chats: List<ChatHistoryItem>,
    onSelectChat: (ChatHistoryItem) -> Unit,
    onNewChatInProject: () -> Unit,
    onBack: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onRotaryScrollEvent {
                    coroutineScope.launch { listState.scrollBy(it.verticalScrollPixels) }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                CompactChip(
                    onClick = onNewChatInProject,
                    label = { Text("➕ В проект «С часов»", fontSize = 12.sp) },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF10A37F))
                )
            }
            if (chats.isEmpty()) {
                item {
                    Text(
                        text = "Список чатов пуст.\nОткройте «🌐 Веб-режим», чтобы войти в аккаунт.",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(chats) { item ->
                Chip(
                    onClick = { onSelectChat(item) },
                    label = {
                        Text(
                            text = item.title,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.fillMaxWidth(0.92f),
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
            item {
                CompactChip(onClick = onBack, label = { Text("← Назад", fontSize = 12.sp) })
            }
        }
    }
}

@Composable
fun TextInputScreen(
    onSend: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Color(0xFF10A37F)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text) }),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF22252A))
                .padding(8.dp)
                .focusRequester(focusRequester),
            decorationBox = { innerTextField ->
                if (text.isEmpty()) {
                    Text("Введите сообщение...", color = Color.Gray, fontSize = 13.sp)
                }
                innerTextField()
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactChip(
                onClick = { if (text.isNotBlank()) onSend(text) },
                label = { Text("Отправить", fontSize = 12.sp) },
                colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF10A37F))
            )
            CompactChip(
                onClick = onCancel,
                label = { Text("Отмена", fontSize = 12.sp) }
            )
        }
    }
}

@Composable
fun ImageViewScreen(
    imageUrl: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val cookie = remember(imageUrl) { CookieManager.getInstance().getCookie(imageUrl) ?: "" }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .addHeader("Cookie", cookie)
                .diskCachePolicy(coil.request.CachePolicy.DISABLED)
                .build(),
            contentDescription = "Полноэкранное фото",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
        CompactChip(
            onClick = onBack,
            label = { Text("✕ Закрыть", fontSize = 11.sp) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
        )
    }
}
