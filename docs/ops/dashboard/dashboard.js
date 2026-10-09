// The custom elements behind index.html. One classic script and the DOM; no framework, no bundler.
// Not a module: Chromium and WebKit refuse `type="module"` from a file:// origin, and Firefox alone allows it.
//
// Data comes from two globals: `EJ_LINKS` (links.js, generated from the Markdown) and `EJ_STATUS`
// (status.js, written by scripts/ej.sh). status.js is gitignored and may be absent, so every read
// of it tolerates `undefined` and says what to run instead of throwing.

const SITES = {
  staging: { url: "https://staging.event-junkie.de/", node: "10.10.1.1", host: "staging.event-junkie.de" },
  production: { url: "https://event-junkie.de/", node: "10.10.0.1", host: "event-junkie.de" },
};
const FORWARD_LABELS = { importer: "Importer Swagger", bff: "BFF Swagger", openobserve: "OpenObserve" };
const STALE_AFTER_MS = 60 * 60 * 1000;

const status = () => (typeof window.EJ_STATUS === "object" && window.EJ_STATUS) || null;
const links = () => (typeof window.EJ_LINKS === "object" && window.EJ_LINKS) || null;

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

// A dot that asks the port itself. `no-cors` from a file:// origin yields an opaque response when
// something answers and a rejection when nothing listens, which is all a dot needs to know.
class Dot extends HTMLElement {
  static get observedAttributes() {
    return ["url"];
  }
  connectedCallback() {
    this.className = "inline-block h-2.5 w-2.5 shrink-0 rounded-full bg-zinc-300 dark:bg-zinc-700";
    this.title = "not probed";
    this.probe();
    this.timer = setInterval(() => this.probe(), 15000);
  }
  disconnectedCallback() {
    clearInterval(this.timer);
  }
  attributeChangedCallback() {
    if (this.isConnected) this.probe();
  }
  async probe() {
    const url = this.getAttribute("url");
    if (!url) return;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 2500);
    try {
      await fetch(url, { mode: "no-cors", cache: "no-store", signal: controller.signal });
      this.paint(true);
    } catch {
      this.paint(false);
    } finally {
      clearTimeout(timeout);
    }
  }
  paint(up) {
    this.classList.toggle("bg-emerald-500", up);
    this.classList.toggle("bg-red-500", !up);
    this.classList.remove("bg-zinc-300", "dark:bg-zinc-700");
    this.title = up ? "answers" : "nothing listens — run scripts/ej.sh up";
    this.dataset.state = up ? "up" : "down";
  }
}

// A command with a copy button. The clipboard API needs a secure context; file:// counts as one.
class Command extends HTMLElement {
  connectedCallback() {
    const command = this.getAttribute("command") || "";
    const label = this.getAttribute("label") || "";
    this.className =
      "flex items-center gap-3 rounded-lg border border-zinc-200 bg-white px-3 py-2 dark:border-zinc-800 dark:bg-zinc-900";
    const text = el("div", "min-w-0 flex-1");
    if (label) text.appendChild(el("div", "text-xs text-zinc-500", label));
    text.appendChild(el("code", "block truncate text-sm", command));
    this.appendChild(text);
    this.appendChild(copyButton(command));
  }
}

function copyButton(text) {
  const button = el(
    "button",
    "shrink-0 rounded-md border border-zinc-200 px-2 py-1 text-xs text-zinc-600 hover:bg-zinc-100 dark:border-zinc-700 dark:text-zinc-300 dark:hover:bg-zinc-800",
    "copy",
  );
  button.type = "button";
  button.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(text);
      button.textContent = "copied";
    } catch {
      button.textContent = "select and copy";
    }
    setTimeout(() => (button.textContent = "copy"), 1500);
  });
  return button;
}

class Freshness extends HTMLElement {
  connectedCallback() {
    const s = status();
    if (!s || !s.generatedAt) {
      this.textContent = "no status.js yet — run scripts/ej.sh status";
      return;
    }
    const age = Date.now() - Date.parse(s.generatedAt);
    const minutes = Math.round(age / 60000);
    const when = minutes < 1 ? "just now" : minutes < 60 ? `${minutes} min ago` : `${Math.round(minutes / 60)} h ago`;
    this.textContent = `status.js written ${when}`;
    if (age > STALE_AFTER_MS) this.classList.add("text-amber-600");
  }
}

class Env extends HTMLElement {
  connectedCallback() {
    const name = this.getAttribute("name");
    const site = SITES[name];
    const tpl = document.getElementById("tpl-env").content.cloneNode(true);
    tpl.querySelector("[data-name]").textContent = name;
    const rows = tpl.querySelector("[data-rows]");
    const hint = tpl.querySelector("[data-hint]");
    const s = status();
    const env = s && s.environments && s.environments[name];
    const stale = s && Date.now() - Date.parse(s.generatedAt) > STALE_AFTER_MS;
    tpl.querySelector("[data-tunnel]").textContent = !env
      ? "no status yet"
      : env.tunnel
        ? env.nodeAnswers
          ? "tunnel up"
          : "tunnel up, node silent"
        : "tunnel down";
    if (stale) tpl.querySelector("[data-tunnel]").classList.add("text-amber-600");

    rows.appendChild(row(null, "Site", link(site.url, site.host), `needs the tunnel and /etc/hosts: ${site.node}  ${site.host}`));

    if (env) {
      for (const [key, forward] of Object.entries(env.forwards)) {
        if (key === "database") {
          rows.appendChild(databaseRow(name, forward));
          continue;
        }
        const r = row(forward.url, FORWARD_LABELS[key] || key, link(forward.url, `localhost:${forward.port}`));
        if (forward.up && !forward.managed) r.querySelector("[data-value]").appendChild(el("span", "ml-2 text-xs text-zinc-500", "not started by ej.sh"));
        rows.appendChild(r);
      }
      rows.appendChild(row(null, "Running", versionLink(env.running)));
      const same = env.running.version === env.resolvable.version;
      const resolvable = row(null, "Next", same ? el("span", "text-zinc-500", "same — the cluster is current") : versionLink(env.resolvable));
      rows.appendChild(resolvable);
    } else {
      hint.textContent = "Nothing known about this environment yet. Run scripts/ej.sh status once; ej.sh up writes it too.";
      hint.classList.remove("hidden");
    }
    this.appendChild(tpl);
  }
}

