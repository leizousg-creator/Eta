package io.github.mangi.eta.agent.browser

import org.json.JSONObject

/**
 * Agent 浏览器的调试控制台脚本。
 *
 * 语义对齐上游 `browser_devtools`：页面侧缓存 console 与网络请求，读回前统一脱敏，
 * `eval` 执行期间打开防护窗口、阻止读写敏感输入。
 *
 * 与上游的差别（有意为之）：
 * - 不拦截 `document.cookie` / `localStorage` / `navigator.credentials` 的访问器。
 *   上游那样做会让某些站点在特性检测阶段行为异常，收益（看到 cookie 值）又与
 *   「凭据一律不读」的边界冲突。这里只记录，不劫持。
 * - 不提供 `request`（自定义 HTTP 请求）：带登录态发任意请求的风险高于收益。
 *
 * 注入是幂等的：`__etaDevtools.installed` 为真时直接返回，重复导航不会叠加包装。
 */
internal object BrowserDevtoolsScripts {

    /** 注入运行时。返回 'already' / 'installed' / 'blocked'。 */
    fun install(): String =
        """
        (function () {
          try {
            if (window.__etaDevtools && window.__etaDevtools.installed) return 'already';
            var dt = window.__etaDevtools || {};
            dt.version = 1;
            dt.installed = false;
            dt.console = [];
            dt.net = [];
            dt.nextConsole = 1;
            dt.nextNet = 1;
            dt.consoleLimit = 200;
            dt.netLimit = 100;

            var SECRET_KEY = /(pass(word|wd)?|pwd|token|secret|api[-_]?key|authorization|cookie|session(id)?|credential|one[-_]?time[-_]?code|otp)/i;

            function limitText(value, max) {
              var text = String(value == null ? '' : value);
              return text.length > max ? text.slice(0, max) : text;
            }
            dt.limitText = limitText;

            function redactText(value) {
              var text = String(value == null ? '' : value);
              try {
                text = text.replace(/("(?:[^"\\\\]|\\\\.)*")\s*:\s*("(?:[^"\\\\]|\\\\.)*"|[^,}\]]+)/g,
                  function (match, key) {
                    return SECRET_KEY.test(key) ? key + ': "[REDACTED]"' : match;
                  });
                text = text.replace(/([?&;])([^=&#\s]+)=([^&#\s]*)/g, function (match, lead, key) {
                  return SECRET_KEY.test(key) ? lead + key + '=[REDACTED]' : match;
                });
                text = text.replace(/\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}\b/g, '[REDACTED_JWT]');
                text = text.replace(/\bBearer\s+[A-Za-z0-9._~+\/-]{12,}=*/gi, 'Bearer [REDACTED]');
                text = text.replace(/\b(access_token|refresh_token|id_token|sessionid|jsessionid|api[_-]?key|client_secret|x-api-key)\s*[:=]\s*[^\s&,;"']+/gi,
                  function (match, key) { return key + '=[REDACTED]'; });
              } catch (ignored) {}
              return text;
            }
            dt.redactText = redactText;

            function redactUrl(value) {
              var raw = String(value == null ? '' : value);
              try {
                var parsed = new URL(raw, location.href);
                var changed = false;
                parsed.searchParams.forEach(function (paramValue, paramKey) {
                  if (SECRET_KEY.test(paramKey)) {
                    parsed.searchParams.set(paramKey, '[REDACTED]');
                    changed = true;
                  }
                });
                if (parsed.password) {
                  parsed.password = '[REDACTED]';
                  changed = true;
                }
                return changed ? parsed.href : raw;
              } catch (error) {
                return redactText(raw);
              }
            }
            dt.redactUrl = redactUrl;

            function isSensitiveInput(node) {
              if (!node || !node.tagName) return false;
              var tag = String(node.tagName).toLowerCase();
              if (tag !== 'input' && tag !== 'textarea') return false;
              var type = String(node.type || '').toLowerCase();
              if (type === 'password' || type === 'hidden') return true;
              var autocomplete = String(node.getAttribute('autocomplete') || '').toLowerCase();
              if (autocomplete.indexOf('password') >= 0 || autocomplete.indexOf('one-time-code') >= 0) return true;
              var hint = String(node.getAttribute('name') || '') + ' ' + String(node.id || '');
              return SECRET_KEY.test(hint);
            }
            dt.isSensitiveInput = isSensitiveInput;

            dt.describe = function (value, depth) {
              var level = depth || 0;
              try {
                if (value === undefined) return 'undefined';
                if (value === null) return 'null';
                var kind = typeof value;
                if (kind === 'string') return limitText(redactText(value), 2000);
                if (kind === 'number' || kind === 'boolean') return String(value);
                if (kind === 'function') return 'function ' + (value.name || 'anonymous');
                if (kind === 'symbol') return String(value);
                if (value instanceof Error) {
                  return limitText(value.name + ': ' + redactText(String(value.message || '')), 2000);
                }
                if (typeof Node !== 'undefined' && value instanceof Node) {
                  if (value.nodeType === 1) {
                    var text = limitText(redactText(String(value.textContent || '')).replace(/\s+/g, ' ').trim(), 200);
                    return '<' + String(value.tagName || '').toLowerCase() + (value.id ? ' id="' + value.id + '"' : '') + '> ' + text;
                  }
                  return String(value.nodeName);
                }
                if (value instanceof Date) return value.toISOString();
                if (typeof Promise !== 'undefined' && value instanceof Promise) return '[Promise]';
                if (level > 3) return '[depth]';
                return limitText(redactText(JSON.stringify(value)), 2000);
              } catch (error) {
                return '[unserializable]';
              }
            };

            function pushConsole(level, args) {
              try {
                var parts = [];
                for (var index = 0; index < args.length; index++) parts.push(dt.describe(args[index], 1));
                dt.console.push({
                  id: dt.nextConsole++,
                  level: level,
                  text: limitText(redactText(parts.join(' ')), 2000),
                  time: Date.now()
                });
                while (dt.console.length > dt.consoleLimit) dt.console.shift();
              } catch (ignored) {}
            }

            ['log', 'info', 'warn', 'error', 'debug'].forEach(function (level) {
              try {
                var original = console[level];
                console[level] = function () {
                  pushConsole(level, arguments);
                  try { return original.apply(console, arguments); } catch (ignored) {}
                };
              } catch (ignored) {}
            });

            try {
              window.addEventListener('error', function (event) {
                pushConsole('error', ['Uncaught: ' + (event && event.message ? event.message : 'unknown')]);
              });
              window.addEventListener('unhandledrejection', function (event) {
                pushConsole('error', ['Unhandled rejection: ' + dt.describe(event && event.reason, 1)]);
              });
            } catch (ignored) {}

            function recordNet(entry) {
              try {
                dt.net.push(entry);
                while (dt.net.length > dt.netLimit) dt.net.shift();
              } catch (ignored) {}
            }

            try {
              var nativeFetch = window.fetch;
              if (typeof nativeFetch === 'function') {
                window.fetch = function (input, init) {
                  var url = typeof input === 'string' ? input : (input && input.url) || '';
                  var method = (init && init.method) || (input && input.method) || 'GET';
                  var id = dt.nextNet++;
                  var startedAt = Date.now();
                  try {
                    return nativeFetch.apply(this, arguments).then(function (response) {
                      recordNet({
                        id: id, url: limitText(redactUrl(url), 320), method: String(method).toUpperCase(),
                        status: response ? response.status : null, ms: Date.now() - startedAt, error: ''
                      });
                      return response;
                    }, function (error) {
                      recordNet({
                        id: id, url: limitText(redactUrl(url), 320), method: String(method).toUpperCase(),
                        status: null, ms: Date.now() - startedAt, error: limitText(String(error && error.message || error), 200)
                      });
                      throw error;
                    });
                  } catch (error) {
                    throw error;
                  }
                };
              }
            } catch (ignored) {}

            try {
              var XHR = window.XMLHttpRequest;
              if (XHR && XHR.prototype && XHR.prototype.open && XHR.prototype.send) {
                var nativeOpen = XHR.prototype.open;
                var nativeSend = XHR.prototype.send;
                XHR.prototype.open = function (method, url) {
                  this.__etaNet = {
                    id: dt.nextNet++, method: String(method || 'GET').toUpperCase(),
                    url: limitText(redactUrl(url), 320), startedAt: Date.now()
                  };
                  return nativeOpen.apply(this, arguments);
                };
                XHR.prototype.send = function () {
                  var self = this;
                  var meta = self.__etaNet;
                  if (meta) {
                    try {
                      self.addEventListener('loadend', function () {
                        recordNet({
                          id: meta.id, url: meta.url, method: meta.method,
                          status: self.status || null, ms: Date.now() - meta.startedAt, error: ''
                        });
                      });
                    } catch (ignored) {}
                  }
                  return nativeSend.apply(this, arguments);
                };
              }
            } catch (ignored) {}

            dt.installed = true;
            window.__etaDevtools = dt;
            return 'installed';
          } catch (error) {
            return 'blocked';
          }
        })();
        """.trimIndent()

