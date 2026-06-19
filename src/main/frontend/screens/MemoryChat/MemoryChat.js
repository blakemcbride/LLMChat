
/* global $$, Server, Utils */

'use strict';

(async function () {

    let history = [];       // [{role, content}]  — sent to the backend each turn
    let items = [];         // [{who, html}]       — rendered transcript
    let connected = false;

    function escapeHtml(s) {
        return (s || '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    }

    function render() {
        let html = '';
        if (items.length === 0)
            html = '<div style="color:#999;">No messages yet. Say hello…</div>';
        for (let i = 0; i < items.length; i++) {
            const it = items[i];
            const align = it.who === 'user' ? 'right' : 'left';
            const bg = it.who === 'user' ? '#d7ebff' : '#ececec';
            const label = it.who === 'user' ? 'You' : 'Assistant';
            html += '<div style="text-align:' + align + '; margin:6px 0;">'
                 +    '<div style="display:inline-block; max-width:80%; text-align:left; background:'
                 +        bg + '; padding:6px 10px; border-radius:8px;">'
                 +      '<div style="font-size:11px; color:#888;">' + label + '</div>'
                 +      it.html
                 +    '</div>'
                 +  '</div>';
        }
        $$('transcript').setHTMLValue(html);
    }

    function addUser(text) {
        items.push({ who: 'user', html: escapeHtml(text) });
        render();
    }

    function addAssistant(htmlAnswer, usedMemories) {
        const list = usedMemories || [];
        let extra = '';
        if (list.length) {
            let lis = '';
            for (let i = 0; i < list.length; i++) {
                const m = list[i];
                const score = (m.score != null) ? ' <span style="color:#aaa;">(' + m.score.toFixed(2) + ')</span>' : '';
                lis += '<li>' + escapeHtml(m.text) + score + '</li>';
            }
            // Native <details> — clickable to reveal the recalled memories, no DOM access needed.
            extra = '<details style="margin-top:4px;">'
                  + '<summary style="font-size:11px; color:#888; cursor:pointer;">grounded in '
                  + list.length + (list.length === 1 ? ' memory' : ' memories') + '</summary>'
                  + '<ul style="font-size:11px; color:#777; margin:4px 0 0 0; padding-left:18px;">'
                  + lis + '</ul></details>';
        }
        items.push({ who: 'assistant', html: htmlAnswer + extra });
        render();
    }

    function setBadge() {
        $$('ownsona-badge').setValue(connected ? 'OwnSona: connected' : 'OwnSona: not connected');
        // Toggle: connect when disconnected, disconnect when connected (the
        // latter also preserves a reconnect path if a token ever goes bad).
        $$('connect-ownsona').setValue(connected ? 'Disconnect' : 'Connect OwnSona');
    }

    async function refreshStatus() {
        const res = await Server.call('services/OwnSonaAuth', 'status');
        if (res._Success) {
            connected = !!res.connected;
            setBadge();
        }
    }

    // ---------- init ----------
    render();
    $$('ownsona-badge').setValue('OwnSona: checking…');

    let res = await Server.call('services/OllamaInfo', 'health');
    if (res._Success && !res.up)
        await Utils.showMessage('Ollama', 'The local Ollama server does not appear to be running.');

    res = await Server.call('services/OllamaInfo', 'models');
    if (res._Success) {
        const models = res.models || [];
        const ctl = $$('model').clear();
        if (models.length === 0)
            await Utils.showMessage('Ollama', 'No Ollama models are installed.');
        for (let i = 0; i < models.length; i++)
            ctl.add(models[i], models[i]);
        if (res.defaultModel && models.indexOf(res.defaultModel) >= 0)
            ctl.setValue(res.defaultModel);
        else if (models.length > 0)
            ctl.setValue(models[0]);
    }

    await refreshStatus();
    $$('user-msg').focus();

    // ---------- connect / disconnect OwnSona ----------
    $$('connect-ownsona').onclick(async function () {
        if (connected) {
            await Server.call('services/OwnSonaAuth', 'logout');
            await refreshStatus();
            return;
        }
        const r = await Server.call('services/OwnSonaAuth', 'beginLogin');
        if (!r._Success || !r.authUrl)
            return;
        window.open(r.authUrl, '_blank');
        await Utils.showMessage('Connect OwnSona',
            'A new tab was opened to log in to OwnSona. After you approve access there, ' +
            'return here and click OK.');
        await refreshStatus();
        if (!connected)
            await Utils.showMessage('OwnSona',
                'Still not connected. Please finish the OwnSona login, then try again.');
    });

    // ---------- new chat ----------
    $$('new-chat').onclick(function () {
        history = [];
        items = [];
        render();
        $$('user-msg').clear().focus();
    });

    // ---------- send ----------
    $$('send').onclick(async function () {
        const msg = ($$('user-msg').getValue() || '').trim();
        if (!msg)
            return;
        if ($$('model').isError('Model'))
            return;

        addUser(msg);
        $$('user-msg').clear();

        Utils.waitMessage('Thinking…');
        const res = await Server.call('services/ChatService', 'send', {
            message: msg,
            model: $$('model').getValue(),
            history: history
        });
        Utils.waitMessageEnd();

        if (!res._Success)
            return;

        if (res.needsLogin) {
            await Utils.showMessage('OwnSona',
                'You need to connect to OwnSona before chatting. Click "Connect OwnSona".');
            await refreshStatus();
            return;
        }

        if (res.error) {
            await Utils.showMessage('Error', res.error);
            return;
        }

        addAssistant(res.htmlAnswer || escapeHtml(res.answer || ''), res.usedMemories);
        history.push({ role: 'user', content: msg });
        history.push({ role: 'assistant', content: res.answer || '' });
        // Bound client-side history (the server also caps what it sends to the model).
        while (history.length > 40)
            history.shift();
        $$('user-msg').focus();
    });

})();
