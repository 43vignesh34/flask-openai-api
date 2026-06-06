document.addEventListener('DOMContentLoaded', () => {
    // DOM Elements
    const chatForm = document.getElementById('chat-form');
    const chatInput = document.getElementById('chat-input');
    const sendBtn = document.getElementById('send-btn');
    const messagesContainer = document.getElementById('messages-container');
    const typingIndicator = document.getElementById('typing-indicator');
    const welcomeCard = document.getElementById('welcome-card');
    const currentSessionTitle = document.getElementById('current-session-title');
    const sessionInput = document.getElementById('session-input');
    const newSessionBtn = document.getElementById('new-session-btn');
    const sessionList = document.getElementById('session-list');
    const clearChatBtn = document.getElementById('clear-chat-btn');
    const toastContainer = document.getElementById('toast-container');
    
    // Application State
    let currentSessionId = '';
    let sessions = [];

    // Initialize Application
    init();

    function init() {
        // Load sessions from localStorage
        const storedSessions = localStorage.getItem('chat_sessions');
        const storedActiveSession = localStorage.getItem('chat_active_session');

        if (storedSessions) {
            sessions = JSON.parse(storedSessions);
        }

        if (storedActiveSession) {
            currentSessionId = storedActiveSession;
        } else {
            // Generate a default unique session if none exists
            currentSessionId = generateSessionId();
            sessions.push(currentSessionId);
            saveSessionsState();
        }

        // Render sessions in sidebar
        renderSessions();
        selectSession(currentSessionId);

        // Auto-grow textarea binding
        chatInput.addEventListener('input', autoGrowTextArea);

        // Submit form on Enter key press (without shift key)
        chatInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                chatForm.dispatchEvent(new Event('submit'));
            }
        });

        // Form Submit Handler
        chatForm.addEventListener('submit', handleFormSubmit);

        // Create new session via button
        newSessionBtn.addEventListener('click', () => {
            const customId = sessionInput.value.trim();
            createNewSession(customId);
        });

        // Bind suggestion chips
        document.querySelectorAll('.suggestion-chip').forEach(chip => {
            chip.addEventListener('click', () => {
                const promptText = chip.getAttribute('data-prompt');
                chatInput.value = promptText;
                autoGrowTextArea();
                chatInput.focus();
            });
        });

        // Clear chat view screen
        clearChatBtn.addEventListener('click', clearScreen);

        // Initialize Lucide Icons
        lucide.createIcons();
    }

    // --- State Handlers ---

    function generateSessionId() {
        return 'session-' + Math.random().toString(36).substring(2, 9);
    }

    function saveSessionsState() {
        localStorage.setItem('chat_sessions', JSON.stringify(sessions));
        localStorage.setItem('chat_active_session', currentSessionId);
    }

    function createNewSession(customId) {
        let newId = customId || generateSessionId();
        
        if (sessions.includes(newId)) {
            showToast('Session ID already exists. Switch to it instead.', 'info');
            selectSession(newId);
            sessionInput.value = '';
            return;
        }

        sessions.push(newId);
        saveSessionsState();
        renderSessions();
        selectSession(newId);
        sessionInput.value = '';
        showToast('Created session: ' + newId, 'success');
    }

    function selectSession(sessionId) {
        currentSessionId = sessionId;
        localStorage.setItem('chat_active_session', currentSessionId);
        
        // Update Title Header
        currentSessionTitle.textContent = `Session: ${sessionId}`;
        
        // Update active class in sidebar
        document.querySelectorAll('.session-item').forEach(item => {
            if (item.getAttribute('data-session') === sessionId) {
                item.classList.add('active');
            } else {
                item.classList.remove('active');
            }
        });

        // Clear screen and fetch/restore history if any exists in local memory
        clearMessagesFromContainer();
        
        // Render welcome card or fetch history from local storage storage cache
        const historyCache = localStorage.getItem(`history_${currentSessionId}`);
        if (historyCache) {
            const history = JSON.parse(historyCache);
            if (history.length > 0) {
                welcomeCard.style.display = 'none';
                history.forEach(msg => appendMessageBubble(msg.role, msg.content));
            } else {
                welcomeCard.style.display = 'flex';
            }
        } else {
            welcomeCard.style.display = 'flex';
        }
    }

    function deleteSession(sessionId, event) {
        event.stopPropagation(); // Avoid triggering selectSession

        if (sessions.length <= 1) {
            showToast('You must keep at least one active session.', 'error');
            return;
        }

        sessions = sessions.filter(id => id !== sessionId);
        localStorage.removeItem(`history_${sessionId}`);

        if (currentSessionId === sessionId) {
            currentSessionId = sessions[0];
        }

        saveSessionsState();
        renderSessions();
        selectSession(currentSessionId);
        showToast('Deleted session ' + sessionId, 'info');
    }

    // --- UI Renderers ---

    function renderSessions() {
        sessionList.innerHTML = '';
        sessions.forEach(id => {
            const li = document.createElement('li');
            li.className = `session-item ${id === currentSessionId ? 'active' : ''}`;
            li.setAttribute('data-session', id);
            li.innerHTML = `
                <div style="display:flex; align-items:center; gap:8px;">
                    <i data-lucide="message-square" style="width:14px; height:14px;"></i>
                    <span>${id}</span>
                </div>
                <div style="display:flex; align-items:center; gap:6px;">
                    <span class="active-indicator"></span>
                    <button class="delete-session-btn" title="Delete Session">
                        <i data-lucide="x" style="width:14px; height:14px;"></i>
                    </button>
                </div>
            `;

            // Select session on click
            li.addEventListener('click', () => selectSession(id));
            
            // Delete button binding
            li.querySelector('.delete-session-btn').addEventListener('click', (e) => deleteSession(id, e));

            sessionList.appendChild(li);
        });

        // Re-trigger icon updates
        lucide.createIcons();
    }

    function autoGrowTextArea() {
        chatInput.style.height = 'auto';
        chatInput.style.height = (chatInput.scrollHeight) + 'px';
    }

    function clearScreen() {
        clearMessagesFromContainer();
        localStorage.removeItem(`history_${currentSessionId}`);
        welcomeCard.style.display = 'flex';
        showToast('Chat screen cleared.', 'info');
    }

    function clearMessagesFromContainer() {
        // Keep the welcome card and typing indicator, delete everything else
        const bubbles = messagesContainer.querySelectorAll('.message-bubble');
        bubbles.forEach(bubble => bubble.remove());
    }

    function formatMessageContent(text) {
        if (!text) return '';
        
        // Escape basic HTML to avoid XSS
        let escaped = text
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;");

        // Format code blocks (```code```)
        escaped = escaped.replace(/```([\s\S]*?)```/g, (match, p1) => {
            return `<pre><code>${p1.trim()}</code></pre>`;
        });

        // Format inline code (`inline`)
        escaped = escaped.replace(/`([^`]+)`/g, '<code>$1</code>');

        // Replace newlines with breaks
        return escaped.replace(/\n/g, '<br>');
    }

    function appendMessageBubble(role, content) {
        const bubble = document.createElement('div');
        bubble.className = `message-bubble ${role}`;
        
        const timestamp = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        const displayRole = role === 'user' ? 'You' : 'Assistant';

        bubble.innerHTML = `
            <div class="message-meta">${displayRole} • ${timestamp}</div>
            <div class="message-content">${formatMessageContent(content)}</div>
        `;

        messagesContainer.appendChild(bubble);
        scrollToBottom();
    }

    function scrollToBottom() {
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }

    function showTypingIndicator(show) {
        if (show) {
            typingIndicator.style.display = 'block';
            scrollToBottom();
        } else {
            typingIndicator.style.display = 'none';
        }
    }

    // --- API Interactions ---

    async function handleFormSubmit(e) {
        e.preventDefault();
        
        const messageText = chatInput.value.trim();
        if (!messageText) return;

        // Hide welcome card upon first message
        welcomeCard.style.display = 'none';

        // Add user message to UI
        appendMessageBubble('user', messageText);

        // Reset input fields
        chatInput.value = '';
        autoGrowTextArea();

        // Show loading typing animation
        showTypingIndicator(true);

        try {
            const response = await fetch('/ask-stream', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    session_id: currentSessionId,
                    input: messageText
                })
            });

            if (!response.ok) {
                showTypingIndicator(false);
                let errorMsg = 'Failed to get a response from AI.';
                if (response.status === 401) {
                    errorMsg = 'Authentication Error: Please check your API key settings in the .env file.';
                } else if (response.status === 429) {
                    errorMsg = 'Rate Limit Exceeded: Please check your OpenAI quota.';
                } else {
                    try {
                        const data = await response.json();
                        errorMsg = data.error || errorMsg;
                    } catch (e) {}
                }
                showToast(errorMsg, 'error');
                appendMessageBubble('assistant', `⚠️ Error: ${errorMsg}`);
                return;
            }

            showTypingIndicator(false);

            // Create and append an empty assistant bubble
            const bubble = document.createElement('div');
            bubble.className = 'message-bubble assistant';
            const timestamp = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            bubble.innerHTML = `
                <div class="message-meta">Assistant • ${timestamp}</div>
                <div class="message-content"></div>
            `;
            messagesContainer.appendChild(bubble);
            const contentDiv = bubble.querySelector('.message-content');
            scrollToBottom();

            // Read the stream
            const reader = response.body.getReader();
            const decoder = new TextDecoder('utf-8');
            let buffer = '';
            let fullText = '';
            let currentEvent = '';

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop(); // Keep partial line in buffer

                for (const line of lines) {
                    const cleanLine = line.replace(/\r$/, '');
                    if (cleanLine.startsWith('event: ')) {
                        currentEvent = cleanLine.substring(7).trim();
                    } else if (cleanLine.startsWith('data: ')) {
                        const dataVal = cleanLine.substring(6);
                        if (currentEvent === 'delta') {
                            fullText += dataVal;
                            contentDiv.innerHTML = formatMessageContent(fullText);
                            scrollToBottom();
                        } else if (currentEvent === 'error') {
                            showToast('Error: ' + dataVal.trim(), 'error');
                            contentDiv.innerHTML += ` <br><span style="color:#ef4444; font-weight:500;">⚠️ Error: ${dataVal.trim()}</span>`;
                            scrollToBottom();
                        }
                    }
                }
            }

            // Cache conversation history locally
            let historyCache = localStorage.getItem(`history_${currentSessionId}`);
            let history = historyCache ? JSON.parse(historyCache) : [];
            history.push({ "role": "user", "content": messageText });
            history.push({ "role": "assistant", "content": fullText });
            localStorage.setItem(`history_${currentSessionId}`, JSON.stringify(history));

        } catch (error) {
            showTypingIndicator(false);
            console.error('API Error:', error);
            showToast('Network error: Could not reach the server.', 'error');
            appendMessageBubble('assistant', '⚠️ Network error: Could not reach the server.');
        }
    }

    // --- Toast Handler ---

    function showToast(message, type = 'info') {
        const toast = document.createElement('div');
        toast.className = `toast ${type === 'error' ? 'toast-error' : ''}`;
        
        const iconName = type === 'error' ? 'alert-triangle' : 'info';
        
        toast.innerHTML = `
            <i data-lucide="${iconName}"></i>
            <span>${message}</span>
            <button class="toast-close"><i data-lucide="x" style="width:14px; height:14px;"></i></button>
        `;

        toastContainer.appendChild(toast);
        lucide.createIcons();

        // Close on X click
        toast.querySelector('.toast-close').addEventListener('click', () => {
            toast.remove();
        });

        // Auto remove after 5 seconds
        setTimeout(() => {
            if (toast.parentNode) {
                toast.remove();
            }
        }, 5000);
    }
});