    /** 读 console 缓冲。[sinceId] 为 0 表示从头取（受 limit 限制）。 */
    fun consoleRead(limit: Int, sinceId: Int): String =
        """
        var dt = window.__etaDevtools;
        if (!dt || !dt.installed) return { instrumented: false, entries: [], returned: 0, total: 0, more: false };
        var list = dt.console || [];
        var entries = [];
        for (var index = 0; index < list.length; index++) {
          var item = list[index];
          if (item.id <= $sinceId) continue;
          entries.push(item);
        }
        var total = entries.length;
        if (entries.length > $limit) entries = entries.slice(entries.length - $limit);
        return {
          instrumented: true,
          entries: entries,
          returned: entries.length,
          total: total,
          more: total > entries.length,
          last_id: entries.length ? entries[entries.length - 1].id : $sinceId
        };
        """.trimIndent()

    /** 读网络记录。 */
    fun networkRead(limit: Int, sinceId: Int): String =
        """
        var dt = window.__etaDevtools;
        if (!dt || !dt.installed) return { instrumented: false, entries: [], returned: 0, total: 0, more: false };
        var list = dt.net || [];
        var entries = [];
        for (var index = 0; index < list.length; index++) {
          var item = list[index];
          if (item.id <= $sinceId) continue;
          entries.push(item);
        }
        var total = entries.length;
        if (entries.length > $limit) entries = entries.slice(entries.length - $limit);
        return {
          instrumented: true,
          entries: entries,
          returned: entries.length,
          total: total,
          more: total > entries.length,
          last_id: entries.length ? entries[entries.length - 1].id : $sinceId
        };
        """.trimIndent()

