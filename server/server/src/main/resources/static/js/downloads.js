(function () {
    'use strict';

    const channelSelect = document.getElementById('download-channel');
    const catalog = document.getElementById('download-catalog');
    const historyHost = document.getElementById('download-history');
    const status = document.getElementById('download-status');
    if (!channelSelect || !catalog || !historyHost || !status) return;

    const channels = ['stable', 'preview', 'snapshot'];
    const channelLabels = { stable: '发行版', preview: '预览版', snapshot: '内测快照' };
    const targets = [
        { id: 'windows', client: 'desktop', platform: 'windows', arch: 'amd64', name: 'Windows',
            requirement: 'Windows 10 1903+ · x64', primary: /\.exe$/i, label: '下载安装包', secondary: /\.zip$/i, secondaryLabel: '便携 ZIP',
            note: '安装包尚未使用受信任的代码签名证书。便携 ZIP 解压即可使用。' },
        { id: 'mac-arm', client: 'desktop', platform: 'macos', arch: 'aarch64', name: 'macOS · Apple 芯片',
            requirement: 'macOS 14+ · Apple Silicon', primary: /\.zip$/i, label: '下载应用 ZIP',
            note: '解压后放入“应用程序”。当前未提供 Apple 开发者 ID 签名与公证。' },
        { id: 'mac-intel', client: 'desktop', platform: 'macos', arch: 'amd64', name: 'macOS · Intel 芯片',
            requirement: 'macOS 14+ · Intel x64', primary: /\.zip$/i, label: '下载应用 ZIP',
            note: '解压后放入“应用程序”。当前未提供 Apple 开发者 ID 签名与公证。' },
        { id: 'linux', client: 'desktop', platform: 'linux', arch: 'amd64', name: 'Linux',
            requirement: 'x64 · Debian / Ubuntu 等发行版', primary: /\.deb$/i, label: '下载 DEB', secondary: /\.tar\.gz$/i, secondaryLabel: 'TAR.GZ',
            note: 'DEB 声明系统依赖；使用 TAR.GZ 时需自行安装图形库和 GStreamer 媒体依赖。' },
        { id: 'android', client: 'android', platform: 'android', arch: 'any', name: 'Android',
            requirement: 'Android 8.0+', primary: /\.apk$/i, label: '下载 APK',
            note: '用手机浏览器打开本页即可下载。覆盖安装时请保持相同应用身份与签名。' },
        { id: 'headless', client: 'headless', platform: 'any', arch: 'any', name: '无头客户端',
            requirement: 'Java 21+ · CLI / Agent / MCP', primary: /\.zip$/i, label: '下载完整 ZIP',
            note: 'Linux / macOS 支持 Agent、CLI 与 MCP；Windows 提供 tt CLI。' },
    ];
    let data;

    function element(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function formatSize(bytes) {
        if (!Number.isFinite(bytes) || bytes < 0) return '';
        if (bytes >= 1024 ** 3) return (bytes / 1024 ** 3).toFixed(2) + ' GB';
        if (bytes >= 1024 ** 2) return (bytes / 1024 ** 2).toFixed(1) + ' MB';
        if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return bytes + ' B';
    }

    function formatDate(millis) {
        if (!Number.isFinite(millis) || millis <= 0) return '';
        const date = new Date(millis);
        return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', {
            year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
        }).format(date);
    }

    function sourceLabel(buildIdentity) {
        const match = typeof buildIdentity === 'string' && /^\d+\.\d+\.\d+\+([a-f0-9]{40})$/i.exec(buildIdentity);
        return match ? '源码 ' + match[1].slice(0, 8).toLowerCase() : '';
    }

    // 注册中心的制品 URL 指向本站；不把错误响应中的任意地址变成下载按钮。
    function artifactUrl(value) {
        if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return null;
        try {
            const url = new URL(value, window.location.origin);
            return url.origin === window.location.origin ? url.href : null;
        } catch (_) {
            return null;
        }
    }

    function channelState(target, channel) {
        const entry = data.targets.find(item => item.clientType === target.client &&
            item.platform === target.platform && item.arch === target.arch);
        return entry && Array.isArray(entry.channels) ? entry.channels.find(item => item.channel === channel) : null;
    }

    function packages(target, state) {
        if (!state || state.enabled !== true || !state.release) return [];
        const release = state.release;
        const installers = Array.isArray(release.installers) ? release.installers.filter(file =>
            typeof file.filename === 'string' && artifactUrl(file.url)) : [];
        if (target.client === 'headless' && artifactUrl(release.bundleUrl)) {
            // 当前 headless 发布只有全量包；存在其他制品时总大小不能当作 ZIP 大小。
            return [{ url: release.bundleUrl, filename: 'TeamTalk-headless.zip',
                size: (release.fileCount ?? 0) === 0 && !(release.installers || []).length ? release.totalBytes : null,
                buttonLabel: target.label }];
        }
        const primary = installers.find(file => target.primary.test(file.filename));
        const secondary = target.secondary && installers.find(file => target.secondary.test(file.filename));
        return [primary && { ...primary, buttonLabel: target.label },
            secondary && { ...secondary, buttonLabel: target.secondaryLabel }].filter(Boolean);
    }

    function downloadButton(file, secondary) {
        const size = formatSize(file.size);
        const button = element('a', 'download-button' + (secondary ? ' download-button-secondary' : ''),
            file.buttonLabel + (size ? ' · ' + size : ''));
        button.href = artifactUrl(file.url);
        button.download = file.filename;
        return button;
    }

    function installationHelp(target) {
        const windows = target.platform === 'windows';
        const guide = element('details', 'download-guide installation-help');
        const summary = element('summary', '', '安装被拦截？');
        summary.setAttribute('aria-label', target.name + '：安装被拦截？');
        guide.append(summary, element('p', '', windows ?
            '内测包尚未使用受信任的代码签名证书，首次运行可能出现 SmartScreen 提示。' :
            '内测包尚未提供 Apple 开发者 ID 签名与公证，首次打开可能提示无法验证开发者。'));
        const steps = windows ? [
            '确认安装包来自本下载页或你信任的私有部署。',
            '若显示“Windows 已保护你的电脑”，点击“更多信息”，再选择“仍要运行”。',
            '如果没有“仍要运行”，可能受到智能应用控制或组织策略限制，请联系设备管理员或到交流群反馈。',
        ] : [
            '将 ZIP 解压，把应用放入“应用程序”，并尝试打开一次。',
            '确认下载来源后，打开“系统设置 → 隐私与安全性”，找到该应用被阻止的提示，点击“仍要打开”。',
            '按系统提示再次确认“打开”；公司管理的设备可能需要管理员处理。',
        ];
        const list = element('ol');
        steps.forEach(step => list.append(element('li', '', step)));
        guide.append(list, element('p', '', '若系统明确提示恶意软件或应用损坏，请先停止安装并反馈完整提示。无需关闭系统整体防护。'));
        const official = element('a', 'text-link', windows ? '微软安装说明 ↗' : 'Apple 打开应用说明 ↗');
        official.href = windows ? 'https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/publish-first-app#step-6-handle-smartscreen-for-new-apps' :
            'https://support.apple.com/zh-cn/102445';
        guide.append(official);
        return guide;
    }

    function renderCard(target, channel) {
        const state = channelState(target, channel);
        const files = packages(target, state);
        const card = element('article', 'download-card' + (files.length ? '' : ' is-unavailable'));
        card.id = 'download-' + target.id;
        const heading = element('div', 'download-card-heading');
        heading.append(element('span', 'download-platform', target.client === 'desktop' ? '桌面客户端' :
            target.client === 'android' ? '移动客户端' : '自动化与集成'), element('h3', '', target.name));
        card.append(heading, element('p', 'download-requirement', target.requirement));
        if (files.length) {
            const release = state.release;
            card.append(element('p', 'download-version', 'v' + release.version + ' · build ' + release.build +
                ' · ' + channelLabels[channel]));
            const source = sourceLabel(release.buildIdentity);
            if (source) card.append(element('p', 'download-meta', source));
            const date = formatDate(state.updatedAt);
            if (date) card.append(element('p', 'download-meta', '通道更新 ' + date));
            const actions = element('div', 'download-actions');
            files.forEach((file, index) => actions.append(downloadButton(file, index > 0)));
            card.append(actions);
        } else {
            const message = state && state.enabled === false ? '此平台的当前通道已停用' : '此通道暂无可下载的安装包';
            card.append(element('p', 'download-empty', message));
        }
        card.append(element('p', 'download-note', target.note));
        if (target.platform === 'windows' || target.platform === 'macos') card.append(installationHelp(target));
        if (target.client === 'headless' && files.length) {
            const guide = element('details', 'download-guide');
            guide.append(element('summary', '', '解压与运行'));
            guide.append(element('p', '', '解压 ZIP 后进入 tt-headless 目录。Linux / macOS 可执行：'));
            const code = element('code', '', 'cd tt-headless\nbin/tt --help\n# 安装到固定目录\nbin/tt-agent install-bundle --prefix ~/.teamtalk');
            const pre = element('pre');
            pre.append(code);
            guide.append(pre, element('p', '', 'Windows 使用 bin\\tt.bat。已安装的 Agent 可用 tt-agent upgrade 升级。'));
            card.append(guide);
        }
        return card;
    }

    function renderHistory(channel) {
        historyHost.replaceChildren();
        const entries = data.history.filter(item => item.channel === channel);
        if (!entries.length) {
            historyHost.append(element('p', 'download-empty', '当前通道暂无版本记录。'));
            return;
        }
        const list = element('ul', 'download-history-list');
        const statuses = { ACTIVE: '可用记录', DISABLED: '已停用', SUPERSEDED: '已被替代', DELETED: '已删除' };
        for (const entry of entries) {
            const target = targets.find(item => item.client === entry.clientType && item.platform === entry.platform && item.arch === entry.arch);
            const row = element('li', 'download-history-item');
            row.append(element('strong', '', (target ? target.name : entry.clientType) + ' · v' + entry.version + ' · build ' + entry.build));
            row.append(element('span', 'download-history-meta', [channelLabels[channel],
                sourceLabel(entry.buildIdentity), statuses[entry.status] || entry.status,
                formatDate(entry.createdAt)].filter(Boolean).join(' · ')));
            list.append(row);
        }
        historyHost.append(list);
    }

    function render() {
        const channel = channelSelect.value;
        catalog.replaceChildren(...targets.map(target => renderCard(target, channel)));
        renderHistory(channel);
        const count = targets.filter(target => packages(target, channelState(target, channel)).length).length;
        status.textContent = count ? channelLabels[channel] + ' · ' + count + ' 个目标可下载，文件直接来自当前部署。' :
            '当前通道暂无可下载发布，请选择其他通道或稍后重试。';
    }

    async function load() {
        channelSelect.disabled = true;
        catalog.setAttribute('aria-busy', 'true');
        status.textContent = '正在读取发布信息…';
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 15000);
        try {
            const response = await fetch('/api/v1/public/downloads', { cache: 'no-store', signal: controller.signal });
            if (!response.ok) throw new Error('Download catalog is unavailable');
            const result = await response.json();
            if (!result || !Array.isArray(result.targets) || !Array.isArray(result.history)) throw new Error('Invalid download catalog');
            data = result;
            const available = channels.filter(channel => targets.some(target => packages(target, channelState(target, channel)).length));
            channelSelect.replaceChildren(...channels.map(channel => {
                const option = element('option', '', channelLabels[channel] + (available.includes(channel) ? '' : '（暂无下载）'));
                option.value = channel;
                return option;
            }));
            channelSelect.value = available[0] || channels[0];
            channelSelect.disabled = false;
            render();
        } catch (_) {
            catalog.replaceChildren();
            historyHost.replaceChildren(element('p', 'download-empty', '版本记录暂时无法读取。'));
            const retry = element('button', 'download-retry', '重试');
            retry.type = 'button';
            retry.addEventListener('click', load, { once: true });
            status.replaceChildren(document.createTextNode('发布信息暂时无法读取。请检查网络后重试。 '), retry);
        } finally {
            clearTimeout(timeout);
            catalog.setAttribute('aria-busy', 'false');
        }
    }

    channelSelect.addEventListener('change', render);
    load();
})();
