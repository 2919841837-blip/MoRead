/*
 * 墨知伴读「互动回答」运行时。
 *
 * 模型写的界面经 GenUiCompiler 编译成一个函数，在这里求值：函数返回各个互动岛的元素树，
 * 运行时把它序列化成 JSON 交给 Compose 渲染。引擎里没有任何 I/O 绑定（没有网络、存储、
 * 定时器、DOM），程序唯一能做的就是「计算并交出一棵树」。对外动作（发消息、复制、跳转……）
 * 只排进队列由宿主校验执行，而且只在用户手势触发的事件处理期间才收。
 *
 * 一条消息一个槽（slot）。状态按编译期注入的键保存在槽里，所以流式期间每次重编译、
 * 或重开页面从持久化状态恢复时，用户已经拖过的滑块不会跳回初值。
 */
(function (global) {
  'use strict';

  var MAX_NODES = 1500;
  var MAX_DEPTH = 32;
  var MAX_TEXT = 30000;
  var MAX_ERRORS = 8;
  var MAX_RERENDERS = 3;

  var slots = Object.create(null);
  var current = null;
  var inEvent = false;
  var actions = [];
  var errors = [];
  var appData = {};

  function record(code, detail) {
    if (errors.length < MAX_ERRORS) errors.push({ code: code, detail: detail ? String(detail).slice(0, 160) : null });
  }

  function errorText(e) {
    if (e && typeof e === 'object' && 'message' in e) return (e.name || 'Error') + ': ' + e.message;
    return String(e);
  }

  function __safe(evaluate, fallback) {
    try {
      return evaluate();
    } catch (e) {
      record('expression_error', errorText(e));
      return fallback;
    }
  }

  function clone(value) {
    if (value === undefined) return undefined;
    try {
      return JSON.parse(JSON.stringify(value));
    } catch (e) {
      return undefined;
    }
  }

  function slotOf(id) {
    var s = slots[id];
    if (!s) {
      s = slots[id] = {
        program: null,
        state: Object.create(null),
        initial: Object.create(null),
        setters: Object.create(null),
        refs: Object.create(null),
        memos: Object.create(null),
        fns: Object.create(null),
        hookIndex: 0,
        dirty: false,
        lastTree: [],
      };
    }
    return s;
  }

  function hookKey(opts, kind) {
    if (opts && typeof opts.key === 'string' && opts.key) return opts.key;
    return '#' + kind + (current.hookIndex++);
  }

  function useState(initial, opts) {
    var s = current;
    if (!s) throw new Error('useState outside render');
    var key = hookKey(opts, 's');
    if (!(key in s.state)) {
      s.state[key] = typeof initial === 'function' ? __safe(initial, undefined) : initial;
    }
    if (!(key in s.initial)) s.initial[key] = clone(typeof initial === 'function' ? s.state[key] : initial);
    var setter = s.setters[key];
    if (!setter) {
      setter = s.setters[key] = function (next) {
        var prev = s.state[key];
        var value = typeof next === 'function' ? next(prev) : next;
        if (!Object.is(prev, value)) {
          s.state[key] = value;
          s.dirty = true;
        }
      };
    }
    return [s.state[key], setter];
  }

  function useRef(initial, opts) {
    var s = current;
    var key = hookKey(opts, 'r');
    if (!(key in s.refs)) s.refs[key] = { current: initial };
    return s.refs[key];
  }

  function sameDeps(a, b) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) if (!Object.is(a[i], b[i])) return false;
    return true;
  }

  function useMemo(factory, deps, opts) {
    var s = current;
    var key = hookKey(opts, 'm');
    var prev = s.memos[key];
    if (prev && sameDeps(prev.deps, deps)) return prev.value;
    var value = __safe(factory, undefined);
    s.memos[key] = { deps: deps, value: value };
    return value;
  }

  function useCallback(fn, deps, opts) {
    return useMemo(function () { return fn; }, deps, opts);
  }

  /** 元素：{t, p, c}。children 拍平，null/false/undefined/true 丢弃，原始值变文字节点。 */
  function h(type, props) {
    var children = [];
    for (var i = 2; i < arguments.length; i++) pushChild(children, arguments[i]);
    return { t: type, p: props || {}, c: children };
  }

  function pushChild(out, child) {
    if (child === null || child === undefined || child === false || child === true) return;
    if (Array.isArray(child)) {
      for (var i = 0; i < child.length; i++) pushChild(out, child[i]);
      return;
    }
    if (typeof child === 'object' && typeof child.t === 'string') {
      out.push(child);
      return;
    }
    if (typeof child === 'function' || typeof child === 'symbol') return;
    var text = typeof child === 'object' ? safeJson(child) : String(child);
    var last = out[out.length - 1];
    if (last && last.t === '#text') last.v += text;
    else out.push({ t: '#text', v: text });
  }

  function safeJson(value) {
    try {
      return JSON.stringify(value);
    } catch (e) {
      return String(value);
    }
  }

  /** {#each}：每一项包成带键片段，键默认是下标，元素上写了 key 属性时序列化阶段优先用它。 */
  function __each(list, render, key) {
    var out = [];
    var items = list;
    if (items && typeof items === 'object' && !Array.isArray(items)) {
      if (typeof items.length === 'number') items = Array.prototype.slice.call(items);
      else items = Object.keys(items).map(function (k) { return items[k]; });
    }
    if (!Array.isArray(items)) return out;
    var limit = Math.min(items.length, 400);
    for (var i = 0; i < limit; i++) {
      var child;
      try {
        child = render(items[i], i);
      } catch (e) {
        record('each_item_error', errorText(e));
        continue;
      }
      out.push({ t: '#frag', p: {}, k: key + '#' + i, c: flatten(child) });
    }
    if (items.length > limit) record('each_truncated', String(items.length));
    return out;
  }

  function flatten(child) {
    var out = [];
    pushChild(out, child);
    return out;
  }

  var ACTION_TYPES = { send: 1, copy: 1, jump: 1, speak: 1, openUrl: 1, saveNote: 1 };

  function act(type, payload) {
    if (!inEvent) {
      record('action_outside_event', type);
      return;
    }
    if (actions.length >= 1) {
      record('action_limit', type);
      return;
    }
    if (!ACTION_TYPES[type]) return;
    actions.push({ type: type, payload: clone(payload) || {} });
  }

  function makeApp() {
    var data = appData || {};
    return Object.freeze({
      book: data.book || null,
      persona: data.persona || null,
      theme: data.theme || { dark: false },
      results: data.results || {},
      send: function (text) { act('send', { text: String(text == null ? '' : text) }); },
      copy: function (text) { act('copy', { text: String(text == null ? '' : text) }); },
      jump: function (target) { act('jump', target && typeof target === 'object' ? target : { quote: String(target) }); },
      speak: function (text, opts) { act('speak', { text: String(text == null ? '' : text), speaker: opts && opts.speaker ? String(opts.speaker) : null }); },
      openUrl: function (url) { act('openUrl', { url: String(url == null ? '' : url) }); },
      saveNote: function (text) { act('saveNote', { text: String(text == null ? '' : text) }); },
    });
  }

  var DIL_ALIAS = Object.freeze({ useState: useState, useRef: useRef, useMemo: useMemo, useCallback: useCallback });

  function genUiAlias(app) {
    return Object.freeze({ issueNewTurn: app.send, copy: app.copy, openUrl: app.openUrl });
  }

  /* ---------- 序列化：元素树 → 纯 JSON；函数换成稳定编号，本体留在引擎里 ---------- */

  function serialize(s, roots) {
    var budget = { nodes: 0, text: 0 };
    s.fns = Object.create(null);
    var out = [];
    for (var i = 0; i < roots.length; i++) {
      var island = roots[i];
      var node = { t: '#island', k: 'i' + i, c: [] };
      var kids = flatten(island);
      for (var j = 0; j < kids.length; j++) {
        var n = serializeNode(s, kids[j], node.k, j, 1, budget);
        if (n) node.c.push(n);
      }
      out.push(node);
    }
    if (budget.nodes >= MAX_NODES) record('node_limit', String(budget.nodes));
    return out;
  }

  function serializeNode(s, node, parentPath, index, depth, budget) {
    if (budget.nodes >= MAX_NODES) return null;
    if (depth > MAX_DEPTH) {
      record('depth_limit', null);
      return null;
    }
    budget.nodes++;
    if (node.t === '#text') {
      var v = node.v;
      if (budget.text + v.length > MAX_TEXT) {
        v = v.slice(0, Math.max(0, MAX_TEXT - budget.text));
        record('text_limit', null);
      }
      budget.text += v.length;
      return { t: '#text', v: v };
    }
    var props = node.p || {};
    var local;
    if (node.t === '#frag') local = node.k;
    else if (props.key !== undefined && props.key !== null) local = 'k:' + String(props.key);
    else if (typeof props.__k === 'string') local = props.__k;
    else local = 'n' + index;
    var path = parentPath + '/' + local;
    var out = { t: node.t, k: path };
    var p = {};
    var hasProps = false;
    for (var name in props) {
      if (name === '__k' || name === 'key' || name === 'children') continue;
      var value = props[name];
      if (typeof value === 'function') {
        var id = path + '.' + name;
        s.fns[id] = value;
        p[name] = { __fn: id };
        hasProps = true;
      } else if (value !== undefined) {
        var plain = plainValue(value, 0);
        if (plain !== undefined) {
          p[name] = plain;
          hasProps = true;
        }
      }
    }
    if (hasProps) out.p = p;
    var kids = node.c || [];
    if (kids.length) {
      out.c = [];
      for (var i = 0; i < kids.length; i++) {
        var child = serializeNode(s, kids[i], path, i, depth + 1, budget);
        if (child) out.c.push(child);
      }
    }
    return out;
  }

  /** 属性值转纯 JSON：嵌套元素（宿主组件的 fallback 之类）与函数丢弃，深度受限。 */
  function plainValue(value, depth) {
    if (value === null) return null;
    var type = typeof value;
    if (type === 'string' || type === 'boolean') return value;
    if (type === 'number') return isFinite(value) ? value : null;
    if (type === 'function' || type === 'symbol' || type === 'undefined') return undefined;
    if (depth > 8) return undefined;
    if (Array.isArray(value)) {
      var arr = [];
      for (var i = 0; i < value.length && i < 2000; i++) {
        var item = plainValue(value[i], depth + 1);
        arr.push(item === undefined ? null : item);
      }
      return arr;
    }
    if (value instanceof Date) return value.toISOString();
    var obj = {};
    for (var key in value) {
      if (!Object.prototype.hasOwnProperty.call(value, key)) continue;
      var v = plainValue(value[key], depth + 1);
      if (v !== undefined) obj[key] = v;
    }
    return obj;
  }

  function stateSnapshot(s) {
    var out = {};
    for (var key in s.state) {
      if (key.charAt(0) === '#') continue;
      var v = plainValue(s.state[key], 0);
      if (v !== undefined) out[key] = v;
    }
    return out;
  }

  function initialSnapshot(s) {
    var out = {};
    for (var key in s.initial) {
      if (key.charAt(0) === '#') continue;
      var v = plainValue(s.initial[key], 0);
      if (v !== undefined) out[key] = v;
    }
    return out;
  }

  /* ---------- 渲染 ---------- */

  function renderSlot(s) {
    if (!s.program) return s.lastTree;
    var app = makeApp();
    var genUi = genUiAlias(app);
    var tree = s.lastTree;
    for (var pass = 0; pass <= MAX_RERENDERS; pass++) {
      current = s;
      s.hookIndex = 0;
      s.dirty = false;
      try {
        var roots = s.program(h, useState, useMemo, useRef, useCallback, __each, __safe, app, DIL_ALIAS, genUi);
        tree = serialize(s, Array.isArray(roots) ? roots : [roots]);
        s.lastTree = tree;
      } catch (e) {
        record('render_failed', errorText(e));
        current = null;
        return s.lastTree;
      }
      current = null;
      if (!s.dirty) break;
      if (pass === MAX_RERENDERS) record('render_loop', null);
    }
    return tree;
  }

  function result(s, tree) {
    var out = JSON.stringify({
      tree: tree,
      state: stateSnapshot(s),
      initial: initialSnapshot(s),
      actions: actions,
      errors: errors,
    });
    actions = [];
    errors = [];
    return out;
  }

  /**
   * 每次 API 调用前复位：看门狗中断在 QuickJS 里不可捕获，也不跑 finally，
   * 上一次被打断的调用可能留下 inEvent=true 或半截队列。
   */
  function begin() {
    inEvent = false;
    current = null;
    actions = [];
    errors = [];
  }

  var api = {
    /** 恢复持久化状态：在 define 之前调用，useState 会优先取这里的值。 */
    restore: function (id, state) {
      begin();
      var s = slotOf(id);
      if (state && typeof state === 'object') {
        for (var key in state) {
          if (Object.prototype.hasOwnProperty.call(state, key)) s.state[key] = state[key];
        }
      }
    },
    define: function (id, program) {
      begin();
      var s = slotOf(id);
      s.program = typeof program === 'function' ? program : null;
      s.memos = Object.create(null);
    },
    render: function (id) {
      var s = slotOf(id);
      return result(s, renderSlot(s));
    },
    invoke: function (id, fnId, args) {
      begin();
      var s = slotOf(id);
      var fn = s.fns[fnId];
      if (typeof fn !== 'function') {
        record('unknown_handler', fnId);
        return result(s, s.lastTree);
      }
      inEvent = true;
      try {
        fn.apply(null, Array.isArray(args) ? args : []);
      } catch (e) {
        record('handler_error', errorText(e));
      } finally {
        inEvent = false;
      }
      var tree = s.dirty ? renderSlot(s) : s.lastTree;
      return result(s, tree);
    },
    setAppData: function (data) {
      appData = data && typeof data === 'object' ? data : {};
    },
    dispose: function (id) {
      delete slots[id];
    },
    has: function (id) {
      return !!(slots[id] && slots[id].program);
    },
  };

  Object.defineProperty(global, '__genui', { value: Object.freeze(api), writable: false, configurable: false });

  /* ---------- 锁定：动态求值关掉，内置原型冻结，槽之间不能互相污染 ---------- */
  try {
    Object.defineProperty(global, 'eval', { value: undefined, writable: false, configurable: false });
  } catch (e) { /* ignore */ }
  try {
    var FunctionCtor = (function () {}).constructor;
    Object.defineProperty(FunctionCtor.prototype, 'constructor', { value: undefined, writable: false, configurable: false });
    Object.defineProperty(global, 'Function', { value: undefined, writable: false, configurable: false });
  } catch (e) { /* ignore */ }
  [Object, Array, String, Number, Boolean, Math, JSON, Date, RegExp, Map, Set, Promise, Symbol].forEach(function (ctor) {
    try {
      Object.freeze(ctor);
      if (ctor.prototype) Object.freeze(ctor.prototype);
    } catch (e) { /* ignore */ }
  });
  try {
    Object.freeze((function () {}).constructor.prototype);
  } catch (e) { /* ignore */ }
})(globalThis);