    /**
     * DOM 快照：只给结构（tag / id / class / 文本摘要），不给表单值。
     * 与 browser_use 的 get_readable 分工：那个给可读正文，这个给渲染后的结构。
     */
    fun domSnapshot(selector: String?, maxNodes: Int): String {
        val selectorLiteral = selector?.let(JSONObject::quote) ?: "null"
        return """
        var selector = $selectorLiteral;
        var dt = window.__etaDevtools || {};
        var redactUrl = dt.redactUrl || function (value) { return String(value == null ? '' : value); };
        var redactText = dt.redactText || function (value) { return String(value == null ? '' : value); };
        var limitText = dt.limitText || function (value, max) {
          var text = String(value == null ? '' : value);
          return text.length > max ? text.slice(0, max) : text;
        };
        function cleanInline(value, max) {
          return limitText(String(value == null ? '' : value).replace(/\s+/g, ' ').trim(), max);
        }
        var root = selector ? document.querySelector(selector) : document.body;
        if (!root) return { found: false, nodes: [], count: 0, truncated: false };
        var out = [];
        var truncated = false;
        function walk(node, depth) {
          if (out.length >= $maxNodes) { truncated = true; return; }
          if (!node || node.nodeType !== 1) return;
          var tag = String(node.tagName || '').toLowerCase();
          if (tag === 'script' || tag === 'style' || tag === 'noscript') return;
          var rect = node.getBoundingClientRect();
          var entry = {
            tag: tag,
            depth: depth,
            id: node.id ? cleanInline(node.id, 80) : null,
            cls: node.className && typeof node.className === 'string' ? cleanInline(node.className, 120) : null,
            text: limitText(redactText(String(node.childElementCount ? '' : node.textContent || '')).replace(/\s+/g, ' ').trim(), 160),
            visible: rect.width > 0 && rect.height > 0
          };
          if (tag === 'input' || tag === 'textarea' || tag === 'select') {
            entry.type = cleanInline(node.type || tag, 32);
            // 表单值一律不回读，只说明是不是敏感输入
            entry.sensitive = window.__etaDevtools && window.__etaDevtools.isSensitiveInput
              ? window.__etaDevtools.isSensitiveInput(node) : false;
          }
          if (tag === 'a' && node.href) entry.href = limitText(redactUrl(node.href), 240);
          if (tag === 'img' && node.src) entry.src = limitText(redactUrl(node.src), 240);
          out.push(entry);
          for (var index = 0; index < node.children.length; index++) walk(node.children[index], depth + 1);
        }
        walk(root, 0);
        return { found: true, nodes: out, count: out.length, truncated: truncated };
        """.trimIndent()
    }

