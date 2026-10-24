/*
 * 抖音 a_bogus 的最小浏览器环境垫片。
 *
 * 这份文件是我们自己写的，不是从任何参考项目里取的。它只服务一个目的：
 * 让同目录下的 utils.js / sm3.js / vm_decode.js（Apache-2.0，见 NOTICE.txt）
 * 在没有浏览器的 QuickJS 里跑起来。
 *
 * 三条原则：
 * 1. 只补算法真正读的东西，不铺一整套 DOM——读不到的项宁可留空，也不要造一个假得
 *    能被服务端识别出来的值；
 * 2. 所有数值要和 DouyinClient 发出去的参数表一致（UA / 屏幕 / browser_version），
 *    a_bogus 签的是「服务端看到的那份查询串」，环境值和参数表对不上就是自找风控；
 * 3. 时间戳走真实 Date，不做缓存——a_bogus 本身带时间，缓存了就成了固定串。
 */
(function () {
    "use strict";

    // 和 DouyinClient.UA、参数表里的 browser_version=147.0.0.0 三处必须一致
    var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";

    var g = (function () {
        try {
            return (new Function("return this"))();
        } catch (e) {
            return typeof globalThis !== "undefined" ? globalThis : this;
        }
    })();

    g.__dyStart = Date.now();

    // ---------------------------------------------------------------- 基础工具

    if (typeof g.console !== "object" || g.console === null) {
        g.console = {};
    }
    // 算法里 console.log 的点很多（有些还带整个 U 数组），QuickJS 的 console 是可选注入的
    var noop = function () { };
    ["log", "debug", "info", "warn", "error", "trace"].forEach(function (name) {
        if (typeof g.console[name] !== "function") {
            g.console[name] = noop;
        }
    });

    var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /** QuickJS 没有 btoa/atob，算法内部会用到 */
    g.btoa = function (input) {
        var str = String(input), out = "";
        for (var i = 0, code, next, c; i < str.length;) {
            code = str.charCodeAt(i++);
            next = i < str.length ? str.charCodeAt(i) : NaN;
            i++;
            c = (NaN === next ? (code >> 4) : ((code & 15) << 4) | (next >> 8));
            out += B64.charAt(c);
            if (code > 255) {
                throw new Error("btoa：只接受 latin-1 字符");
            }
        }
        return out;
    };

    g.atob = function (input) {
        var str = String(input).replace(/=+$/, ""), out = "", bits = 0, chars = 0, buffer = 0;
        for (var i = 0, c; i < str.length; i++) {
            c = B64.indexOf(str.charAt(i));
            if (c === -1) {
                throw new Error("atob：非法字符");
            }
            buffer = (buffer << 6) | c;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out += String.fromCharCode((buffer >> bits) & 255);
                buffer &= (1 << bits) - 1;
            }
            chars++;
        }
        return out;
    };

    if (typeof g.globalThis === "undefined") {
        g.globalThis = g;
    }

    // ---------------------------------------------------------------- window / screen

    // window 直接指向全局：算法里既有 window.innerWidth 也有的地方按裸标识符读，
    // 同一个对象两处都能命中，省一层代理
    g.window = g;
    g.self = g;
    g.innerWidth = 2048;
    g.innerHeight = 960;
    g.outerWidth = 2554;
    g.outerHeight = 1386;
    g.devicePixelRatio = 1;
    g.scrollX = 0;
    g.scrollY = 0;

    // 数值取自 utils.js 里的 innerScreen 那份写死值：算法会拿它们做「是不是 800/600」这类
    // 反常检测，刻意避开那些被标记的取值
    g.screen = {
        availWidth: 2560,
        availHeight: 1392,
        width: 2560,
        height: 1440,
        sizeWidth: 2560,
        sizeHeight: 1440,
        innerWidth: 2048,
        innerHeight: 960,
        outerWidth: 2554,
        outerHeight: 1386,
        colorDepth: 24,
        pixelDepth: 24,
        platform: "Win32",
        orientation: { type: "landscape-primary", angle: 0 }
    };

    // ---------------------------------------------------------------- navigator

    g.navigator = {
        userAgent: UA,
        appCodeName: "Mozilla",
        appName: "Netscape",
        appVersion: "5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
        platform: "Win32",
        vendor: "Google Inc.",
        vendorSub: "",
        // utils.js 会往这里挂 vendorSubs = {ink:…}，所以必须是可扩展对象
        language: "zh-CN",
        languages: ["zh-CN", "zh", "en"],
        hardwareConcurrency: 8,
        deviceMemory: 8,
        maxTouchPoints: 0,
        product: "Chrome",
        productSub: "20030107",
        onLine: true,
        doNotTrack: null,
        cookieEnabled: true,
        // 算法注释里点名要匹配这几项（原样拼写，包括它们上游写错的 pemrissions）
        pemrissions: { microphone: "granted", camera: "granted", geolocation: "prompt" },
        ink: {
            // 浏览器里是异步的；这里只被用来判断「存在且可调用」，回一个固定 resolved 结构
            getAutoURLJoinMetadataAsync: function () {
                return { joinType: 0,uma: 0 };
            }
        }
    };

    // ---------------------------------------------------------------- location

    var location = {
        href: "https://www.douyin.com/",
        origin: "https://www.douyin.com",
        protocol: "https:",
        host: "www.douyin.com",
        hostname: "www.douyin.com",
        port: "",
        pathname: "/",
        search: "",
        hash: "",
        reload: noop,
        replace: noop,
        assign: noop,
        toString: function () {
            return this.href;
        }
    };
    g.location = location;
    g.document = {
        URL: location.href,
        documentURI: location.href,
        referrer: "https://www.douyin.com/",
        title: "",
        cookie: "",
        characterSet: "UTF-8",
        readyState: "complete",
        visibilityState: "visible",
        hidden: false,
        compatMode: "CSS1Compat",
        // 算法只 createElement 出来读属性/挂事件，不真的排版，所以返回普通对象即可
        createElement: function (tag) {
            return {
                tagName: String(tag).toUpperCase(),
                style: {},
                dataset: {},
                setAttribute: noop,
                getAttribute: function () {
                    return null;
                },
                appendChild: noop,
                removeChild: noop,
                addEventListener: noop,
                removeEventListener: noop
            };
        },
        getElementById: function () {
            return null;
        },
        querySelector: function () {
            return null;
        },
        querySelectorAll: function () {
            return [];
        },
        getElementsByTagName: function () {
            return [];
        },
        getElementsByClassName: function () {
            return [];
        },
        addEventListener: noop,
        removeEventListener: noop,
        write: noop,
        writeln: noop
        // head/body/documentElement 这些在浏览器里是对象；不给就是 null，
        // 算法里凡是依赖它们的分支都属于「环境自检」，我们把 EnvTestTurnOn 关掉了
    };

    g.history = {
        length: 2,
        state: null,
        pushState: noop,
        replaceState: noop,
        back: noop,
        forward: noop,
        go: noop
    };

    // ---------------------------------------------------------------- performance

    // 只在 PerformanceTestTurnOn 为真时用，仍然给全：缺了就是 ReferenceError
    g.performance = {
        timing: {
            navigationStart: g.__dyStart,
            domLoading: g.__dyStart + 40,
            domInteractive: g.__dyStart + 180,
            domContentLoadedEventStart: g.__dyStart + 190,
            domContentLoadedEventEnd: g.__dyStart + 195,
            domComplete: g.__dyStart + 260,
            loadEventStart: g.__dyStart + 265,
            loadEventEnd: g.__dyStart + 266
        },
        navigation: { type: 0, redirectCount: 0 },
        memory: { usedJSHeapSize: 24000000, totalJSHeapSize: 32000000, jsHeapSizeLimit: 4100000000 },
        now: function () {
            return Date.now() - g.__dyStart;
        },
        mark: noop,
        measure: noop,
        getEntries: function () {
            return [];
        },
        getEntriesByName: function () {
            return [];
        },
        getEntriesByType: function () {
            return [];
        },
        clearMarks: noop,
        clearMeasures: noop
    };

    // ---------------------------------------------------------------- 其余常被探测的对象
    // 都是「存在性 + 简单读写」级别的桩：这些接口的签名材料里并不含它们的内容，
    // 缺了会抛，值不对不会被服务端区分，所以给便宜的实现就好

    var storageStub = function () {
        var map = {};
        return {
            getItem: function (k) {
                return Object.prototype.hasOwnProperty.call(map, k) ? map[k] : null;
            },
            setItem: function (k, v) {
                map[k] = String(v);
            },
            removeItem: function (k) {
                delete map[k];
            },
            clear: function () {
                map = {};
            },
            key: function (i) {
                return Object.keys(map)[i] || null;
            },
            get length() {
                return Object.keys(map).length;
            }
        };
    };
    g.localStorage = storageStub();
    g.sessionStorage = storageStub();
    g.indexedDB = { open: function () { return {}; } };
    g.openDatabase = function () { return null; };
    g.webkitStorageInfo = { quota: 0, usage: 0 };

    // 算法要的只是「能同步跑掉回调」，不真的靠定时器驱动流程
    g.setTimeout = function (fn, ms) {
        if (typeof fn === "function") {
            try { fn(); } catch (e) { /* 定时器里的异常不该中断取签 */ }
        }
        return 0;
    };
    g.setInterval = function () { return 0; };
    g.clearTimeout = noop;
    g.clearInterval = noop;
    g.requestAnimationFrame = function (fn) {
        if (typeof fn === "function") {
            fn(g.performance.now());
        }
        return 0;
    };
    g.cancelAnimationFrame = noop;

    /**
     * XMLHttpRequest 只做「构造 + 方法齐全」的桩。
     *
     * <p>垫片阶段不给真网络：取签是纯计算，一旦允许它自己发请求，
     * 就等于在 UI 线程外多开一条没有超时的路，出问题很难查。
     */
    var xhrStub = function () {
        this.readyState = 0;
        this.status = 0;
        this.responseText = "";
        this.response = "";
        this.withCredentials = false;
        this.timeout = 0;
        this.upload = { addEventListener: noop };
    };
    xhrStub.prototype.open = function () { this.__url = arguments[1]; };
    xhrStub.prototype.send = noop;
    xhrStub.prototype.abort = noop;
    xhrStub.prototype.setRequestHeader = noop;
    xhrStub.prototype.getAllResponseHeaders = function () { return ""; };
    xhrStub.prototype.getResponseHeader = function () { return null; };
    xhrStub.prototype.addEventListener = noop;
    xhrStub.prototype.removeEventListener = noop;
    xhrStub.UNSENT = 0;
    xhrStub.OPENED = 1;
    g.XMLHttpRequest = xhrStub;

    g.fetch = function () {
        return {
            then: function () { return this; },
            catch: function () { return this; }
        };
    };
    g.WebSocket = function () {
        this.send = noop;
        this.close = noop;
        this.addEventListener = noop;
    };
    g.Event = function (type) { this.type = type; };
    g.CustomEvent = function (type, init) {
        this.type = type;
        this.detail = init && init.detail;
    };
    g.addEventListener = noop;
    g.removeEventListener = noop;
    g.dispatchCustomEvent = function () { return true; };

    // 安全 SDK 的几个全局钩子：网页里由 bdms 注入，这里只保证「读它不炸」
    g.byted_acrawler = g.byted_acrawler || {
        init: noop,
        frontier: noop,
        sign: function () { return ""; },
        __ac_signature: function () { return ""; },
        getPassPortDeviceId: function () { return ""; },
        setConfig: noop
    };
    g.bdms = g.bdms || { init: { _v: [] } };
    g.Captcha = function () { };
    g.TTGCaptcha = function () { };

    /** crypto 只有 getRandomValues 会被用到（随机填充那些数组） */
    if (typeof g.crypto !== "object" || g.crypto === null) {
        g.crypto = {};
    }
    if (typeof g.crypto.getRandomValues !== "function") {
        g.crypto.getRandomValues = function (typed) {
            if (typed && typeof typed.length === "number") {
                for (var i = 0; i < typed.length; i++) {
                    typed[i] = Math.floor(Math.random() * 256);
                }
            }
            return typed;
        };
    }
    if (typeof g.msCrypto === "undefined") {
        g.msCrypto = g.crypto;
    }

    // ---------------------------------------------------------------- 入口

    /**
     * 关掉上游的两个开关。
     *
     * <p>{@code programVersion} 默认是 DEBUG，会把整个 U 数组和每一步都 console 出来；
     * {@code EnvTestTurnOn} 要的是真浏览器的自检结果，我们本来就不可能通过。
     * 这两个必须在 utils.js 加载完之后调用——那之前它们还不是全局变量。
     */
    g.dyPrepare = function (agent) {
        programVersion = G_RELEASE;
        EnvTestTurnOn = false;
        PerformanceTestTurnOn = false;
        if (agent) {
            navigator.userAgent = agent;
        }
    };

    /**
     * 给一条查询串算 a_bogus。
     *
     * @param {string} uri 服务端将看到的那份查询串原文（不带前导 ?，也不做 URL 编码）
     * @returns {string} a_bogus；只含 A-Za-z0-9+/=，拼进查询串前要自己编码
     */
    g.dyGetABogus = function (uri) {
        return makeABogus(String(uri || ""), 0);
    };
})();