function row(probeUrl, label, valueNode, title) {
  const tpl = document.getElementById("tpl-env-row").content.cloneNode(true);
  const dot = tpl.querySelector("ej-dot");
  if (probeUrl) dot.setAttribute("url", probeUrl);
  else dot.replaceWith(el("span", "inline-block h-2.5 w-2.5 shrink-0"));
  tpl.querySelector("[data-label]").textContent = label;
  const value = tpl.querySelector("[data-value]");
  value.appendChild(valueNode);
  if (title) value.title = title;
  return tpl.firstElementChild;
}

// PostgreSQL speaks no HTTP, so the page cannot probe it. The dot is ej.sh's last pg_isready, and
// the button copies a read-only psql session (CLUSTER_ACCESS.md §7).
function databaseRow(envName, forward) {
  const command =
    `PGPASSWORD="$(kubectl --context event-junkie-${envName} get secret events-db -n event-junkie -o jsonpath='{.data.password}' | base64 -d)" ` +
    `PGOPTIONS='-c default_transaction_read_only=on' psql -h 127.0.0.1 -p ${forward.port} -U events -d events`;
  const value = el("span", "flex items-center gap-2");
  value.appendChild(el("code", "text-sm", `localhost:${forward.port}`));
  value.appendChild(copyButton(command));
  if (forward.up && !forward.managed) value.appendChild(el("span", "text-xs text-zinc-500", "not started by ej.sh"));
  const r = row(null, "PostgreSQL", value, "copy: a read-only psql session");
  r.firstElementChild.className = `inline-block h-2.5 w-2.5 shrink-0 rounded-full ${forward.up ? "bg-emerald-500" : "bg-red-500"}`;
  r.firstElementChild.title = forward.up ? "answered pg_isready when status.js was written" : "no answer when status.js was written — run scripts/ej.sh up";
  return r;
}

function link(href, text) {
  const a = el("a", "text-sky-700 hover:underline dark:text-sky-400", text);
  a.href = href;
  a.target = "_blank";
  a.rel = "noopener";
  return a;
}

function versionLink(v) {
  if (!v || !v.version) return el("span", "text-zinc-500", "unknown");
  if (v.version.startsWith("(")) return el("span", "text-zinc-500", v.version);
  return v.link ? link(v.link, v.version) : el("span", null, v.version);
}

class Links extends HTMLElement {
  connectedCallback() {
    const data = links();
    if (!data) {
      this.textContent = "links.js is missing — run scripts/dashboard-parity.sh";
      return;
    }
    this.className = "grid gap-6 md:grid-cols-2";
    for (const section of data.sections) {
      const box = el("div", "rounded-xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-zinc-900");
      box.id = `links-${section.number}`;
      box.appendChild(el("h3", "text-sm font-semibold", `${section.number}. ${section.title}`));
      const list = el("ul", "mt-2 divide-y divide-zinc-100 text-sm dark:divide-zinc-800");
      for (const r of section.rows) {
        const li = el("li", "py-1.5");
        const head = el("div", "flex items-baseline gap-2");
        head.appendChild(r.url ? link(r.url, r.label) : el("code", "text-xs", r.label));
        if (r.status) {
          const badge = el("span", "ml-auto shrink-0 text-xs text-zinc-500");
          badge.innerHTML = r.status;
          head.appendChild(badge);
        }
        li.appendChild(head);
        const what = el("div", "text-xs text-zinc-600 dark:text-zinc-400");
        what.innerHTML = r.what;
        li.appendChild(what);
        list.appendChild(li);
      }
      box.appendChild(list);
      this.appendChild(box);
    }
  }
}

class Cheatsheet extends HTMLElement {
  connectedCallback() {
    const data = links();
    if (!data) return;
    this.className = "space-y-4";
    for (const group of data.cheatsheet) {
      const box = el("div", "rounded-xl border border-zinc-200 bg-white p-4 dark:border-zinc-800 dark:bg-zinc-900");
      box.appendChild(el("h3", "text-sm font-semibold", group.heading));
      const list = el("div", "mt-2 space-y-1");
      for (const c of group.commands) {
        const line = el("div", "flex items-start gap-3");
        const code = el("pre", "min-w-0 flex-1 overflow-x-auto whitespace-pre text-xs leading-5", c.command);
        line.appendChild(code);
        if (c.note) line.appendChild(el("span", "hidden shrink-0 text-xs text-zinc-500 md:inline", c.note));
        line.appendChild(copyButton(c.command));
        list.appendChild(line);
      }
      box.appendChild(list);
      this.appendChild(box);
    }
  }
}

customElements.define("ej-dot", Dot);
customElements.define("ej-command", Command);
customElements.define("ej-freshness", Freshness);
customElements.define("ej-env", Env);
customElements.define("ej-links", Links);
customElements.define("ej-cheatsheet", Cheatsheet);