    /** 按 selector 查元素，返回截图式列表（不含表单值）。 */
    fun queryAll(selector: String?, limit: Int): String {
        val selectorLiteral = selector?.let(JSONObject::quote) ?: "\"a,button,input,textarea,select,[role]\""
        return """
        var selector = $selectorLiteral;
        var dt = window.__etaDevtools || {};
        var redactText = dt.redactText || function (value) { return String(value == null ? '' : value); };
        var limitText = dt.limitText || function (value, max) {
          var text = String(value == null ? '' : value);
          return text.length > max ? text.slice(0, max) : text;
        };
        function cleanInline(value, max) {
          return limitText(String(value == null ? '' : value).replace(/\s+/g, ' ').trim(), max);
        }
        var nodes = [];
        try { nodes = Array.prototype.slice.call(document.querySelectorAll(selector)); } catch (error) {
          return { ok: false, code: 'INVALID_SELECTOR', nodes: [], count: 0 };
        }
        var out = [];
        for (var index = 0; index < nodes.length && out.length < $limit; index++) {
          var node = nodes[index];
          var rect = node.getBoundingClientRect();
          out.push({
            tag: String(node.tagName || '').toLowerCase(),
            id: node.id ? cleanInline(node.id, 80) : null,
            cls: node.className && typeof node.className === 'string' ? cleanInline(node.className, 120) : null,
            text: limitText(redactText(String(node.textContent || '')).replace(/\s+/g, ' ').trim(), 120),
            visible: rect.width > 0 && rect.height > 0,
            top: Math.round(rect.top),
            left: Math.round(rect.left),
            disabled: !!node.disabled,
            sensitive: window.__etaDevtools && window.__etaDevtools.isSensitiveInput
              ? window.__etaDevtools.isSensitiveInput(node) : false
          });
        }
        return { ok: true, nodes: out, count: out.length, total: nodes.length, truncated: nodes.length > out.length };
        """.trimIndent()
    }

