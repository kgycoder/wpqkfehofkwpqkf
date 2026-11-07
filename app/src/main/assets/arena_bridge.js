(function () {
  if (window.__arena) return;

  var MENU_SELECTOR = "#chat-area > div.md\\:absolute.md\\:inset-x-0.md\\:top-0.md\\:z-20 > div > div.flex.flex-col.md\\:hidden > div > div.flex.justify-start > div > button";
  var STOP_SELECTOR = "#chat-area > div.flex.min-h-0.flex-1.justify-center.overflow-hidden.overscroll-none > main > div > div > div.relative.flex.flex-col.items-center.pb-6.px-4 > div > div > div > div > form > div > div > div:nth-child(2) > button";
  var marker = "";

  function text(el) {
    if (!el) return "";
    return ((el.innerText || el.textContent || "") + "").replace(/\s+/g, " ").trim();
  }

  function visible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var style = window.getComputedStyle(el);
    if (!style || style.display === "none" || style.visibility === "hidden" || Number(style.opacity) === 0) {
      return false;
    }
    var rect = el.getBoundingClientRect();
    return rect.width > 1 && rect.height > 1;
  }

  function buttons() {
    return Array.prototype.slice.call(document.querySelectorAll("button"));
  }

  function findButton(pred) {
    var list = buttons();
    for (var i = 0; i < list.length; i++) {
      if (pred(list[i])) return list[i];
    }
    return null;
  }

  function isArenaHost(host) {
    return /(^|\.)arena\.ai$/i.test(host || "") || /(^|\.)lmarena\.ai$/i.test(host || "");
  }

  function loginButton() {
    return findButton(function (b) {
      return visible(b) && text(b) === "Log In";
    });
  }

  function googleButton() {
    return findButton(function (b) {
      if (!visible(b)) return false;
      var label = text(b);
      return label.indexOf("Continue with Google") >= 0 || label.indexOf("Google로") >= 0;
    });
  }

  function stopButton() {
    var labeled = findButton(function (b) {
      if (!visible(b)) return false;
      var blob = ((b.getAttribute("aria-label") || "") + " " + text(b)).toLowerCase();
      return blob.indexOf("stop") >= 0 || blob.indexOf("중단") >= 0;
    });
    if (labeled) return labeled;
    var candidate = document.querySelector(STOP_SELECTOR);
    var send = document.querySelector('button[aria-label="Send message"]');
    if (candidate && visible(candidate) && candidate !== send) return candidate;
    return null;
  }

  function languageOf(block) {
    var spans = block.querySelectorAll("span");
    for (var i = 0; i < spans.length; i++) {
      var value = text(spans[i]);
      if (value && value.length < 24 && !/copy|copied/i.test(value)) return value;
    }
    return "";
  }

  function serialize(node) {
    if (!node) return "";
    if (node.getAttribute && node.getAttribute("data-code-block") === "true") {
      var lang = languageOf(node);
      var codeNode = node.querySelector("code") || node.querySelector("pre");
      var code = codeNode ? (codeNode.innerText || codeNode.textContent || "") : "";
      code = (code || "").replace(/\n$/, "");
      return "```" + lang + "\n" + code + "\n```";
    }
    return (node.innerText || node.textContent || "").trim();
  }

  function collect(activeMarker) {
    if (!activeMarker) return "";
    var root = document.querySelector("#chat-area") || document.body;
    var nodes = Array.prototype.slice.call(root.querySelectorAll(".prose, [data-code-block='true']"));
    var index = -1;
    for (var i = 0; i < nodes.length; i++) {
      var raw = nodes[i].innerText || nodes[i].textContent || "";
      if (raw.indexOf(activeMarker) >= 0) index = i;
    }
    if (index < 0) return "";
    var parts = [];
    var holder = nodes[index].innerText || nodes[index].textContent || "";
    var cut = holder.lastIndexOf(activeMarker);
    var tail = holder.slice(cut + activeMarker.length).trim();
    if (tail) parts.push(tail);
    for (var j = index + 1; j < nodes.length; j++) {
      var piece = serialize(nodes[j]);
      if (piece) parts.push(piece);
    }
    return parts.join("\n\n").trim();
  }

  function setAreaValue(area, value) {
    var proto = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value");
    if (proto && proto.set) proto.set.call(area, value);
    else area.value = value;
    area.dispatchEvent(new Event("input", { bubbles: true }));
    area.dispatchEvent(new Event("change", { bubbles: true }));
  }

  window.__arena = {
    probe: function () {
      var host = location.hostname || "";
      return {
        url: location.href || "",
        hasLogin: !!loginButton(),
        hasGoogle: !!googleButton(),
        hasComposer: !!document.querySelector('textarea[name="message"]'),
        onProvider: !!host && !isArenaHost(host),
        stop: !!stopButton()
      };
    },
    clickLogin: function () {
      var button = loginButton();
      if (!button) return { ok: false };
      button.click();
      return { ok: true };
    },
    clickGoogle: function () {
      var button = googleButton();
      if (!button) return { ok: false };
      button.click();
      return { ok: true };
    },
    clickMenu: function () {
      var exact = document.querySelector(MENU_SELECTOR);
      if (visible(exact)) {
        exact.click();
        return { ok: true };
      }
      var list = buttons();
      for (var i = 0; i < list.length; i++) {
        var label = (list[i].getAttribute("aria-label") || "").toLowerCase();
        if (visible(list[i]) && (label.indexOf("menu") >= 0 || label.indexOf("sidebar") >= 0)) {
          list[i].click();
          return { ok: true };
        }
      }
      return { ok: false };
    },
    send: function (value, force) {
      var area = document.querySelector('textarea[name="message"]');
      if (!area) return { ok: false, reason: "no_input" };
      var found = String(value || "").match(/⟦TURN:[^⟧]*⟧/g);
      marker = found && found.length ? found[found.length - 1] : "";
      area.focus();
      if ((area.value || "") !== value) setAreaValue(area, value);
      var button = document.querySelector('button[aria-label="Send message"]');
      if (!button) return { ok: false, reason: "no_send" };
      if (button.disabled && !force) return { ok: false, reason: "send_disabled" };
      if (button.disabled && force) button.disabled = false;
      button.click();
      return { ok: true };
    },
    stop: function () {
      var button = stopButton();
      if (!button) return { ok: false };
      button.click();
      return { ok: true };
    },
    assistant: function () {
      return {
        text: collect(marker),
        stop: !!stopButton()
      };
    }
  };
})();