    /**
     * 启动一次 eval：异步执行、把结果写进 `__etaDevtoolsEvalBox`，由 Kotlin 侧轮询取。
     * 执行期间打开 `__etaDevtoolsGuard`，敏感输入的读写会被拦下。
     *
     * 代码通过 [JSONObject.quote] 转义后内联，不经过字符串拼接暴露引号问题。
     */
    fun evalStart(code: String, guardMs: Int): String {
        val codeLiteral = JSONObject.quote(code)
        return """
        var code = $codeLiteral;
        var box = { done: false, ok: false, value: null, type: 'undefined', error: '', truncated: false };
        window.__etaDevtoolsEvalBox = box;
        var dt = window.__etaDevtools;
        var redact = dt && dt.redactText ? dt.redactText : function (value) { return String(value == null ? '' : value); };
        var guardTimer = setTimeout(function () { window.__etaDevtoolsGuard = false; }, $guardMs);
        window.__etaDevtoolsGuard = true;

        function finish(ok, value, type, error, truncated) {
          box.done = true;
          box.ok = ok;
          box.value = value;
          box.type = type;
          box.error = error;
          box.truncated = truncated;
          try { clearTimeout(guardTimer); } catch (ignored) {}
          window.__etaDevtoolsGuard = false;
        }

        function describe(value, depth) {
          try {
            if (value === undefined) return { value: null, type: 'undefined' };
            if (value === null) return { value: null, type: 'null' };
            var kind = typeof value;
            if (kind === 'string') return { value: redact(value).slice(0, 2000), type: 'string' };
            if (kind === 'number' || kind === 'boolean') return { value: value, type: kind };
            if (kind === 'bigint') return { value: String(value), type: 'bigint' };
            if (kind === 'function') return { value: 'function ' + (value.name || 'anonymous'), type: 'function' };
            if (value instanceof Error) {
              return { value: { name: value.name, message: redact(String(value.message || '')).slice(0, 600),
                stack: redact(String(value.stack || '')).slice(0, 800) }, type: 'error' };
            }
            if (typeof Node !== 'undefined' && value instanceof Node) {
              if (value.nodeType === 1) {
                var text = redact(String(value.textContent || '')).replace(/\s+/g, ' ').trim().slice(0, 200);
                return { value: '<' + String(value.tagName || '').toLowerCase() + '> ' + text, type: 'element' };
              }
              return { value: String(value.nodeName), type: 'node' };
            }
            if (value instanceof Date) return { value: value.toISOString(), type: 'date' };
            if (typeof Promise !== 'undefined' && value instanceof Promise) return { value: '[Promise]', type: 'promise' };
            if (depth > 3) return { value: '[' + kind + ']', type: kind };
            var text = JSON.stringify(value);
            var truncated = false;
            if (text && text.length > 4000) { text = text.slice(0, 4000); truncated = true; }
            return { value: redact(text), type: kind, truncated: truncated };
          } catch (error) {
            return { value: '[unserializable]', type: 'unknown' };
          }
        }

        try {
          var outcome = (0, eval)(code);
          if (outcome && typeof outcome.then === 'function') {
            outcome.then(function (resolved) {
              var described = describe(resolved, 0);
              finish(true, described.value, described.type, '', !!described.truncated);
            }, function (rejected) {
              finish(false, null, 'error', redact(String(rejected && rejected.message || rejected)).slice(0, 600), false);
            });
          } else {
            var describedSync = describe(outcome, 0);
            finish(true, describedSync.value, describedSync.type, '', !!describedSync.truncated);
          }
        } catch (error) {
          finish(false, null, 'error', redact(String(error && error.message || error)).slice(0, 600), false);
        }
        return { started: true };
        """.trimIndent()
    }

    /** 轮询 eval 结果。 */
    fun evalPoll(): String =
        """
        var box = window.__etaDevtoolsEvalBox;
        if (!box) return { started: false };
        return {
          started: true,
          done: !!box.done,
          ok: !!box.ok,
          value: box.value === undefined ? null : box.value,
          type: box.type || 'undefined',
          error: box.error || '',
          truncated: !!box.truncated
        };
        """.trimIndent()

    /** 只读执行，不打开防护窗口：用于内部探测（当前是否已注入）。 */
    fun probe(): String =
        """
        var dt = window.__etaDevtools;
        return {
          instrumented: !!(dt && dt.installed),
          console_count: dt && dt.console ? dt.console.length : 0,
          net_count: dt && dt.net ? dt.net.length : 0
        };
        """.trimIndent()
}
